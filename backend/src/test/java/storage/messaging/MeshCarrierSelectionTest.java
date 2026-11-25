package storage.messaging;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import storage.transport.InMemoryTransport;
import storage.transport.Link;
import static org.junit.jupiter.api.Assertions.*;

class MeshCarrierSelectionTest {
    @Test void tieBreakSelectsTheReplacementBeforeClosingTheOldLink() throws Exception {
        var network = new InMemoryTransport.Network();
        try (var node = new MeshNode("A", network.node("A"), new FloodForwarder())) {
            node.start();
            TestLink incoming = new TestLink("old"), outgoing = new TestLink("new");
            assertTrue(node.register("B", incoming, false, "B"));
            assertTrue(node.register("B", outgoing, true, "B"));
            assertFalse(incoming.isOpen());
            assertEquals("new", node.neighbourAddresses().get("B"));
            assertTrue(node.sendTo("B", Message.create("A", "B", MessageType.CHAT, new byte[0])));
            // An old reader finishing must not unregister its replacement.
            node.unregister("B", incoming);
            assertTrue(node.neighbourIds().contains("B"));
        }
    }

    @Test void unregisterRemovesTheSelectedCarrierEvenBeforeItsSocketCloses() throws Exception {
        var network = new InMemoryTransport.Network();
        try (var node = new MeshNode("A", network.node("A"), new FloodForwarder())) {
            node.start();
            TestLink link = new TestLink("peer");
            assertTrue(node.register("B", link, true, "B"));
            node.unregister("B", link);
            assertTrue(node.neighbourIds().isEmpty());
            link.close();
        }
    }

    private static final class TestLink implements Link {
        private final String address;
        private volatile boolean open = true;
        TestLink(String address) { this.address = address; }
        public String kind() { return "memory"; }
        public String remoteAddress() { return address; }
        public boolean isOpen() { return open; }
        public void send(byte[] frame) throws IOException {
            if (!open) throw new IOException("closed");
        }
        public byte[] receive() { return null; }
        public void close() { open = false; }
    }
}
