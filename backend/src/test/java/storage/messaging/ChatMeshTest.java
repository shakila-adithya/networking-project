package storage.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import storage.transport.InMemoryTransport;

@Timeout(30)
class ChatMeshTest {
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    private MeshNode node(String id) throws Exception {
        MeshNode node = new MeshNode(id, net.node(id), new FloodForwarder());
        node.start();
        closeables.add(node);
        return node;
    }

    private ChatService chat(MeshNode node, int attempts, long retryMillis) {
        ChatService service = new ChatService(node, attempts, Duration.ofMillis(retryMillis));
        closeables.add(service);
        return service;
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
    void multiHopChatIsDeliveredAndAcknowledged() throws Exception {
        MeshNode a = node("A"), b = node("B"), c = node("C"), d = node("D");
        link(a, b); link(b, c); link(c, d);
        ChatService chatA = chat(a, 3, 500);
        ChatService chatD = chat(d, 3, 500);
        chat(b, 3, 500); chat(c, 3, 500);

        UUID id = chatA.send("D", "hello across three hops");
        await(() -> chatA.status(id) == DeliveryStatus.DELIVERED);
        assertEquals(1, chatD.inbox().size());
        assertEquals("hello across three hops", chatD.inbox().get(0).text());
        assertEquals("A", chatD.inbox().get(0).from());
    }

    @Test
    void simultaneousConnectsStillLeaveOneWorkingLink() throws Exception {
        MeshNode a = node("A"), b = node("B");
        net.setInRange("A", "B", true);
        a.connectTo("B");
        b.connectTo("A");
        await(() -> a.neighbourIds().contains("B") && b.neighbourIds().contains("A"));
        ChatService chatA = chat(a, 3, 500);
        ChatService chatB = chat(b, 3, 500);
        UUID id = chatA.send("B", "after tie-break");
        await(() -> chatA.status(id) == DeliveryStatus.DELIVERED);
        assertEquals(1, chatB.inbox().size());
    }

    @Test
    void ttlStopsMessagesFromTravelingTooFar() throws Exception {
        List<MeshNode> chain = new ArrayList<>();
        for (int i = 0; i <= Message.DEFAULT_TTL + 1; i++) {
            chain.add(node("N" + i));
            if (i > 0) link(chain.get(i - 1), chain.get(i));
        }
        MeshNode first = chain.get(0);
        MeshNode last = chain.get(chain.size() - 1);
        ChatService far = chat(last, 1, 200);
        for (MeshNode n : chain.subList(0, chain.size() - 1)) chat(n, 1, 200);
        ChatService sender = chat(first, 1, 200);
        UUID id = sender.send(last.localId(), "too far");
        await(() -> sender.status(id) == DeliveryStatus.FAILED);
        assertTrue(far.inbox().isEmpty());
    }

    @Test
    void ringReroutesAroundDeadNodeButChainPartitionFails() throws Exception {
        // Ring A-B-C-D-A: kill B, A can still reach C through D.
        MeshNode a = node("A"), b = node("B"), c = node("C"), d = node("D");
        link(a, b); link(b, c); link(c, d); link(d, a);
        ChatService chatA = chat(a, 3, 300);
        ChatService chatC = chat(c, 3, 300);
        chat(b, 3, 300); chat(d, 3, 300);
        net.kill("B");
        await(() -> !a.neighbourIds().contains("B") && !c.neighbourIds().contains("B"));
        UUID id = chatA.send("C", "around the ring");
        await(() -> chatA.status(id) == DeliveryStatus.DELIVERED);
        assertEquals("around the ring", chatC.inbox().get(0).text());

        // Kill D too: A is now alone, so the send must end FAILED rather than claim delivery.
        net.kill("D");
        await(() -> a.neighbourIds().isEmpty());
        UUID lost = chatA.send("C", "nobody home");
        await(() -> chatA.status(lost) == DeliveryStatus.FAILED);
        assertEquals(1, chatC.inbox().size());
    }

    @Test
    void retryDeliversOnceALinkAppears() throws Exception {
        MeshNode a = node("A"), b = node("B");
        ChatService chatA = chat(a, 6, 300);
        ChatService chatB = chat(b, 6, 300);
        UUID id = chatA.send("B", "sent before the link existed");
        assertEquals(DeliveryStatus.PENDING, chatA.status(id));
        Thread.sleep(400);
        link(a, b);
        await(() -> chatA.status(id) == DeliveryStatus.DELIVERED);
        assertEquals(1, chatB.inbox().size());
    }

    @Test
    void broadcastReachesEveryoneOnceWithoutAck() throws Exception {
        MeshNode a = node("A"), b = node("B"), c = node("C");
        link(a, b); link(b, c); link(c, a); // triangle: flooding sees duplicates
        ChatService chatA = chat(a, 3, 300);
        ChatService chatB = chat(b, 3, 300);
        ChatService chatC = chat(c, 3, 300);
        UUID id = chatA.send(Message.BROADCAST, "hi everyone");
        await(() -> chatB.inbox().size() == 1 && chatC.inbox().size() == 1);
        Thread.sleep(300);
        assertEquals(1, chatB.inbox().size());
        assertEquals(1, chatC.inbox().size());
        assertEquals(DeliveryStatus.SENT, chatA.status(id));
    }

    @Test
    void retriesDoNotCreateDuplicateInboxEntries() throws Exception {
        MeshNode a = node("A"), b = node("B"), c = node("C");
        link(a, b); link(b, c);
        ChatService chatA = chat(a, 4, 100);
        chat(b, 4, 100);
        ChatService chatC = chat(c, 4, 100);
        UUID id = chatA.send("C", "once only");
        await(() -> chatA.status(id) == DeliveryStatus.DELIVERED);
        Thread.sleep(500);
        assertEquals(1, chatC.inbox().size());
    }
}
