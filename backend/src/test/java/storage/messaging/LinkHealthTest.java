package storage.messaging;

import org.junit.jupiter.api.Test;
import storage.transport.*;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkHealthTest {
    @Test void silentOpenCarrierIsRemovedWithoutWaitingForTcpEof() throws Exception {
        var network = new InMemoryTransport.Network(); network.setInRange("A", "B", true);
        var silent = new MutedCarrier(network.node("B"));
        try (var a = new MeshNode("A", network.node("A"), new FloodForwarder(), Duration.ofMillis(25), Duration.ofMillis(300));
             var b = new MeshNode("B", silent, new FloodForwarder())) {
            var up = new CountDownLatch(1); var down = new CountDownLatch(1);
            a.onNeighbourChange(id -> up.countDown(), id -> down.countDown());
            a.start(); b.start(); a.connectTo("B");
            assertTrue(up.await(3, TimeUnit.SECONDS));
            silent.muted = true;
            assertTrue(down.await(3, TimeUnit.SECONDS), "A must expire the silent link while the carrier still reports open");
            assertFalse(a.neighbourIds().contains("B"));
        }
    }
    private static final class MutedCarrier implements Transport {
        private final Transport delegate;
        volatile boolean muted;
        MutedCarrier(Transport delegate) { this.delegate = delegate; }
        public String kind() { return "memory"; }
        public String localAddress() { return delegate.localAddress(); }
        public void start(LinkListener listener) throws IOException { delegate.start(link -> listener.onIncomingLink(wrap(link))); }
        public Link connect(String address) throws IOException { return wrap(delegate.connect(address)); }
        private Link wrap(Link raw) {
            return new Link() {
                public String remoteAddress() { return raw.remoteAddress(); }
                public boolean isOpen() { return raw.isOpen(); }
                public void send(byte[] frame) throws IOException { if (!muted) raw.send(frame); }
                public byte[] receive() throws IOException { return raw.receive(); }
                public void close() { raw.close(); }
            };
        }
        public void close() { delegate.close(); }
    }
}
