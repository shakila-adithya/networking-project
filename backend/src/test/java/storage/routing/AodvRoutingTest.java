package storage.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import storage.messaging.ChatService;
import storage.messaging.DeliveryStatus;
import storage.messaging.MeshNode;
import storage.messaging.Message;
import storage.messaging.MessageCodec;
import storage.messaging.MessageType;
import storage.transport.InMemoryTransport;
import storage.transport.Link;

@Timeout(40)
class AodvRoutingTest {
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();
    private final Map<String, Integer> linkCosts = new HashMap<>();
    private final Map<String, AodvRouter> routers = new HashMap<>();
    private final Map<String, ChatService> chats = new HashMap<>();
    private final Map<String, MeshNode> nodes = new HashMap<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) c.close();
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private void node(String... ids) throws Exception {
        for (String id : ids) {
            AodvRouter router = new AodvRouter(
                    neighbour -> linkCosts.getOrDefault(pair(id, neighbour), 1),
                    Duration.ofMillis(300), 3, Duration.ofSeconds(60));
            MeshNode node = new MeshNode(id, net.node(id), router);
            node.start();
            ChatService chat = new ChatService(node, 6, Duration.ofMillis(400));
            routers.put(id, router);
            nodes.put(id, node);
            chats.put(id, chat);
            closeables.add(chat);
            closeables.add(node);
        }
    }

    private void link(String a, String b) throws Exception {
        link(a, b, 1);
    }

    private void link(String a, String b, int cost) throws Exception {
        linkCosts.put(pair(a, b), cost);
        net.setInRange(a, b, true);
        nodes.get(a).connectTo(b);
        await(() -> nodes.get(a).neighbourIds().contains(b) && nodes.get(b).neighbourIds().contains(a));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached in time");
            Thread.sleep(10);
        }
    }

    private RoutingTable.Route route(String from, String to) {
        return routers.get(from).routes().stream()
                .filter(r -> r.destination().equals(to)).findFirst().orElse(null);
    }

    private UUID send(String from, String to, String text) {
        return chats.get(from).send(to, text);
    }

    private void awaitDelivered(String from, UUID id) throws InterruptedException {
        await(() -> chats.get(from).status(id) == DeliveryStatus.DELIVERED);
    }

    @Test
    void chainDiscoversRouteOnceAndAckUsesReverseRoute() throws Exception {
        node("A", "B", "C", "D");
        link("A", "B"); link("B", "C"); link("C", "D");
        UUID id = send("A", "D", "across three hops");
        awaitDelivered("A", id);
        assertEquals("across three hops", chats.get("D").inbox().get(0).text());
        RoutingTable.Route r = route("A", "D");
        assertTrue(r.valid());
        assertEquals("B", r.nextHop());
        assertEquals(3, r.hops());
        assertEquals(3, r.cost());
        assertEquals("C", route("D", "A").nextHop());
        assertEquals(1, routers.get("A").requestsSent(), "A discovered once");
        assertEquals(0, routers.get("D").requestsSent(), "the ACK reused the reverse route");
    }

    @Test
    void cheaperLongerPathBeatsExpensiveDirectLink() throws Exception {
        node("A", "B", "D");
        link("A", "D", 10);
        link("A", "B", 1);
        link("B", "D", 1);
        UUID id = send("A", "D", "avoid the weak link");
        awaitDelivered("A", id);
        await(() -> {
            RoutingTable.Route r = route("A", "D");
            return r != null && r.valid() && r.nextHop().equals("B") && r.cost() == 2;
        });
    }

    @Test
    void deadRelayIsRoutedAround() throws Exception {
        node("A", "B", "C", "D");
        link("A", "B"); link("B", "D");
        link("A", "C", 5); link("C", "D", 5);
        UUID first = send("A", "D", "via the cheap path");
        awaitDelivered("A", first);
        assertEquals("B", route("A", "D").nextHop());

        net.kill("B");
        await(() -> route("A", "D") != null && !route("A", "D").valid());
        UUID second = send("A", "D", "after B died");
        awaitDelivered("A", second);
        RoutingTable.Route r = route("A", "D");
        assertTrue(r.valid());
        assertEquals("C", r.nextHop());
        assertEquals(2, chats.get("D").inbox().size());
    }

    @Test
    void routeErrorReachesTheSourceThroughRelays() throws Exception {
        node("A", "B", "C", "D");
        link("A", "B"); link("B", "C"); link("C", "D");
        awaitDelivered("A", send("A", "D", "establish"));
        net.setInRange("C", "D", false);
        await(() -> route("A", "D") != null && !route("A", "D").valid());
        await(() -> route("B", "D") != null && !route("B", "D").valid());
    }

    @Test
    void partitionedDestinationEndsFailedNotDelivered() throws Exception {
        node("A", "B", "C");
        link("A", "B");
        UUID id = send("A", "C", "nobody can hear this");
        await(() -> chats.get("A").status(id) == DeliveryStatus.FAILED);
        assertTrue(chats.get("C").inbox().isEmpty());
        assertNull(route("A", "C"));
    }

    @Test
    void relayRepairsBrokenRouteByDiscoveringAnotherPath() throws Exception {
        // B has no route to D yet (as after a link break). A raw "A" link hands it a routed CHAT for D.
        node("B", "E", "D");
        link("B", "E"); link("E", "D");
        InMemoryTransport rawA = net.node("A");
        rawA.start(link -> { });
        net.setInRange("A", "B", true);
        Link toB = rawA.connect("B");
        assertEquals(MessageType.HELLO, MessageCodec.decode(toB.receive()).type());
        toB.send(MessageCodec.encode(Message.create("A", Message.BROADCAST, MessageType.HELLO, new byte[0])));
        await(() -> nodes.get("B").neighbourIds().contains("A"));

        UUID chatId = UUID.randomUUID();
        byte[] payload = ByteBuffer.allocate(16 + 5).putLong(chatId.getMostSignificantBits())
                .putLong(chatId.getLeastSignificantBits()).put("hello".getBytes(StandardCharsets.UTF_8)).array();
        toB.send(MessageCodec.encode(Message.create("A", "D", MessageType.CHAT, payload)));

        await(() -> !chats.get("D").inbox().isEmpty());
        assertEquals("hello", chats.get("D").inbox().get(0).text());
        assertEquals("E", route("B", "D").nextHop());
        toB.close();
    }

    @Test
    void relayThatCannotRepairReportsRouteErrorUpstream() throws Exception {
        node("B");
        InMemoryTransport rawA = net.node("A");
        rawA.start(link -> { });
        net.setInRange("A", "B", true);
        Link toB = rawA.connect("B");
        toB.receive(); // B's HELLO
        toB.send(MessageCodec.encode(Message.create("A", Message.BROADCAST, MessageType.HELLO, new byte[0])));
        await(() -> nodes.get("B").neighbourIds().contains("A"));
        toB.send(MessageCodec.encode(Message.create("A", "Z", MessageType.CHAT, new byte[20])));

        Message seen;
        do {
            byte[] frame = toB.receive();
            assertNotNull(frame, "link closed before an error arrived");
            seen = MessageCodec.decode(frame);
        } while (seen.type() == MessageType.RREQ || seen.type() == MessageType.PRESENCE);
        assertEquals(MessageType.RERR, seen.type());
        assertEquals("A", seen.destination());
        toB.close();
    }
}
