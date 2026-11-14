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
    // One logical neighbour may have independently authenticated carriers. Healthy fallbacks stay open.
    private final Map<String, Map<String, NeighbourLink>> carriers = new HashMap<>();
    private final Object carrierLock = new Object();
    private final Map<Link, Double> roundTrips = new ConcurrentHashMap<>();
    private final Map<Link, Double> reliability = new ConcurrentHashMap<>();
    private final Map<Link, Double> throughput = new ConcurrentHashMap<>();
    private final Map<String, Integer> measuredSignal = new ConcurrentHashMap<>();
    public void signalHint(String peer, String address, int signal) { measuredSignal.put(peer + "/" + (address.startsWith("bt:") ? "bluetooth" : "tcp"), signal); }
    private final Map<Link, Long> sentBytes = new ConcurrentHashMap<>();
    private final Map<MessageType, Consumer<Message>> handlers = new ConcurrentHashMap<>();
    private final List<Consumer<String>> upListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> downListeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private final Map<String, Boolean> custodyPeers = new ConcurrentHashMap<>();
    public Set<String> custodyNeighbourIds() { Set<String> ids = neighbourIds(); ids.removeIf(id -> !Boolean.TRUE.equals(custodyPeers.get(id))); return ids; }
    private storage.custody.CustodyService custody;
    private storage.routing.MeshTopology topologyView;
    private String storageUnavailable = "Secure persistent forwarding is not configured.";
    public void attachTopology(storage.routing.MeshTopology topology) { topologyView = topology; }
    public void attachCustody(storage.custody.CustodyService service) { custody = service; }
    public void storageUnavailable(String reason) { storageUnavailable = reason; }
    public boolean durable() { return custody != null; }
    public storage.custody.CustodyService custody() { return custody; }
    public Map<String, Object> custodyStatus() { return custody == null ? Map.of("available", false, "error", storageUnavailable) : custody.status(); }
    public java.util.List<Map<String, Object>> topologyStatus() { return topologyView == null ? java.util.List.of() : topologyView.snapshot(); }
    public boolean deliverStored(Message message) {
        Consumer<Message> handler = handlers.get(message.type());
        if (handler == null) return false;
        try { handler.accept(message); return true; }
        catch (RuntimeException e) { System.err.println("Stored delivery paused: " + e.getMessage()); return false; }
    }
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

    private record NeighbourLink(Link link, String initiator, java.util.concurrent.atomic.AtomicLong lastHeard) {
        NeighbourLink(Link link, String initiator) { this(link, initiator, new java.util.concurrent.atomic.AtomicLong(System.nanoTime())); }
    }
    private final long healthIntervalMillis;
    private final long silenceTimeoutNanos;
    private final java.util.concurrent.ScheduledExecutorService healthTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r ->
            Thread.ofPlatform().daemon().name("mesh-link-health").unstarted(r));
    private final java.util.concurrent.ExecutorService healthWriters = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Link> healthPending = ConcurrentHashMap.newKeySet();

    public MeshNode(String localId, Transport transport, Forwarder forwarder) {
        this(localId, transport, forwarder, java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(12));
    }
    public MeshNode(String localId, Transport transport, Forwarder forwarder,
                    java.time.Duration healthInterval, java.time.Duration silenceTimeout) {
        if (healthInterval.toMillis() < 1 || silenceTimeout.compareTo(healthInterval.multipliedBy(2)) <= 0)
            throw new IllegalArgumentException("Link silence timeout must exceed two health intervals");
        healthIntervalMillis = healthInterval.toMillis();
        silenceTimeoutNanos = silenceTimeout.toNanos();
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
        transport.start(link -> launchReader(link, false, null));
        announcePresence();
        healthTimer.scheduleWithFixedDelay(new LinkHealthTask(this), healthIntervalMillis, healthIntervalMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS);
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
        launchReader(transport.connect(address), true, null);
    }

    /** Discovery IDs are claims: require the authenticated HELLO to match before reporting a dial successful. */
    public void connectTo(String address, String expectedDeviceId) throws IOException {
        Link link = transport.connect(address);
        var outcome = launchReader(link, true, expectedDeviceId);
        try { outcome.get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); link.close(); throw new IOException("Connection interrupted", e); }
        catch (java.util.concurrent.TimeoutException e) { link.close(); throw new IOException("Peer did not complete HELLO; update Relay on both devices", e); }
        catch (java.util.concurrent.ExecutionException e) {
            link.close(); throw new IOException("Peer identity handshake failed: " + e.getCause().getMessage(), e.getCause());
        }
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
            kinds.put(entry.getKey(), entry.getValue().link().kind());
        }
        return kinds;
    }

    /** Actual connected carrier addresses; a different candidate for the peer is not an active link. */
    public Map<String, String> neighbourAddresses() {
        Map<String, String> addresses = new HashMap<>();
        neighbours.forEach((id, neighbour) -> addresses.put(id, neighbour.link().remoteAddress()));
        return addresses;
    }

    public Set<String> neighbourIds() {
        return new HashSet<>(neighbours.keySet());
    }

    /** Sends a new message from this device. Returns the number of neighbours it was handed to. */
    public int originate(Message message) {
        if (custody != null && storage.custody.CustodyService.handles(message)) return custody.originate(message);
        seen.firstSeen(message.id());
        return sendToHops(message, null);
    }

    private java.util.concurrent.CompletableFuture<String> launchReader(Link link, boolean initiatedLocally, String expectedId) {
        var outcome = new java.util.concurrent.CompletableFuture<String>();
        java.util.concurrent.ScheduledFuture<?> deadline;
        try { deadline = healthTimer.schedule(link::close, 10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (RuntimeException e) { link.close(); throw e; }
        try {
            Thread worker = new Thread(new LinkReader(this, link, initiatedLocally, expectedId, outcome, deadline),
                    "mesh-link-" + localId + "-" + link.remoteAddress());
            worker.setDaemon(true);
            worker.start();
            return outcome;
        } catch (RuntimeException | Error failure) {
            deadline.cancel(false);
            link.close();
            throw failure;
        }
    }

    // ---- called by LinkReader on its own thread ----

    void sendHello(Link link) throws IOException {
        link.send(MessageCodec.encode(Message.create(localId, Message.BROADCAST, MessageType.HELLO,
                helloPayload())));
    }

    private static int carrierCost(String kind) {
        return switch (kind) { case "tcp", "lan", "wifi-direct", "hotspot" -> 100; case "bluetooth" -> 300; default -> 200; };
    }
    private int measuredCost(String peer, NeighbourLink n) {
        int score = carrierCost(n.link().kind());
        score += (int)Math.min(150, roundTrips.getOrDefault(n.link(), 0.0) / 5);
        score += (int)(120 * (1 - reliability.getOrDefault(n.link(), 1.0)));
        Double rate = throughput.get(n.link());
        if (rate != null) score += (int)(80 / (1 + rate / 1_000_000));
        Integer signal = measuredSignal.get(peer + "/" + n.link().kind());
        if (signal != null) score += (100 - signal) / 2;
        var power = storage.platform.PowerState.current();
        if (power.percent() != null && power.percent() < 20 && !Boolean.TRUE.equals(power.pluggedIn())) score += n.link().kind().equals("bluetooth") ? 40 : 20;
        return score;
    }
    public int routeCost(String peer) {
        NeighbourLink link = neighbours.get(peer);
        return link == null ? 1000 : measuredCost(peer, link);
    }
    public boolean hasCarrier(String peer, String kind) {
        synchronized (carrierLock) { return carriers.getOrDefault(peer, Map.of()).values().stream()
                .anyMatch(n -> n.link().isOpen() && (kind.equals(n.link().kind())
                        || kind.equals("tcp") && Set.of("lan", "wifi-direct", "hotspot").contains(n.link().kind()))); }
    }
    public List<Map<String, Object>> carrierStatus() {
        synchronized (carrierLock) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            carriers.forEach((peer, links) -> links.values().forEach(n -> {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("peerId", peer); item.put("kind", n.link().kind()); item.put("address", n.link().remoteAddress());
                item.put("active", neighbours.get(peer) == n); item.put("open", n.link().isOpen());
                item.put("rttMillis", roundTrips.get(n.link())); item.put("bytesSent", sentBytes.getOrDefault(n.link(), 0L));
                item.put("sendRateEstimateBytesPerSecond", throughput.get(n.link())); item.put("reliability", reliability.get(n.link()));
                item.put("signal", measuredSignal.get(peer + "/" + n.link().kind())); item.put("cost", measuredCost(peer, n));
                item.put("battery", storage.platform.PowerState.current());
                result.add(item);
            }));
            return result;
        }
    }
    private byte[] helloPayload() {
        byte[] name = encodeName(displayName);
        byte[] hello = new byte[name.length + 6];
        System.arraycopy(new byte[]{82,76,89,51,0}, 0, hello, 0, 5); hello[5] = (byte)(custody == null ? 0 : 1); System.arraycopy(name, 0, hello, 6, name.length); return hello;
    }
    String helloName(String peer, byte[] payload) throws IOException {
        boolean current = payload.length >= 6 && (payload[5] == 0 || payload[5] == 1) && payload[0] == 82 && payload[1] == 76 && payload[2] == 89 && payload[3] == 51 && payload[4] == 0;
        if (!current && custody != null) throw new IOException("This peer needs an update. Install the current Relay version on both devices for durable forwarding.");
        custodyPeers.put(peer, current && payload[5] == 1);
        return decodeName(current ? Arrays.copyOfRange(payload, 6, payload.length) : payload);
    }
    /** Retain healthy carriers of different transports; tie-break duplicates of the same transport only. */
    boolean register(String remoteId, Link link, boolean initiatedLocally, String remoteName) {
        if (!running || remoteId.equals(localId)) return false;
        String initiator = initiatedLocally ? localId : remoteId;
        Link replaced = null;
        boolean first;
        synchronized (carrierLock) {
            Map<String, NeighbourLink> links = carriers.get(remoteId);
            if (links == null) {
                if (carriers.size() >= 64) return false;
                links = new HashMap<>(); carriers.put(remoteId, links);
            }
            NeighbourLink existing = links.get(link.kind());
            if (existing != null && existing.link().isOpen()) {
                if (initiator.compareTo(existing.initiator()) >= 0) return false;
                replaced = existing.link();
            }
            if (existing == null && links.size() >= 4) return false;
            first = !neighbours.containsKey(remoteId);
            links.put(link.kind(), new NeighbourLink(link, initiator));
            selectCarrier(remoteId, links);
        }
        if (replaced != null) replaced.close();
        learnName(remoteId, remoteName);
        if (first) {
            upListeners.forEach(listener -> listener.accept(remoteId));
            forwarder.neighbourChanged(remoteId, true);
            syncNamesTo(remoteId); announcePresence();
        }
        return true;
    }
    private final Map<String, String> improvement = new HashMap<>();
    private final Map<String, Long> improvementSince = new HashMap<>();
    private void selectCarrier(String peer, Map<String, NeighbourLink> links) {
        NeighbourLink best = links.values().stream().filter(n -> n.link().isOpen())
                .min(java.util.Comparator.comparingInt(n -> measuredCost(peer, n))).orElse(null);
        NeighbourLink current = neighbours.get(peer);
        if (current == null || !current.link().isOpen()) { if (best == null) neighbours.remove(peer); else neighbours.put(peer, best); improvement.remove(peer); improvementSince.remove(peer); return; }
        if (best != current && best != null && measuredCost(peer, best) <= .8 * measuredCost(peer, current)) {
            String key = best.link().kind();
            if (!key.equals(improvement.get(peer))) { improvement.put(peer, key); improvementSince.put(peer, System.nanoTime()); }
            if (System.nanoTime() - improvementSince.get(peer) >= java.time.Duration.ofSeconds(10).toNanos()) neighbours.put(peer, best);
        } else { improvement.remove(peer); improvementSince.remove(peer); }
    }
    void unregister(String remoteId, Link link) {
        boolean gone;
        synchronized (carrierLock) {
            Map<String, NeighbourLink> links = carriers.get(remoteId);
            if (links == null) return;
            NeighbourLink record = links.get(link.kind());
            if (record == null || record.link() != link) return;
            links.remove(link.kind()); roundTrips.remove(link); sentBytes.remove(link); reliability.remove(link); throughput.remove(link);
            selectCarrier(remoteId, links);
            gone = !neighbours.containsKey(remoteId);
            if (links.isEmpty()) carriers.remove(remoteId);
        }
        if (gone) {
            custodyPeers.remove(remoteId);
            downListeners.forEach(listener -> listener.accept(remoteId));
            forwarder.neighbourChanged(remoteId, false);
        }
    }
    void receive(Message message, String fromNeighbour, Link receivedOn) {
        synchronized (carrierLock) {
            carriers.getOrDefault(fromNeighbour, Map.of()).values().stream().filter(n -> n.link() == receivedOn)
                    .forEach(n -> n.lastHeard().set(System.nanoTime()));
        }
        if (message.type() == MessageType.LINK_PING || message.type() == MessageType.LINK_PONG) {
            if (!message.source().equals(fromNeighbour) || !message.destination().equals(localId) || message.ttl() != 1) return;
            if (message.type() == MessageType.LINK_PING) {
                writeCarrier(receivedOn, new Message(UUID.randomUUID(), localId, fromNeighbour, MessageType.LINK_PONG,
                        1, System.currentTimeMillis(), message.payload()));
            } else if (message.payload().length == 8) {
                long started = java.nio.ByteBuffer.wrap(message.payload()).getLong();
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                if (elapsed >= 0 && elapsed < 60000) roundTrips.compute(receivedOn, (k, old) -> old == null ? elapsed : old * .8 + elapsed * .2);
            }
            return;
        }
        receive(message, fromNeighbour);
    }
    boolean writeCarrier(Link link, Message message) {
        try {
            byte[] bytes = MessageCodec.encode(message); long started = System.nanoTime(); link.send(bytes);
            sentBytes.merge(link, (long)bytes.length, Long::sum);
            reliability.compute(link, (k, old) -> old == null ? 1 : .98 * old + .02);
            if (bytes.length >= 1024) {
                double rate = bytes.length * 1_000_000_000.0 / Math.max(1, System.nanoTime() - started);
                throughput.compute(link, (k, old) -> old == null ? rate : old * .8 + rate * .2);
            }
            return true;
        } catch (IOException e) { reliability.compute(link, (k, old) -> old == null ? 0 : old * .5); link.close(); return false; }
    }
    void receive(Message message, String fromNeighbour) {
        NeighbourLink sender = neighbours.get(fromNeighbour);
        if (sender != null) sender.lastHeard().set(System.nanoTime());
        if (message.type() == MessageType.LINK_PING || message.type() == MessageType.LINK_PONG) {
            if (message.type() == MessageType.LINK_PING && message.source().equals(fromNeighbour)
                    && message.destination().equals(localId) && message.ttl() == 1) {
                sendTo(fromNeighbour, new Message(UUID.randomUUID(), localId, fromNeighbour, MessageType.LINK_PONG,
                        1, System.currentTimeMillis(), new byte[0]));
            }
            return; // link health frames never leave their direct link
        }
        if (message.type() == MessageType.HELLO) {
            return;
        }
        if (message.type() == MessageType.CUSTODY_DATA || message.type() == MessageType.CUSTODY_ACK) {
            if (custody != null) custody.receive(message, fromNeighbour);
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
        if (writeCarrier(neighbour.link(), message)) return true;
        unregister(neighbourId, neighbour.link());
        NeighbourLink fallback = neighbours.get(neighbourId);
        return fallback != null && writeCarrier(fallback.link(), message);
    }

    private int sendToHops(Message message, String fromNeighbour) {
        int sent = 0;
        for (String hop : forwarder.nextHops(message, fromNeighbour, neighbourIds())) if (sendTo(hop, message)) sent++;
        return sent;
    }

    /** Closes silent carriers even when TCP has not yet reported a lost radio/network. */
    void checkLinkHealth() {
        if (!running) return;
        long now = System.nanoTime();
        List<Map.Entry<String, NeighbourLink>> snapshot = new java.util.ArrayList<>();
        synchronized (carrierLock) { carriers.forEach((id, links) -> { selectCarrier(id, links); links.values().forEach(n -> snapshot.add(Map.entry(id, n))); }); }
        snapshot.forEach(item -> {
            String id = item.getKey(); NeighbourLink neighbour = item.getValue();
            if (now - neighbour.lastHeard().get() > silenceTimeoutNanos) {
                neighbour.link().close(); unregister(id, neighbour.link());
            } else if (healthPending.add(neighbour.link())) {
                try { healthWriters.submit(new LinkPingTask(this, id, neighbour.link(), healthPending)); }
                catch (java.util.concurrent.RejectedExecutionException shutdown) { healthPending.remove(neighbour.link()); }
            }
        });
    }

    @Override
    public void close() {
        running = false;
        if (custody != null) custody.close();
        if (topologyView != null) topologyView.close();
        healthTimer.shutdownNow();
        healthWriters.shutdownNow();
        synchronized (carrierLock) {
            carriers.values().forEach(links -> links.values().forEach(n -> n.link().close()));
            carriers.clear(); neighbours.clear();
        }
        transport.close();
        forwarder.close();
    }
}
