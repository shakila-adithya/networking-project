package storage.messaging;

import storage.transport.Link;
import storage.transport.Transport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One device in the mesh. Owns the neighbour links created through a {@link Transport}, delivers
 * messages addressed to this device, and relays the rest using a {@link Forwarder}. Duplicates are
 * dropped by message id and relayed messages lose one TTL per hop.
 */
public final class MeshNode implements AutoCloseable {
    private final String localId;
    private final Transport transport;
    private final Forwarder forwarder;
    private final DuplicateCache seen = new DuplicateCache(4096);
    private final Map<String, NeighbourLink> neighbours = new ConcurrentHashMap<>();
    private final Map<MessageType, Consumer<Message>> handlers = new ConcurrentHashMap<>();
    private final List<Consumer<String>> upListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> downListeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile String displayName = "";
    private static final int MAX_NAME_BYTES = 96;
    private static final int MAX_KNOWN_NAMES = 512;
    private static final int NAMES_SENT_TO_NEW_NEIGHBOUR = 64;
    /** Friendly names learned from HELLO and PRESENCE messages (device id -> name). Bounded. */
    private final Map<String, String> names = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_KNOWN_NAMES;
        }
    });

    private record NeighbourLink(Link link, String initiator) { }

    public MeshNode(String localId, Transport transport, Forwarder forwarder) {
        if (localId.isEmpty() || localId.equals(Message.BROADCAST)) {
            throw new IllegalArgumentException("invalid device id");
        }
        this.localId = localId;
        this.transport = transport;
        this.forwarder = forwarder;
        forwarder.attach(this);
        handlers.put(MessageType.PRESENCE, this::onPresence);
    }

    public String localId() {
        return localId;
    }

    public void start() throws IOException {
        running = true;
        transport.start(link -> launchReader(link, false));
        announcePresence();
    }

    /** The friendly name this device shows to others (empty = none chosen, others show the id). */
    public String displayName() {
        return displayName;
    }

    /** Sets the friendly name and announces it to every reachable device. */
    public void setDisplayName(String name) {
        this.displayName = name == null ? "" : name;
        announcePresence();
    }

    /** Friendly names of the other devices this node knows about (device id -> name). */
    public Map<String, String> names() {
        synchronized (names) {
            return new HashMap<>(names);
        }
    }

    private void announcePresence() {
        if (running) {
            originate(Message.create(localId, Message.BROADCAST, MessageType.PRESENCE, encodeName(displayName)));
        }
    }

    private void onPresence(Message message) {
        learnName(message.source(), decodeName(message.payload()));
    }

    private void learnName(String deviceId, String name) {
        if (deviceId.equals(localId)) {
            return;
        }
        if (name.isEmpty()) {
            names.remove(deviceId);
        } else {
            names.put(deviceId, name);
        }
    }

    /** Tells a newly connected neighbour the names we already know, so it learns devices behind us too. */
    private void syncNamesTo(String neighbourId) {
        int sent = 0;
        for (Map.Entry<String, String> entry : names().entrySet()) {
            if (sent++ >= NAMES_SENT_TO_NEW_NEIGHBOUR) {
                return;
            }
            if (!entry.getKey().equals(neighbourId)) {
                // ttl 1: the neighbour records it but does not forward it.
                sendTo(neighbourId, new Message(UUID.randomUUID(), entry.getKey(), Message.BROADCAST,
                        MessageType.PRESENCE, 1, System.currentTimeMillis(), encodeName(entry.getValue())));
            }
        }
    }

    static byte[] encodeName(String name) {
        if (name == null || name.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_NAME_BYTES) {
            return bytes;
        }
        int end = MAX_NAME_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--; // do not cut a multi-byte character in half
        }
        return Arrays.copyOf(bytes, end);
    }

    /** Decodes a received name; anything oversized or containing control characters is ignored. */
    static String decodeName(byte[] payload) {
        if (payload.length == 0 || payload.length > MAX_NAME_BYTES) {
            return "";
        }
        String name = new String(payload, StandardCharsets.UTF_8).strip();
        return name.codePoints().anyMatch(Character::isISOControl) ? "" : name;
    }

    /** Opens a link to a device at a transport address; the neighbour's id is learned from its HELLO. */
    public void connectTo(String address) throws IOException {
        launchReader(transport.connect(address), true);
    }

    public void setHandler(MessageType type, Consumer<Message> handler) {
        handlers.put(type, handler);
    }

    /** Adds listeners for neighbours appearing and disappearing. Several can be registered. */
    public void onNeighbourChange(Consumer<String> up, Consumer<String> down) {
        upListeners.add(up);
        downListeners.add(down);
    }

    /** Which kind of link each neighbour is on: "bluetooth" for a Bluetooth address, otherwise "tcp". */
    public Map<String, String> neighbourKinds() {
        Map<String, String> kinds = new HashMap<>();
        for (Map.Entry<String, NeighbourLink> entry : neighbours.entrySet()) {
            kinds.put(entry.getKey(), entry.getValue().link().remoteAddress().startsWith("bt:") ? "bluetooth" : "tcp");
        }
        return kinds;
    }

    public Set<String> neighbourIds() {
        return new HashSet<>(neighbours.keySet());
    }

    /** Sends a new message from this device. Returns the number of neighbours it was handed to. */
    public int originate(Message message) {
        seen.firstSeen(message.id());
        return sendToHops(message, null);
    }

    private void launchReader(Link link, boolean initiatedLocally) {
        try {
            Thread worker = new Thread(new LinkReader(this, link, initiatedLocally),
                    "mesh-link-" + localId + "-" + link.remoteAddress());
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | Error failure) {
            link.close();
            throw failure;
        }
    }

    // ---- called by LinkReader on its own thread ----

    void sendHello(Link link) throws IOException {
        link.send(MessageCodec.encode(Message.create(localId, Message.BROADCAST, MessageType.HELLO,
                encodeName(displayName))));
    }

    /** Registers a handshaken link. Returns false if the link lost to an existing one and must be closed. */
    boolean register(String remoteId, Link link, boolean initiatedLocally, String remoteName) {
        if (!running || remoteId.equals(localId)) {
            return false;
        }
        String initiator = initiatedLocally ? localId : remoteId;
        boolean[] accepted = {false};
        Link[] replaced = {null};
        neighbours.compute(remoteId, (id, existing) -> {
            if (existing == null || !existing.link().isOpen()) {
                accepted[0] = true;
                return new NeighbourLink(link, initiator);
            }
            // Simultaneous connect: both ends keep the link started by the smaller id.
            if (initiator.compareTo(existing.initiator()) < 0) {
                accepted[0] = true;
                replaced[0] = existing.link();
                return new NeighbourLink(link, initiator);
            }
            return existing;
        });
        if (replaced[0] != null) {
            replaced[0].close();
        }
        if (accepted[0]) {
            learnName(remoteId, remoteName);
            upListeners.forEach(listener -> listener.accept(remoteId));
            forwarder.neighbourChanged(remoteId, true);
            syncNamesTo(remoteId);
            announcePresence(); // devices beyond us learn our name; the new neighbour already has it from HELLO
        }
        return accepted[0];
    }

    void unregister(String remoteId, Link link) {
        NeighbourLink current = neighbours.get(remoteId);
        if (current != null && current.link() == link && neighbours.remove(remoteId, current)) {
            downListeners.forEach(listener -> listener.accept(remoteId));
            forwarder.neighbourChanged(remoteId, false);
        }
    }

    void receive(Message message, String fromNeighbour) {
        if (message.type() == MessageType.HELLO) {
            return;
        }
        if (forwarder.handleControl(message, fromNeighbour)) {
            return;
        }
        if (!seen.firstSeen(message.id())) {
            return;
        }
        if (message.destination().equals(localId) || message.isBroadcast()) {
            Consumer<Message> handler = handlers.get(message.type());
            if (handler != null) {
                try {
                    handler.accept(message);
                } catch (RuntimeException e) {
                    System.err.println("Message handler failed: " + e);
                }
            }
        }
        boolean forMe = message.destination().equals(localId);
        if (!forMe && message.ttl() > 1) {
            sendToHops(message.withTtl(message.ttl() - 1), fromNeighbour);
        }
    }

    /** Sends one message to a specific directly connected neighbour. Returns false if that was not possible. */
    public boolean sendTo(String neighbourId, Message message) {
        NeighbourLink neighbour = neighbours.get(neighbourId);
        if (neighbour == null) {
            return false;
        }
        try {
            neighbour.link().send(MessageCodec.encode(message));
            return true;
        } catch (IOException e) {
            System.err.println("Send to " + neighbourId + " failed: " + e.getMessage());
            neighbour.link().close(); // its reader sees EOF and removes the neighbour
            return false;
        }
    }

    private int sendToHops(Message message, String fromNeighbour) {
        byte[] frame;
        try {
            frame = MessageCodec.encode(message);
        } catch (IOException e) {
            System.err.println("Could not encode message: " + e.getMessage());
            return 0;
        }
        int sent = 0;
        for (String hop : forwarder.nextHops(message, fromNeighbour, neighbourIds())) {
            NeighbourLink neighbour = neighbours.get(hop);
            if (neighbour == null) {
                continue;
            }
            try {
                neighbour.link().send(frame);
                sent++;
            } catch (IOException e) {
                System.err.println("Send to " + hop + " failed: " + e.getMessage());
                neighbour.link().close(); // its reader sees EOF and removes the neighbour
            }
        }
        return sent;
    }

    @Override
    public void close() {
        running = false;
        for (NeighbourLink neighbour : neighbours.values()) {
            neighbour.link().close();
        }
        transport.close();
        forwarder.close();
    }
}
