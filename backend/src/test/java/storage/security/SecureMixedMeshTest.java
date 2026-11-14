package storage.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import storage.files.*;
import storage.messaging.*;
import storage.routing.*;
import storage.transfer.*;
import storage.transport.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP plus an explicitly simulated carrier. Proves encrypted mixed-carrier routing, not radio interoperability. */
@Timeout(45)
class SecureMixedMeshTest {
    @TempDir Path root;
    private final List<AutoCloseable> owned = new ArrayList<>();
    private MeshNode node(String id, Transport transport) throws Exception {
        MeshSecurity security = new MeshSecurity("mixed-test", "test-only-mixed-mesh-password");
        MeshNode node = new MeshNode(id, new SecureTransport(transport, security),
                new AodvRouter(LinkCost.HOP_COUNT, Duration.ofMillis(300), 5, Duration.ofSeconds(30)));
        node.start(); owned.add(node); return node;
    }
    private MeshFileService files(MeshNode node, FileStore store) throws Exception {
        var service = new MeshFileService(node, store, new TransferConfig(4096, 4, Duration.ofMillis(200),
                Duration.ofSeconds(3), Duration.ofMillis(10), Duration.ofMillis(300)));
        owned.add(service); return service;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Condition not reached before deadline"); Thread.sleep(10);
        }
    }
    private static void connect(MeshNode from, MeshNode to, String address) throws Exception {
        from.connectTo(address);
        await(() -> from.neighbourIds().contains(to.localId()) && to.neighbourIds().contains(from.localId()));
    }
    @Test void encryptedMixedCarrierFileRecoversAnAlternateRouteWithoutStoringOnRelays() throws Exception {
        var net = new InMemoryTransport.Network();
        for (String[] edge : new String[][]{{"B", "C"}, {"B", "D"}, {"D", "C"}}) net.setInRange(edge[0], edge[1], true);
        var tcpA = new TcpTransport("127.0.0.1", 0);
        var tcpB = new TcpTransport("127.0.0.1", 0);
        try {
            var a = node("A", tcpA);
            var b = node("B", new CompositeTransport(List.of(new SimulatedCarrier(net.node("B")), tcpB)));
            var c = node("C", new SimulatedCarrier(net.node("C")));
            var d = node("D", new SimulatedCarrier(net.node("D")));
            var storeA = new FileStore(root.resolve("A")); var storeB = new FileStore(root.resolve("B"));
            var storeC = new FileStore(root.resolve("C")); var storeD = new FileStore(root.resolve("D"));
            var sender = files(a, storeA); files(b, storeB); files(c, storeC); files(d, storeD);
            connect(a, b, tcpB.localAddress()); connect(b, c, "sim:C"); connect(b, d, "sim:D"); connect(d, c, "sim:C");
            assertEquals("tcp", a.neighbourKinds().get("B")); assertEquals("memory", b.neighbourKinds().get("C"));
            Path source = root.resolve("source.bin"); byte[] bytes = new byte[512 * 1024]; new Random(43).nextBytes(bytes); Files.write(source, bytes);
            var metadata = new FileMetadata(UUID.randomUUID(), "source.bin", bytes.length, HashUtils.sha256(source), Instant.now().toString(), List.of("C"));
            var transfer = sender.send("C", metadata, source);
            await(() -> sender.status(transfer.transferId()).orElseThrow().ackedChunks() > 0);
            net.setInRange("B", "C", false);
            await(() -> !b.neighbourIds().contains("C"));
            await(() -> Set.of(TransferState.DELIVERED, TransferState.FAILED).contains(sender.status(transfer.transferId()).orElseThrow().state()));
            if (sender.status(transfer.transferId()).orElseThrow().state() == TransferState.FAILED) {
                assertTrue(sender.resume(transfer.transferId()));
                await(() -> sender.status(transfer.transferId()).orElseThrow().state() == TransferState.DELIVERED);
            }
            assertTrue(storeC.verify(metadata.fileId(), metadata));
            assertArrayEquals(bytes, Files.readAllBytes(storeC.open(metadata.fileId()).contentPath()));
            assertTrue(storeB.inventory().isEmpty(), "Relay B must not retain a user-file backup");
            assertTrue(storeD.inventory().isEmpty(), "Alternate relay D must not retain a user-file backup");
        } finally {
            Collections.reverse(owned);
            for (AutoCloseable closeable : owned) closeable.close();
        }
    }
    private static final class SimulatedCarrier implements Transport {
        private final Transport delegate;
        SimulatedCarrier(Transport delegate) { this.delegate = delegate; }
        public String kind() { return "memory"; }
        public String localAddress() { return "sim:" + delegate.localAddress(); }
        public boolean handles(String address) { return address.startsWith("sim:"); }
        public void start(LinkListener listener) throws IOException { delegate.start(raw -> listener.onIncomingLink(adapt(raw))); }
        public Link connect(String address) throws IOException { return adapt(delegate.connect(address.substring(4))); }
        private static Link adapt(Link raw) {
            return new Link() {
                public String kind() { return "memory"; }
                public String remoteAddress() { return "sim:" + raw.remoteAddress(); }
                public boolean isOpen() { return raw.isOpen(); }
                public void send(byte[] frame) throws IOException { raw.send(frame); }
                public byte[] receive() throws IOException { return raw.receive(); }
                public void close() { raw.close(); }
            };
        }
        public void close() { delegate.close(); }
    }

    @Test void closingTransportUnblocksAnIncompleteAuthentication() throws Exception {
        var tcp = new TcpTransport("127.0.0.1", 0);
        var transport = new SecureTransport(tcp, new MeshSecurity("shutdown", "shutdown-test-password"));
        transport.start(link -> fail("Unauthenticated link must never be delivered"));
        int port = Integer.parseInt(tcp.localAddress().substring(tcp.localAddress().lastIndexOf(':') + 1));
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            assertTrue(socket.getInputStream().readNBytes(4).length > 0, "Server began PAKE, peer has not replied");
            long start = System.nanoTime(); transport.close();
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos());
            socket.getInputStream().readAllBytes(); // Must terminate; no stranded blocking handshake.
        } finally { transport.close(); }
    }
}
