package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.messaging.ChatService;
import storage.messaging.DeliveryStatus;
import storage.messaging.MeshNode;
import storage.routing.AodvRouter;
import storage.routing.LinkCost;
import storage.transfer.MeshFileService;
import storage.transfer.TransferConfig;
import storage.transfer.TransferState;

/**
 * The whole mesh stack (routing, chat, file transfer) running on {@link BluetoothTransport}. The radio is the
 * in-memory fake, so this proves the adapter and the layers above it, not Bluetooth hardware.
 */
@Timeout(60)
class BluetoothMeshTest {
    @TempDir
    Path tmp;
    private final FakeRfcomm.Air air = new FakeRfcomm.Air();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) closeables.get(i).close();
    }

    private record Device(String id, String address, MeshNode node, ChatService chat, MeshFileService files, FileStore store) { }

    private Device device(String id, String address) throws Exception {
        AodvRouter router = new AodvRouter(LinkCost.HOP_COUNT, Duration.ofMillis(300), 3, Duration.ofSeconds(60));
        BluetoothTransport transport = new BluetoothTransport(air.device(address), address, BluetoothTransport.DEFAULT_CHANNEL);
        MeshNode node = new MeshNode(id, transport, router);
        node.start();
        FileStore store = new FileStore(tmp.resolve("store-" + id));
        MeshFileService files = new MeshFileService(node, store, new TransferConfig(8192, 8,
                Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ZERO, Duration.ofMillis(300)));
        ChatService chat = new ChatService(node, 4, Duration.ofMillis(400));
        closeables.add(node);
        closeables.add(files);
        closeables.add(chat);
        return new Device(id, address, node, chat, files, store);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached in time");
            Thread.sleep(20);
        }
    }

    private void connect(Device from, Device to) throws Exception {
        from.node().connectTo("bt:" + to.address());
        await(() -> from.node().neighbourIds().contains(to.id()) && to.node().neighbourIds().contains(from.id()));
    }

    @Test
    void chatAndFileCrossTwoBluetoothHops() throws Exception {
        Device a = device("A", "AA:00:00:00:00:0A");
        Device b = device("B", "AA:00:00:00:00:0B");
        Device c = device("C", "AA:00:00:00:00:0C");
        connect(a, b);
        connect(b, c);

        UUID chat = a.chat().send("C", "hello over two bluetooth hops");
        await(() -> a.chat().status(chat) == DeliveryStatus.DELIVERED);
        assertEquals("hello over two bluetooth hops", c.chat().inbox().get(0).text());

        byte[] data = new byte[300_000];
        new java.util.Random(3).nextBytes(data);
        Path source = tmp.resolve("source.bin");
        Files.write(source, data);
        FileMetadata meta = new FileMetadata(UUID.randomUUID(), "photo.bin", data.length,
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)),
                Instant.now().toString(), List.of("C"));
        UUID transfer = a.files().send("C", meta, source).transferId();
        await(() -> a.files().status(transfer).orElseThrow().state() == TransferState.DELIVERED);
        assertArrayEquals(data, Files.readAllBytes(c.store().open(meta.fileId()).contentPath()));
    }

    @Test
    void losingARelayLinkIsDetectedAndTheMeshHeals() throws Exception {
        Device a = device("A", "AA:00:00:00:00:1A");
        Device b = device("B", "AA:00:00:00:00:1B");
        Device c = device("C", "AA:00:00:00:00:1C");
        Device d = device("D", "AA:00:00:00:00:1D");
        connect(a, b); connect(b, c); connect(a, d); connect(d, c); // two paths A-B-C and A-D-C
        UUID first = a.chat().send("C", "first");
        await(() -> a.chat().status(first) == DeliveryStatus.DELIVERED);

        b.node().close();                                    // relay B switches off
        await(() -> !a.node().neighbourIds().contains("B"));
        UUID second = a.chat().send("C", "after B left");
        await(() -> a.chat().status(second) == DeliveryStatus.DELIVERED);
        assertEquals(2, c.chat().inbox().size());
    }

    @Test
    void eachNeighbourReportsWhichCarrierItsLinkUses() throws Exception {
        Device a = device("A", "AA:00:00:00:00:2A");
        Device b = device("B", "AA:00:00:00:00:2B");
        connect(a, b);
        assertEquals(java.util.Map.of("B", "bluetooth"), a.node().neighbourKinds());
        assertEquals(java.util.Map.of("A", "bluetooth"), b.node().neighbourKinds(), "the accepting side sees it too");
    }
}
