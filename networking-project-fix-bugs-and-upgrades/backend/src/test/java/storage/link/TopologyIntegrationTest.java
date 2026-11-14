package storage.link;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import storage.discovery.Candidate;
import storage.messaging.ChatService;
import storage.messaging.DeliveryStatus;
import storage.messaging.MeshNode;
import storage.routing.AodvRouter;
import storage.routing.LinkCost;
import storage.routing.RoutingTable;
import storage.transport.InMemoryTransport;

@Timeout(40)
class TopologyIntegrationTest {
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();
    private final Map<String, MeshNode> nodes = new HashMap<>();
    private final Map<String, ChatService> chats = new HashMap<>();
    private final Map<String, TopologyController> controllers = new HashMap<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) c.close();
    }

    private TopologyPolicy policy(int min, int target, int max) {
        return new TopologyPolicy(min, target, max, 20, Duration.ofSeconds(30),
                Duration.ofMillis(100), Duration.ofMillis(400), Duration.ofMillis(60));
    }

    private void device(String id, TopologyPolicy policy) throws Exception {
        AodvRouter router = new AodvRouter(LinkCost.HOP_COUNT, Duration.ofMillis(300), 3, Duration.ofSeconds(60));
        MeshNode node = new MeshNode(id, net.node(id), router);
        node.start();
        ChatService chat = new ChatService(node, 6, Duration.ofMillis(400));
        TopologyController controller = new TopologyController(node, policy, () -> router.routes().stream()
                .filter(RoutingTable.Route::valid).map(RoutingTable.Route::destination).collect(Collectors.toSet()));
        nodes.put(id, node);
        chats.put(id, chat);
        controllers.put(id, controller);
        closeables.add(controller);
        closeables.add(chat);
        closeables.add(node);
    }

    /** Puts devices in mutual radio range and lets each one "discover" the others. */
    private void hear(String listener, int signal, String... heard) {
        for (String other : heard) {
            net.setInRange(listener, other, true);
            controllers.get(listener).offerPermanent(new Candidate(other, other, signal));
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached in time");
            Thread.sleep(20);
        }
    }

    @Test
    void unknownDevicesAreAllTreatedAsBridgesUpToTheCap() throws Exception {
        TopologyPolicy p = policy(2, 3, 5);
        for (String id : List.of("A", "B", "C", "D", "E")) device(id, p);
        controllers.get("A").start();
        hear("A", 50, "B", "C", "D", "E");
        // All four are unknown to A, so each is a potential bridge: bounded only by maxLinks, not target.
        await(() -> nodes.get("A").neighbourIds().size() == 5 - 1);
        Thread.sleep(300);
        assertEquals(4, nodes.get("A").neighbourIds().size());
    }

    @Test
    void hardCapIsNeverExceededByOwnDials() throws Exception {
        TopologyPolicy p = policy(1, 2, 3);
        for (String id : List.of("A", "B", "C", "D", "E", "F")) device(id, p);
        controllers.get("A").start();
        hear("A", 50, "B", "C", "D", "E", "F");
        await(() -> nodes.get("A").neighbourIds().size() >= 3);
        Thread.sleep(400);
        assertEquals(3, nodes.get("A").neighbourIds().size());
    }

    @Test
    void bridgeJoinsTwoSeparatedClustersSoTheyCanChat() throws Exception {
        TopologyPolicy p = policy(1, 2, 4);
        for (String id : List.of("A", "B", "C", "D")) device(id, p);
        for (String id : List.of("A", "B", "C", "D")) controllers.get(id).start();
        hear("A", 50, "B");
        hear("B", 50, "A");
        hear("C", 50, "D");
        hear("D", 50, "C");
        await(() -> nodes.get("A").neighbourIds().contains("B") && nodes.get("C").neighbourIds().contains("D"));

        UUID before = chats.get("A").send("D", "no path yet");
        await(() -> chats.get("A").status(before) == DeliveryStatus.FAILED);

        hear("B", 60, "C"); // B moves into range of C: the clusters merge
        await(() -> nodes.get("B").neighbourIds().contains("C"));
        UUID after = chats.get("A").send("D", "now across the bridge");
        await(() -> chats.get("A").status(after) == DeliveryStatus.DELIVERED);
        assertEquals("now across the bridge", chats.get("D").inbox().get(0).text());
    }

    @Test
    void lostNeighbourIsReplacedWithoutNewDiscovery() throws Exception {
        TopologyPolicy p = policy(2, 2, 3);
        for (String id : List.of("A", "B", "C", "D", "E")) device(id, p);
        controllers.get("A").start();
        // A can hear four devices; with unknown ids all are bridges, so give A routes first by linking
        // it to B and C, then offering the spares only afterwards.
        hear("A", 90, "B");
        hear("A", 80, "C");
        await(() -> nodes.get("A").neighbourIds().equals(Set.of("B", "C")));
        hear("A", 50, "D");
        hear("A", 40, "E");
        await(() -> nodes.get("A").neighbourIds().size() == 3); // bridge slot used by one spare
        Set<String> spare = new java.util.HashSet<>(nodes.get("A").neighbourIds());
        spare.removeAll(Set.of("B", "C"));

        net.kill("B");
        await(() -> !nodes.get("A").neighbourIds().contains("B"));
        // Still at least minLinks (2) because the spare replaced B; nothing was re-offered.
        await(() -> nodes.get("A").neighbourIds().size() >= 2);
        assertTrue(nodes.get("A").neighbourIds().containsAll(Set.of("C")));
        assertFalse(nodes.get("A").neighbourIds().contains("B"));
        assertEquals(1, spare.size());
    }

    @Test
    void redundancyLinkKeepsDevicesConnectedWhenTheRelayDies() throws Exception {
        TopologyPolicy p = policy(1, 3, 5);
        for (String id : List.of("A", "B", "C")) device(id, p);
        // Start as a chain A - B - C.
        net.setInRange("A", "B", true);
        net.setInRange("B", "C", true);
        nodes.get("A").connectTo("B");
        nodes.get("B").connectTo("C");
        await(() -> nodes.get("B").neighbourIds().size() == 2);
        UUID warm = chats.get("A").send("C", "learn the route");
        await(() -> chats.get("A").status(warm) == DeliveryStatus.DELIVERED);

        // A and C move into range of each other; A's controller adds the direct redundancy link.
        controllers.get("A").start();
        hear("A", 70, "C");
        await(() -> nodes.get("A").neighbourIds().contains("C") || nodes.get("C").neighbourIds().contains("A"));

        net.kill("B");
        await(() -> !nodes.get("A").neighbourIds().contains("B"));
        UUID id = chats.get("A").send("C", "after the relay died");
        await(() -> chats.get("A").status(id) == DeliveryStatus.DELIVERED);
        assertEquals(2, chats.get("C").inbox().size());
    }

    @Test
    void connectNowDialsAKnownCandidateAndReportsFailuresReadably() throws Exception {
        TopologyPolicy p = policy(1, 2, 4);
        for (String id : List.of("A", "B", "C")) device(id, p);       // C is never started as a reachable device
        net.setInRange("A", "B", true);
        controllers.get("A").offer(new Candidate("B", "B", 70));
        controllers.get("A").offer(new Candidate("C", "C", 70));      // out of range: dialing it must fail

        controllers.get("A").connectNow("B");                          // no start(): only this explicit dial happens
        await(() -> nodes.get("A").neighbourIds().contains("B"));
        controllers.get("A").connectNow("B");                          // already connected: quietly fine

        java.io.IOException unknown = assertThrows(java.io.IOException.class, () -> controllers.get("A").connectNow("ZZ"));
        assertTrue(unknown.getMessage().contains("scan again"), unknown.getMessage());
        java.io.IOException refused = assertThrows(java.io.IOException.class, () -> controllers.get("A").connectNow("C"));
        assertTrue(refused.getMessage().contains("out of range"), refused.getMessage());
        var status = controllers.get("A").status().stream().filter(s -> s.candidate().deviceId().equals("C")).findFirst().orElseThrow();
        assertEquals(1, status.failures());
        assertTrue(status.lastError().contains("out of range"));
    }
}
