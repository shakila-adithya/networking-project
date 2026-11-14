package storage.messaging;
import org.junit.jupiter.api.*;
import storage.transport.*;
import storage.security.*;
import storage.discovery.Candidate;
import storage.link.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
/** Real TCP plus an explicitly simulated second carrier. No Bluetooth interoperability claim. */
@Timeout(40)
class HybridRecoveryTest {
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Recovery deadline exceeded"); Thread.sleep(10); }
    }
    static SecureTransport secure(Transport transport) { return new SecureTransport(transport, new MeshSecurity("hybrid", "hybrid-test-only-password")); }
    static class Simulated implements Transport {
        final Transport delegate;
        Simulated(Transport delegate) { this.delegate = delegate; }
        public String kind() { return "memory"; }
        public String localAddress() { return "sim:" + delegate.localAddress(); }
        public boolean handles(String a) { return a.startsWith("sim:"); }
        public void start(LinkListener listener) throws IOException { delegate.start(link -> listener.onIncomingLink(wrap(link))); }
        public Link connect(String a) throws IOException { return wrap(delegate.connect(a.substring(4))); }
        public void close() { delegate.close(); }
        Link wrap(Link link) { return new Link() {
            public String remoteAddress() { return "sim:" + link.remoteAddress(); } public String kind() { return "memory"; }
            public void send(byte[] bytes) throws IOException { link.send(bytes); } public byte[] receive() throws IOException { return link.receive(); }
            public boolean isOpen() { return link.isOpen(); } public void close() { link.close(); }
        }; }
    }
    static final class TrackedTcp implements Transport {
        final TcpTransport delegate = new TcpTransport("127.0.0.1", 0);
        final java.util.Set<Link> links = java.util.concurrent.ConcurrentHashMap.newKeySet();
        public String kind() { return "tcp"; } public String localAddress() { return delegate.localAddress(); }
        public boolean handles(String address) { return delegate.handles(address); }
        public void start(LinkListener listener) throws IOException { delegate.start(link -> { links.add(link); listener.onIncomingLink(link); }); }
        public Link connect(String address) throws IOException { Link link = delegate.connect(address); links.add(link); return link; }
        public void close() { links.forEach(Link::close); delegate.close(); }
    }
    @Test void lostPrimaryPromotesAHealthyFallbackWithoutLosingTheNeighbourOrOtherLinks() throws Exception {
        var net = new InMemoryTransport.Network(); net.setInRange("A", "B", true);
        var tcpA = new TrackedTcp(); var tcpB = new TrackedTcp(); var tcpC = new TrackedTcp();
        try (var a = new MeshNode("A", secure(new CompositeTransport(List.of(new Simulated(net.node("A")), tcpA))), new FloodForwarder());
             var b = new MeshNode("B", secure(new CompositeTransport(List.of(new Simulated(net.node("B")), tcpB))), new FloodForwarder());
             var c = new MeshNode("C", secure(tcpC), new FloodForwarder());
             var chatA = new ChatService(a, 10, Duration.ofMillis(100)); var chatB = new ChatService(b, 10, Duration.ofMillis(100))) {
            a.start(); b.start(); c.start(); a.connectTo(tcpB.localAddress(), "B"); a.connectTo(tcpC.localAddress(), "C");
            AtomicInteger lostB = new AtomicInteger(), lostC = new AtomicInteger();
            a.onNeighbourChange(id -> { }, id -> { if (id.equals("B")) lostB.incrementAndGet(); if (id.equals("C")) lostC.incrementAndGet(); });
            a.connectTo("sim:B", "B"); await(() -> a.carrierStatus().size() == 3 && b.carrierStatus().size() == 2);
            assertEquals("tcp", a.neighbourKinds().get("B"));
            tcpB.close(); await(() -> a.neighbourKinds().get("B").equals("memory"));
            UUID sent = chatA.send("B", "healthy fallback carries this"); await(() -> chatA.status(sent) == DeliveryStatus.DELIVERED);
            assertEquals(0, lostB.get()); assertEquals(0, lostC.get()); assertTrue(a.neighbourIds().contains("C"));
            assertEquals("healthy fallback carries this", chatB.inbox().getFirst().text());
        }
    }
    @Test void controllersDiscoverANewPathAfterARelayDisappearsAndKeepTheUnaffectedPeer() throws Exception {
        var net = new InMemoryTransport.Network();
        Map<String, MeshNode> nodes = new LinkedHashMap<>(); List<AutoCloseable> tasks = new ArrayList<>();
        try {
            for (String id : List.of("A", "B", "C", "D", "E")) { var n = new MeshNode(id, secure(net.node(id)), new FloodForwarder()); nodes.put(id, n); tasks.add(n); n.start(); }
            for (String[] pair : new String[][]{{"A","B"},{"A","E"},{"B","C"}}) { net.setInRange(pair[0], pair[1], true); nodes.get(pair[0]).connectTo(pair[1], pair[1]); }
            AtomicInteger lostHealthy = new AtomicInteger(); nodes.get("E").onNeighbourChange(id -> {}, id -> lostHealthy.incrementAndGet());
            var policy = new TopologyPolicy(1, 2, 4, 0, Duration.ofSeconds(30), Duration.ofMillis(50), Duration.ofMillis(500), Duration.ofMillis(50));
            var controlA = new TopologyController(nodes.get("A"), policy, Set::of); var controlD = new TopologyController(nodes.get("D"), policy, Set::of);
            tasks.add(controlA); tasks.add(controlD); controlA.start(); controlD.start();
            var chatA = new ChatService(nodes.get("A"), 30, Duration.ofMillis(100)); var chatC = new ChatService(nodes.get("C"), 30, Duration.ofMillis(100)); tasks.add(chatA); tasks.add(chatC);
            nodes.get("B").close(); await(() -> !nodes.get("A").neighbourIds().contains("B"));
            UUID pending = chatA.send("C", "repair only the failed path");
            net.setInRange("A", "D", true); net.setInRange("D", "C", true);
            controlA.offer(new Candidate("D", "D", 80)); controlD.offer(new Candidate("C", "C", 80));
            await(() -> nodes.get("A").neighbourIds().contains("D") && nodes.get("D").neighbourIds().contains("C"));
            await(() -> chatA.status(pending) == DeliveryStatus.DELIVERED);
            assertEquals(0, lostHealthy.get()); assertTrue(nodes.get("A").neighbourIds().contains("E"));
            assertEquals("repair only the failed path", chatC.inbox().getFirst().text());
        } finally { Collections.reverse(tasks); for (var task : tasks) task.close(); }
    }
}
