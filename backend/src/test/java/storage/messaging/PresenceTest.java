package storage.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import storage.routing.AodvRouter;
import storage.routing.LinkCost;
import storage.transport.InMemoryTransport;

/** Display names travel in HELLO (neighbours) and PRESENCE (everyone reachable). */
@Timeout(30)
class PresenceTest {
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) c.close();
    }

    private MeshNode node(String id, String name) throws Exception {
        MeshNode node = new MeshNode(id, net.node(id),
                new AodvRouter(LinkCost.HOP_COUNT, Duration.ofMillis(300), 3, Duration.ofSeconds(60)));
        node.setDisplayName(name);
        node.start();
        closeables.add(node);
        return node;
    }

    private void link(MeshNode a, MeshNode b) throws Exception {
        net.setInRange(a.localId(), b.localId(), true);
        a.connectTo(b.localId());
        await(() -> a.neighbourIds().contains(b.localId()) && b.neighbourIds().contains(a.localId()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached in time");
            Thread.sleep(10);
        }
    }

    @Test
    void neighboursLearnTheNameFromHello() throws Exception {
        MeshNode a = node("A", "Amaya's phone");
        MeshNode b = node("B", "");
        link(a, b);
        await(() -> "Amaya's phone".equals(b.names().get("A")));
        assertFalse(a.names().containsKey("B"), "B chose no name, so A shows its id");
        assertEquals("Amaya's phone", a.displayName());
    }

    @Test
    void namesReachDevicesSeveralHopsAwayAndFollowRenames() throws Exception {
        MeshNode a = node("A", "Alpha");
        MeshNode b = node("B", "Bravo");
        MeshNode c = node("C", "Charlie");
        link(a, b);
        link(b, c);
        await(() -> "Alpha".equals(c.names().get("A")) && "Charlie".equals(a.names().get("C")));

        a.setDisplayName("Alpha 2");
        await(() -> "Alpha 2".equals(c.names().get("A")));
        a.setDisplayName("");
        await(() -> !c.names().containsKey("A"));
        assertFalse(b.names().containsKey("A"), "clearing the name removes it everywhere");
    }

    @Test
    void aNewNeighbourAlsoLearnsTheNamesBehindUs() throws Exception {
        MeshNode a = node("A", "Alpha");
        MeshNode b = node("B", "Bravo");
        link(a, b);
        await(() -> "Alpha".equals(b.names().get("A")));

        MeshNode d = node("D", "Delta");
        link(d, b);                                   // D meets only B ...
        await(() -> "Alpha".equals(d.names().get("A")) && "Bravo".equals(d.names().get("B")));
        await(() -> "Delta".equals(a.names().get("D"))); // ... and A learns D from D's own announcement
    }

    @Test
    void namesAreBoundedAndSanitisedOnTheWire() {
        String longName = "é".repeat(100);                       // 200 bytes in UTF-8
        byte[] encoded = MeshNode.encodeName(longName);
        assertTrue(encoded.length <= 96);
        assertEquals(0, encoded.length % 2, "never cut a two-byte character in half");
        assertEquals("é".repeat(48), MeshNode.decodeName(encoded));

        assertEquals("", MeshNode.decodeName(new byte[0]));
        assertEquals("", MeshNode.decodeName(new byte[97]), "oversized payloads are ignored");
        assertEquals("", MeshNode.decodeName("bad\nname".getBytes(StandardCharsets.UTF_8)), "control characters are refused");
        assertEquals("ok", MeshNode.decodeName("  ok ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theNameTableStaysBounded() throws Exception {
        MeshNode a = node("A", "");
        MeshNode b = node("B", "");
        link(a, b);
        for (int i = 0; i < 700; i++) {
            b.originate(new Message(java.util.UUID.randomUUID(), "D" + i, Message.BROADCAST, MessageType.PRESENCE, 8,
                    System.currentTimeMillis(), ("name" + i).getBytes(StandardCharsets.UTF_8)));
        }
        await(() -> a.names().containsKey("D699"));          // all 700 announcements have been processed
        Map<String, String> known = a.names();
        assertTrue(known.size() <= 512, "at most 512 names are remembered, got " + known.size());
        assertTrue(known.size() >= 500, "and the table is actually used, got " + known.size());
        assertFalse(known.containsKey("D0"), "the oldest names are evicted first");
    }
}
