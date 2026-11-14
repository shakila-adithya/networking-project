package storage.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * SIMULATION ONLY. A virtual radio environment for tests: nodes can connect only while they are
 * "in range", and taking a node down or out of range closes the links between them, like a lost
 * radio connection. It proves routing logic, not Bluetooth hardware interoperability.
 */
public final class InMemoryTransport implements Transport {
    private static final byte[] EOF_MARKER = new byte[0];

    /** The shared virtual environment that connects InMemoryTransport nodes. */
    public static final class Network {
        private final Map<String, InMemoryTransport> nodes = new ConcurrentHashMap<>();
        private final Set<String> ranges = ConcurrentHashMap.newKeySet();
        private final List<MemoryLink> links = new CopyOnWriteArrayList<>();

        public InMemoryTransport node(String name) {
            return new InMemoryTransport(this, name);
        }

        private static String key(String a, String b) {
            return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        }

        /** Puts two nodes in or out of radio range. Leaving range closes their existing links. */
        public void setInRange(String a, String b, boolean inRange) {
            if (inRange) {
                ranges.add(key(a, b));
                return;
            }
            ranges.remove(key(a, b));
            for (MemoryLink link : new ArrayList<>(links)) {
                if (link.connects(a, b)) {
                    link.close();
                }
            }
        }

        boolean inRange(String a, String b) {
            return ranges.contains(key(a, b));
        }

        /** Simulates a device switching off: all its links drop and it no longer accepts. */
        public void kill(String name) {
            InMemoryTransport node = nodes.remove(name);
            if (node != null) {
                node.listener = null;
            }
            for (MemoryLink link : new ArrayList<>(links)) {
                if (link.involves(name)) {
                    link.close();
                }
            }
        }
    }

    private final Network network;
    private final String name;
    private volatile LinkListener listener;

    private InMemoryTransport(Network network, String name) {
        this.network = network;
        this.name = name;
    }

    @Override
    public String kind() {
        return "memory";
    }

    @Override
    public String localAddress() {
        return name;
    }

    @Override
    public void start(LinkListener incoming) {
        this.listener = incoming;
        network.nodes.put(name, this);
    }

    @Override
    public Link connect(String address) throws IOException {
        InMemoryTransport target = network.nodes.get(address);
        if (listener == null || target == null || target.listener == null) {
            throw new IOException(address + " is not running");
        }
        if (!network.inRange(name, address)) {
            throw new IOException(address + " is out of range");
        }
        MemoryLink mine = new MemoryLink(network, name, address);
        MemoryLink theirs = new MemoryLink(network, address, name);
        mine.peer = theirs;
        theirs.peer = mine;
        network.links.add(mine);
        network.links.add(theirs);
        target.listener.onIncomingLink(theirs);
        return mine;
    }

    @Override
    public void close() {
        network.kill(name);
    }

    private static final class MemoryLink implements Link {
        private final Network network;
        private final String local;
        private final String remote;
        private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        private volatile MemoryLink peer;
        private volatile boolean open = true;

        MemoryLink(Network network, String local, String remote) {
            this.network = network;
            this.local = local;
            this.remote = remote;
        }

        boolean connects(String a, String b) {
            return (local.equals(a) && remote.equals(b)) || (local.equals(b) && remote.equals(a));
        }

        boolean involves(String name) {
            return local.equals(name) || remote.equals(name);
        }

        @Override
        public String remoteAddress() {
            return remote;
        }

        @Override
        public void send(byte[] frame) throws IOException {
            if (frame.length > MAX_FRAME_SIZE) {
                throw new IOException("frame too large: " + frame.length);
            }
            MemoryLink other = peer;
            if (!open || other == null || !other.open) {
                throw new IOException("link to " + remote + " is closed");
            }
            other.inbox.add(frame.clone());
        }

        @Override
        public byte[] receive() throws IOException {
            try {
                byte[] frame = inbox.take();
                if (frame == EOF_MARKER) {
                    inbox.add(EOF_MARKER); // later receive() calls also see EOF
                    return null;
                }
                return frame;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while receiving", e);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            network.links.remove(this);
            inbox.add(EOF_MARKER);
            MemoryLink other = peer;
            if (other != null && other.open) {
                other.close();
            }
        }
    }
}
