package storage.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.messaging.MeshNode;
import storage.routing.AodvRouter;
import storage.transport.InMemoryTransport;

@Timeout(90)
class MeshFileTransferTest {
    @TempDir
    Path tmp;
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();
    private final Map<String, MeshNode> nodes = new HashMap<>();
    private final Map<String, AodvRouter> routers = new HashMap<>();
    private final Map<String, FileStore> stores = new HashMap<>();
    private final Map<String, MeshFileService> services = new HashMap<>();
    private final Map<String, Integer> costs = new HashMap<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) c.close();
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private TransferConfig config(long chunkIntervalMillis, long stallMillis) {
        return new TransferConfig(4096, 8, Duration.ofMillis(300), Duration.ofMillis(stallMillis),
                Duration.ofMillis(chunkIntervalMillis), Duration.ofMillis(300));
    }

    private void device(String id, TransferConfig config) throws Exception {
        AodvRouter router = new AodvRouter(n -> costs.getOrDefault(pair(id, n), 1),
                Duration.ofMillis(300), 3, Duration.ofSeconds(60));
        MeshNode node = new MeshNode(id, net.node(id), router);
        node.start();
        FileStore store = new FileStore(tmp.resolve("store-" + id));
        MeshFileService service = new MeshFileService(node, store, config);
        nodes.put(id, node);
        routers.put(id, router);
        stores.put(id, store);
        services.put(id, service);
        closeables.add(service);
        closeables.add(node);
    }

    private void devices(TransferConfig config, String... ids) throws Exception {
        for (String id : ids) device(id, config);
    }

    private void link(String a, String b) throws Exception {
        link(a, b, 1);
    }

    private void link(String a, String b, int cost) throws Exception {
        costs.put(pair(a, b), cost);
        net.setInRange(a, b, true);
        nodes.get(a).connectTo(b);
        await(() -> nodes.get(a).neighbourIds().contains(b) && nodes.get(b).neighbourIds().contains(a));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached in time");
            Thread.sleep(10);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private FileMetadata metadata(String name, byte[] data, String... recipients) throws Exception {
        return new FileMetadata(UUID.randomUUID(), name, data.length, sha256(data), Instant.now().toString(),
                recipients.length == 0 ? null : List.of(recipients));
    }

    private TransferStatus send(String from, String to, FileMetadata meta, byte[] data) throws Exception {
        Path source = tmp.resolve("src-" + UUID.randomUUID());
        Files.write(source, data);
        return services.get(from).send(to, meta, source);
    }

    private TransferStatus status(String from, UUID id) {
        return services.get(from).status(id).orElseThrow();
    }

    private void awaitState(String from, UUID id, TransferState state) throws InterruptedException {
        await(() -> status(from, id).state() == state);
    }

    private void assertStored(String device, FileMetadata meta, byte[] data) throws Exception {
        FileStore.StoredFile stored = stores.get(device).open(meta.fileId());
        assertEquals(meta, stored.metadata());
        assertArrayEquals(data, Files.readAllBytes(stored.contentPath()));
    }

    @Test
    void smallFileCrossesThreeHops() throws Exception {
        devices(config(0, 10_000), "A", "B", "C", "D");
        link("A", "B"); link("B", "C"); link("C", "D");
        byte[] data = randomBytes(50_000);
        FileMetadata meta = metadata("notes.bin", data, "D");
        UUID id = send("A", "D", meta, data).transferId();
        awaitState("A", id, TransferState.DELIVERED);
        TransferStatus done = status("A", id);
        assertEquals(100, done.percent());
        assertEquals(done.totalChunks(), done.ackedChunks());
        assertEquals(13, done.totalChunks()); // 50000 / 4096 rounded up
        assertStored("D", meta, data);
        assertFalse(Files.exists(tmp.resolve("store-A/mesh-transfers/outgoing/" + id + ".bin")),
                "staging file removed after delivery");
    }

    @Test
    void largeFileWithManyChunksIsDeliveredIntact() throws Exception {
        devices(config(0, 20_000), "A", "B", "C");
        link("A", "B"); link("B", "C");
        byte[] data = randomBytes(2 * 1024 * 1024);
        FileMetadata meta = metadata("big.bin", data, "C");
        UUID id = send("A", "C", meta, data).transferId();
        awaitState("A", id, TransferState.DELIVERED);
        assertEquals(512, status("A", id).totalChunks());
        assertStored("C", meta, data);
    }

    @Test
    void emptyFileIsDelivered() throws Exception {
        devices(config(0, 10_000), "A", "B");
        link("A", "B");
        byte[] data = new byte[0];
        FileMetadata meta = metadata("empty.txt", data, "B");
        UUID id = send("A", "B", meta, data).transferId();
        awaitState("A", id, TransferState.DELIVERED);
        assertStored("B", meta, data);
    }

    @Test
    void transferSurvivesTheRelayDyingByReroutingMidTransfer() throws Exception {
        devices(config(4, 15_000), "A", "B", "C", "D");
        link("A", "B"); link("B", "C");          // cheap path A-B-C
        link("A", "D", 5); link("D", "C", 5);     // expensive alternative A-D-C
        byte[] data = randomBytes(1024 * 1024);   // 256 chunks, paced so it is still running when B dies
        FileMetadata meta = metadata("reroute.bin", data, "C");
        UUID id = send("A", "C", meta, data).transferId();
        await(() -> status("A", id).percent() >= 20);
        assertEquals("B", routers.get("A").routes().stream()
                .filter(r -> r.destination().equals("C")).findFirst().orElseThrow().nextHop());

        net.kill("B");
        awaitState("A", id, TransferState.DELIVERED);
        assertStored("C", meta, data);
        assertEquals("D", routers.get("A").routes().stream()
                .filter(r -> r.destination().equals("C") && r.valid()).findFirst().orElseThrow().nextHop(),
                "traffic moved to the alternative path");
    }

    @Test
    void stalledTransferFailsThenResumesFromWhatTheReceiverAlreadyHas() throws Exception {
        devices(config(4, 1_500), "A", "B", "C");
        link("A", "B"); link("B", "C");
        byte[] data = randomBytes(1024 * 1024);
        FileMetadata meta = metadata("resume.bin", data, "C");
        UUID id = send("A", "C", meta, data).transferId();
        await(() -> status("A", id).percent() >= 25);

        net.setInRange("B", "C", false);               // the only path to C disappears
        awaitState("A", id, TransferState.FAILED);
        TransferStatus failed = status("A", id);
        assertTrue(failed.error().contains("resumed"), failed.error());
        assertTrue(failed.ackedChunks() > 0 && failed.ackedChunks() < failed.totalChunks());
        assertFalse(failed.percent() == 100);
        assertThrows(Exception.class, () -> stores.get("C").open(meta.fileId()), "nothing was committed");

        net.setInRange("B", "C", true);                 // C comes back into range
        nodes.get("B").connectTo("C");
        await(() -> nodes.get("B").neighbourIds().contains("C") && nodes.get("C").neighbourIds().contains("B"));
        int sentBefore = failed.chunksSent();
        assertTrue(services.get("A").resume(id));
        awaitState("A", id, TransferState.DELIVERED);
        assertStored("C", meta, data);
        int sentAfterResume = status("A", id).chunksSent() - sentBefore;
        assertTrue(sentAfterResume <= failed.totalChunks() - failed.ackedChunks() + 2 * 8,
                "resume sent " + sentAfterResume + " chunks but only " + (failed.totalChunks() - failed.ackedChunks())
                        + " were missing");
    }

    @Test
    void unreachableRecipientFailsAndDeliversNothing() throws Exception {
        devices(config(0, 1_200), "A", "B", "C");
        link("A", "B");                                  // C is out of everyone's range
        byte[] data = randomBytes(20_000);
        FileMetadata meta = metadata("nowhere.bin", data, "C");
        UUID id = send("A", "C", meta, data).transferId();
        awaitState("A", id, TransferState.FAILED);
        assertTrue(status("A", id).error().contains("route unavailable"));
        assertThrows(Exception.class, () -> stores.get("C").open(meta.fileId()));
        assertFalse(services.get("A").resume(UUID.randomUUID()), "unknown transfers cannot be resumed");
    }

    @Test
    void sendingTheSameFileAgainIsRecognisedAsAlreadyStored() throws Exception {
        devices(config(0, 10_000), "A", "B");
        link("A", "B");
        byte[] data = randomBytes(30_000);
        FileMetadata meta = metadata("twice.bin", data, "B");
        awaitState("A", send("A", "B", meta, data).transferId(), TransferState.DELIVERED);
        UUID second = send("A", "B", meta, data).transferId();
        awaitState("A", second, TransferState.DELIVERED);
        assertEquals(0, status("A", second).chunksSent(), "no chunks needed for a file the receiver already has");
        assertStored("B", meta, data);
    }

    @Test
    void contentThatDoesNotMatchItsHashIsRefused() throws Exception {
        devices(config(0, 10_000), "A", "B");
        link("A", "B");
        byte[] data = randomBytes(20_000);
        FileMetadata wrong = new FileMetadata(UUID.randomUUID(), "bad.bin", data.length, "b".repeat(64),
                Instant.now().toString(), List.of("B"));
        UUID id = send("A", "B", wrong, data).transferId();
        awaitState("A", id, TransferState.FAILED);
        assertTrue(status("A", id).error().contains("SHA-256"), status("A", id).error());
        assertThrows(Exception.class, () -> stores.get("B").open(wrong.fileId()));
        try (var left = Files.list(tmp.resolve("store-B/mesh-transfers/incoming"))) {
            assertEquals(0, left.count(), "partial file removed");
        }
    }

    @Test
    void senderRejectsInvalidRequestsBeforeSendingAnything() throws Exception {
        devices(config(0, 5_000), "A", "B");
        byte[] data = randomBytes(5_000);
        FileMetadata meta = metadata("x.bin", data, "B");
        Path source = tmp.resolve("src.bin");
        Files.write(source, data);
        MeshFileService a = services.get("A");
        assertThrows(IllegalArgumentException.class, () -> a.send("A", meta, source), "cannot send to itself");
        assertThrows(IllegalArgumentException.class, () -> a.send("../etc", meta, source));
        assertThrows(IllegalArgumentException.class, () -> a.send("C", meta, source), "not in the recipient list");
        Files.write(source, new byte[10]);
        assertThrows(IllegalArgumentException.class, () -> a.send("B", meta, source), "size mismatch");
        assertTrue(a.transfers().isEmpty());
    }

    @Test
    void leftoverPartialFilesFromAnEarlierRunAreRemovedAtStartup() throws Exception {
        Path root = tmp.resolve("store-Z");
        Files.createDirectories(root.resolve("mesh-transfers/incoming"));
        Files.writeString(root.resolve("mesh-transfers/incoming/old.part"), "stale");
        Files.createDirectories(root.resolve("mesh-transfers/outgoing"));
        Files.writeString(root.resolve("mesh-transfers/outgoing/old.bin"), "stale");
        device("Z", config(0, 1_000));
        assertFalse(Files.exists(root.resolve("mesh-transfers/incoming/old.part")));
        assertFalse(Files.exists(root.resolve("mesh-transfers/outgoing/old.bin")));
    }
}
