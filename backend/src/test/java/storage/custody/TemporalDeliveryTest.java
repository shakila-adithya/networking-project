package storage.custody;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import storage.messaging.*;
import storage.security.*;
import storage.transport.*;
import storage.files.*;
import storage.files.FileStore;
import storage.transfer.*;
import javax.crypto.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Secure simulated contacts, explicitly not a hardware/radio interoperability test. */
@Timeout(60)
class TemporalDeliveryTest {
    @TempDir Path root;
    final InMemoryTransport.Network net = new InMemoryTransport.Network();
    final Map<String, SecretKey> keys = new HashMap<>();
    final List<Device> owned = new ArrayList<>();
    record Device(MeshNode node, CustodyService custody, ChatService chat, FileStore store, MeshFileService files) implements AutoCloseable {
        public void close() { files.close(); chat.close(); node.close(); }
    }
    Device device(String id) throws Exception { return device(id, 1_000_000, Duration.ofHours(24)); }
    Device device(String id, long quota, Duration retention) throws Exception {
        SecretKey key = keys.computeIfAbsent(id, ignored -> { try { return KeyGenerator.getInstance("AES").generateKey(); } catch (Exception e) { throw new IllegalStateException(e); } });
        var node = new MeshNode(id, new SecureTransport(net.node(id), new MeshSecurity("temporal", "test-only-temporal-password")), new FloodForwarder());
        var custody = new CustodyService(node, new EncryptedSpool(root.resolve(id + "/spool"), key, quota), retention, ignored -> null);
        var chat = new ChatService(node, 2, Duration.ofMillis(100), new EncryptedSpool(root.resolve(id + "/history"), key, 1_000_000));
        var store = new FileStore(root.resolve(id + "/files"));
        var config = new TransferConfig(4096, 8, Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ZERO, Duration.ofMillis(200));
        var files = new MeshFileService(node, store, config); var result = new Device(node, custody, chat, store, files); owned.add(result); node.start(); return result;
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Temporal contact condition timed out"); Thread.sleep(10); }
    }
    void link(Device a, Device b) throws Exception { net.setInRange(a.node().localId(), b.node().localId(), true); a.node().connectTo(b.node().localId(), b.node().localId()); await(() -> a.node().neighbourIds().contains(b.node().localId())); }
    void unlink(String a, String b) throws Exception { net.setInRange(a, b, false); }
    long queued(Device device, String type) { Object value = ((Map<?, ?>)device.custody().status().get("byType")).get(type); return value instanceof Number n ? n.longValue() : 0; }
    @AfterEach void close() { Collections.reverse(owned); owned.forEach(Device::close); }
    @Test void messageCrossesSeparateContactsAndRelayRestartWithoutContinuousPath() throws Exception {
        Device a = device("A"), b = device("B"), c = device("C");
        UUID chat = a.chat().send("C", "Wait for a future contact");
        link(a, b); await(() -> queued(b, "CHAT") > 0);
        unlink("A", "B"); b.close(); Device recoveredB = device("B");
        link(recoveredB, c); await(() -> c.chat().inbox().size() == 1); await(() -> queued(recoveredB, "ACK") > 0);
        assertEquals(DeliveryStatus.PENDING, a.chat().status(chat));
        unlink("B", "C");
        link(a, recoveredB); await(() -> a.chat().status(chat) == DeliveryStatus.DELIVERED);
        assertEquals("Wait for a future contact", c.chat().inbox().getFirst().text());
        c.close(); Device recoveredC = device("C"); assertEquals(1, recoveredC.chat().inbox().size());
        assertEquals(DeliveryStatus.DELIVERED, a.chat().status(chat));
    }
    @Test void chunkedFileAndFinalReceiptSurviveSeparateContactsAndSenderRestart() throws Exception {
        Device a = device("A"), b = device("B"), c = device("C");
        byte[] content = new byte[12000]; new Random(73).nextBytes(content); Path source = root.resolve("source.bin"); Files.write(source, content);
        var metadata = new FileMetadata(UUID.randomUUID(), "source.bin", content.length, HashUtils.sha256(source), Instant.now().toString(), List.of("C"));
        var transfer = a.files().send("C", metadata, source);
        link(a, b); await(() -> queued(b, "FILE_OFFER") > 0); unlink("A", "B");
        link(b, c); await(() -> queued(b, "FILE_ACCEPT") > 0); unlink("B", "C");
        link(a, b); await(() -> queued(b, "FILE_CHUNK") >= 3); unlink("A", "B");
        a.close(); Device recoveredA = device("A");
        link(b, c); await(() -> { try { return c.store().verify(metadata.fileId(), metadata); } catch (Exception e) { return false; } });
        await(() -> queued(b, "FILE_DONE") > 0); unlink("B", "C");
        link(recoveredA, b); await(() -> recoveredA.files().status(transfer.transferId()).orElseThrow().state() == TransferState.DELIVERED);
        assertArrayEquals(content, Files.readAllBytes(c.store().open(metadata.fileId()).contentPath()));
        assertTrue(b.store().inventory().isEmpty(), "Temporary custody is not a received-file backup");
        assertArrayEquals(content, Files.readAllBytes(source));
    }
    @Test void quotaRejectionAndExpiryNeverBecomeFinalDeliveryOrRemoveTheSenderOriginal() throws Exception {
        Device a = device("A"), full = device("Full", 1024, Duration.ofHours(24));
        UUID rejected = a.chat().send("C", "x".repeat(1800));
        link(a, full);
        await(() -> full.custody().status().get("error").toString().contains("full"));
        assertEquals(0, queued(full, "CHAT")); assertEquals(1, queued(a, "CHAT"));
        assertEquals(DeliveryStatus.PENDING, a.chat().status(rejected)); unlink("A", "Full");
        Device relay = device("Relay", 1_000_000, Duration.ofMillis(900));
        link(a, relay); await(() -> queued(relay, "CHAT") == 1); unlink("A", "Relay");
        await(() -> ((Number)relay.custody().status().get("expired")).longValue() == 1);
        assertEquals(0, queued(relay, "CHAT")); assertEquals(1, queued(a, "CHAT"));
        assertEquals(DeliveryStatus.PENDING, a.chat().status(rejected));
        assertTrue(relay.custody().status().get("error").toString().contains("expired"));
    }

}
