package storage.link;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import storage.discovery.Candidate;
import storage.messaging.FloodForwarder;
import storage.messaging.MeshNode;
import storage.transport.InMemoryTransport;

class TopologyPlanTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();
    private final TopologyPolicy policy = new TopologyPolicy(2, 3, 5, 20,
            Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(8), Duration.ofSeconds(1));

    private TopologyController controller() {
        MeshNode node = new MeshNode("ME", net.node("ME"), new FloodForwarder());
        return new TopologyController(node, policy, Set::of, now::get);
    }

    private static Candidate c(String id, int signal) {
        return new Candidate(id, id, signal);
    }

    private static List<String> ids(List<Candidate> list) {
        return list.stream().map(Candidate::deviceId).toList();
    }

    @Test
    void redundancyLinksFillToTargetStrongestFirst() {
        TopologyController tc = controller();
        for (Candidate cand : List.of(c("R1", 80), c("R2", 60), c("R3", 90))) tc.offer(cand);
        List<Candidate> plan = tc.plan(Set.of("X"), Set.of("X", "R1", "R2", "R3"));
        assertEquals(List.of("R3", "R1"), ids(plan)); // degree 1 -> target 3 means 2 more
    }

    @Test
    void bridgeMayUseTheReservedSlotButRedundancyMayNot() {
        TopologyController tc = controller();
        tc.offer(c("BRIDGE", 70));
        tc.offer(c("SAMECOMP", 99));
        Set<String> three = Set.of("N1", "N2", "N3"); // already at target
        List<Candidate> plan = tc.plan(three, Set.of("N1", "N2", "N3", "SAMECOMP"));
        assertEquals(List.of("BRIDGE"), ids(plan));
    }

    @Test
    void nothingIsDialledAtTheHardCap() {
        TopologyController tc = controller();
        tc.offer(c("BRIDGE", 90));
        assertTrue(tc.plan(Set.of("A", "B", "C", "D", "E"), Set.of()).isEmpty());
    }

    @Test
    void weakCandidatesOnlyAcceptedWhileBelowMinimumLinks() {
        TopologyController tc = controller();
        tc.offer(c("WEAK", 5));
        assertEquals(List.of("WEAK"), ids(tc.plan(Set.of("N1"), Set.of("N1"))), "degree 1 < min 2");
        assertTrue(tc.plan(Set.of("N1", "N2"), Set.of("N1", "N2")).isEmpty(), "degree 2 is enough");
    }

    @Test
    void neverPlansSelfOrExistingNeighbours() {
        TopologyController tc = controller();
        tc.offer(c("ME", 90));
        tc.offer(c("N1", 90));
        assertTrue(tc.plan(Set.of("N1"), Set.of("N1")).isEmpty());
    }

    @Test
    void failuresBackOffExponentiallyAndCapAtMax() {
        TopologyController tc = controller(); // ME never started: every dial fails
        tc.offerPermanent(c("GONE", 90));
        long[] waits = {1_000, 2_000, 4_000, 8_000, 8_000, 8_000}; // retryBase 1 s doubling, retryMax 8 s
        for (long wait : waits) {
            tc.tick();
            now.addAndGet(wait - 1);
            assertTrue(tc.plan(Set.of(), Set.of()).isEmpty(), "still backing off for " + wait);
            now.addAndGet(2);
            assertEquals(1, tc.plan(Set.of(), Set.of()).size(), "retry allowed after " + wait);
        }
        assertEquals(waits.length, tc.status().get(0).failures());
        assertFalse(tc.status().get(0).lastError().isEmpty());
    }

    @Test
    void dialsStillConnectingCountTowardTheCap() throws Exception {
        // Two real in-range devices: after a successful dial the link is not yet a neighbour,
        // but must still occupy a slot so later ticks cannot exceed maxLinks.
        TopologyPolicy tight = new TopologyPolicy(1, 2, 3, 20, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(8), Duration.ofSeconds(1));
        MeshNode node = new MeshNode("ME", net.node("ME"), new FloodForwarder());
        node.start();
        TopologyController tc = new TopologyController(node, tight, Set::of, now::get);
        for (String id : List.of("P", "Q", "R", "S")) {
            MeshNode peer = new MeshNode(id, net.node(id), new FloodForwarder());
            peer.start();
            net.setInRange("ME", id, true);
            tc.offerPermanent(c(id, 50));
        }
        tc.tick();                                   // dials 3 of the 4 (maxLinks = 3)
        // Pretend the links have not registered yet: the three pending dials must fill the cap.
        assertTrue(tc.plan(Set.of(), Set.of()).isEmpty(), "in-flight dials occupy the free slots");
        long deadline = System.currentTimeMillis() + 5_000;
        while (node.neighbourIds().size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        Thread.sleep(200);
        assertEquals(3, node.neighbourIds().size());
        now.addAndGet(5_000);                        // pending window over, but neighbours now fill the cap
        assertTrue(tc.plan(node.neighbourIds(), Set.of()).isEmpty());
        node.close();
    }

    @Test
    void candidatesExpireUnlessReofferedOrPermanent() {
        TopologyController tc = controller();
        tc.offer(c("TEMP", 90));
        tc.offerPermanent(c("STATIC", 90));
        now.addAndGet(29_000);
        assertEquals(2, tc.plan(Set.of(), Set.of()).size());
        now.addAndGet(2_000);
        assertEquals(List.of("STATIC"), ids(tc.plan(Set.of(), Set.of())));
    }

    @Test
    void policyRejectsInconsistentLimits() {
        assertThrows(IllegalArgumentException.class, () -> new TopologyPolicy(3, 2, 5, 0,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TopologyPolicy(2, 5, 5, 0,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO));
    }
}
