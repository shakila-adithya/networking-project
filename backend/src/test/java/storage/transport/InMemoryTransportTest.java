package storage.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class InMemoryTransportTest {
    @Test
    void connectRequiresRangeAndRunningNode() throws Exception {
        InMemoryTransport.Network net = new InMemoryTransport.Network();
        InMemoryTransport a = net.node("A");
        InMemoryTransport b = net.node("B");
        a.start(link -> { });
        assertThrows(IOException.class, () -> a.connect("B"), "B not started");
        b.start(link -> { });
        assertThrows(IOException.class, () -> a.connect("B"), "out of range");
        net.setInRange("A", "B", true);
        Link link = a.connect("B");
        assertTrue(link.isOpen());
    }

    @Test
    void framesFlowBothWaysAndLeavingRangeClosesLinks() throws Exception {
        InMemoryTransport.Network net = new InMemoryTransport.Network();
        BlockingQueue<Link> accepted = new LinkedBlockingQueue<>();
        InMemoryTransport a = net.node("A");
        InMemoryTransport b = net.node("B");
        a.start(link -> { });
        b.start(accepted::add);
        net.setInRange("A", "B", true);
        Link ab = a.connect("B");
        Link ba = accepted.poll(5, TimeUnit.SECONDS);
        ab.send(new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, ba.receive());
        ba.send(new byte[] {9});
        assertArrayEquals(new byte[] {9}, ab.receive());

        net.setInRange("A", "B", false);
        assertNull(ab.receive());
        assertNull(ba.receive());
        assertFalse(ab.isOpen());
        assertThrows(IOException.class, () -> ab.send(new byte[] {1}));
    }

    @Test
    void killedNodeDropsLinksAndRefusesConnections() throws Exception {
        InMemoryTransport.Network net = new InMemoryTransport.Network();
        BlockingQueue<Link> accepted = new LinkedBlockingQueue<>();
        InMemoryTransport a = net.node("A");
        InMemoryTransport b = net.node("B");
        a.start(link -> { });
        b.start(accepted::add);
        net.setInRange("A", "B", true);
        Link ab = a.connect("B");
        net.kill("B");
        assertNull(ab.receive());
        assertThrows(IOException.class, () -> a.connect("B"));
    }
}
