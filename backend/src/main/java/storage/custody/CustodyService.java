package storage.custody;

import storage.messaging.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Durable hop-by-hop custody, separate from application delivery acknowledgements. */
public final class CustodyService implements AutoCloseable {
    public static final long DEFAULT_QUOTA = 1_000_000_000L;
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);
    private final MeshNode node;
    private final EncryptedSpool spool;
    private final Function<String, String> nextHop;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mesh-custody"); t.setDaemon(true); return t;
    });
    private record Index(String destination, boolean origin, long expiresAt, MessageType type, String source) { }
    private record Bundle(UUID id, Message message, boolean origin, long expiresAt, List<String> visited) { }
    private final Map<UUID, Index> index = new LinkedHashMap<>();
    private final Map<UUID, String> offeredTo = new HashMap<>();
    private final Map<UUID, Long> nextAttempt = new HashMap<>();
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean wakeQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String error = "";
    private long retentionMillis;
    private long expired;
    public CustodyService(MeshNode node, EncryptedSpool spool, Duration retention, Function<String, String> nextHop) throws IOException {
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("Retention must be positive");
        this.node = node; this.spool = spool; this.retentionMillis = retention.toMillis(); this.nextHop = nextHop;
        for (UUID id : spool.ids()) {
            try { remember(decode(spool.get(id))); }
            catch (IOException bad) { error = "Some stored data failed integrity checks. It was kept for diagnosis."; }
        }
        node.attachCustody(this);
        node.onNeighbourChange(peer -> {
            synchronized (this) { nextAttempt.clear(); }
            if (wakeQueued.compareAndSet(false, true)) {
                try { timer.execute(new WakeTask()); } catch (RejectedExecutionException stopping) { wakeQueued.set(false); }
            }
        }, peer -> { synchronized (this) { offeredTo.forEach((id, holder) -> { if (holder.equals(peer)) nextAttempt.remove(id); }); } });
        timer.scheduleWithFixedDelay(new ForwardTask(), 100, 500, TimeUnit.MILLISECONDS);
    }
    public static boolean handles(Message message) {
        return !message.isBroadcast() && switch (message.type()) {
            case CHAT, ACK, FILE_OFFER, FILE_ACCEPT, FILE_CHUNK, FILE_CHUNK_ACK, FILE_DONE -> true;
            default -> false;
        };
    }
    private static boolean original(Message message) { return Set.of(MessageType.CHAT, MessageType.FILE_OFFER, MessageType.FILE_CHUNK).contains(message.type()); }
    private static UUID id(Message m) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(m.source().getBytes(java.nio.charset.StandardCharsets.UTF_8)); hash.update((byte)0);
            hash.update(m.destination().getBytes(java.nio.charset.StandardCharsets.UTF_8)); hash.update((byte)m.type().ordinal()); hash.update(m.payload());
            ByteBuffer b = ByteBuffer.wrap(hash.digest()); return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public synchronized int originate(Message message) {
        if (closed) return 0;
        UUID id = id(message);
        if (index.containsKey(id)) return 1;
        Bundle bundle = new Bundle(id, message, original(message), original(message) ? Long.MAX_VALUE : System.currentTimeMillis() + retentionMillis, List.of(node.localId()));
        try { persist(bundle); return 1; }
        catch (IOException e) { error = e.getMessage(); throw new IllegalStateException("Pending data could not be saved: " + e.getMessage(), e); }
    }
    private void remember(Bundle bundle) { index.put(bundle.id(), new Index(bundle.message().destination(), bundle.origin(), bundle.expiresAt(), bundle.message().type(), bundle.message().source())); }
    private void persist(Bundle bundle) throws IOException { spool.put(bundle.id(), encode(bundle)); remember(bundle); }
    public synchronized void receive(Message outer, String from) {
        if (closed || !outer.source().equals(from) || !outer.destination().equals(node.localId()) || outer.ttl() != 1) return;
        try {
            if (outer.type() == MessageType.CUSTODY_ACK) {
                if (outer.payload().length != 16) return;
                ByteBuffer b = ByteBuffer.wrap(outer.payload()); UUID id = new UUID(b.getLong(), b.getLong());
                if (!from.equals(offeredTo.get(id))) return;
                Index entry = index.get(id);
                if (entry == null) return;
                if (entry.origin()) nextAttempt.put(id, System.currentTimeMillis() + 60000);
                else remove(id);
                return;
            }
            Bundle wire = decode(outer.payload());
            if (!handles(wire.message()) || !id(wire.message()).equals(wire.id()) || wire.visited().contains(node.localId())
                    || wire.visited().isEmpty() || !wire.visited().getLast().equals(from)
                    || wire.visited().size() > Message.DEFAULT_TTL || wire.visited().size() == Message.DEFAULT_TTL && !wire.message().destination().equals(node.localId()) || wire.expiresAt() <= System.currentTimeMillis()) return;
            if (!index.containsKey(wire.id())) {
                List<String> visited = new ArrayList<>(wire.visited()); visited.add(node.localId());
                persist(new Bundle(wire.id(), wire.message(), false, Math.min(wire.expiresAt(), System.currentTimeMillis() + retentionMillis), visited));
            }
            // Only a forced, atomically committed record earns a custody ACK.
            node.sendTo(from, new Message(UUID.randomUUID(), node.localId(), from, MessageType.CUSTODY_ACK, 1,
                    System.currentTimeMillis(), ByteBuffer.allocate(16).putLong(wire.id().getMostSignificantBits()).putLong(wire.id().getLeastSignificantBits()).array()));
        } catch (IOException | RuntimeException e) { error = "Custody admission failed: " + e.getMessage(); }
    }
    private final class ForwardTask implements Runnable { @Override public void run() { try { forward(); } catch (RuntimeException e) { error = "Forwarding paused: " + e.getMessage(); } } }
    private final class WakeTask implements Runnable {
        public void run() { try { new ForwardTask().run(); } finally { wakeQueued.set(false); } }
    }
    public void forward() {
        List<Map.Entry<UUID, Index>> work;
        synchronized (this) {
            if (closed) return;
            work = new ArrayList<>(index.entrySet());
            work.sort(Comparator.comparingInt(e -> switch (e.getValue().type()) { case ACK, FILE_ACCEPT, FILE_CHUNK_ACK, FILE_DONE -> 0; case CHAT -> 1; default -> 2; }));
        }
        int budget = 16;
        for (var item : work) {
            UUID id = item.getKey(); Bundle bundle; String peer = null; Message sending = null; boolean local;
            try {
                synchronized (this) {
                    if (closed) return;
                    Index entry = index.get(id); if (entry == null) continue;
                    long now = System.currentTimeMillis();
                    if (!entry.origin() && entry.expiresAt() <= now) { remove(id); expired++; error = "Temporary relay data expired; the sender retains its original."; continue; }
                    if (nextAttempt.getOrDefault(id, 0L) > now) continue;
                    local = entry.destination().equals(node.localId());
                    Set<String> peers = node.custodyNeighbourIds();
                    if (!local && peers.isEmpty()) continue;
                    bundle = decode(spool.get(id));
                    if (local) nextAttempt.put(id, now + 5000);
                    else {
                        if (bundle.visited().size() > Message.DEFAULT_TTL) { error = "Stored data reached its hop limit; awaiting sender retry."; continue; }
                        String preferred = peers.contains(entry.destination()) ? entry.destination() : nextHop.apply(entry.destination());
                        peer = preferred != null && peers.contains(preferred) && !bundle.visited().contains(preferred) ? preferred
                                : peers.stream().filter(p -> !bundle.visited().contains(p)).min(Comparator.comparingInt(node::routeCost).thenComparing(p -> p)).orElse(null);
                        if (peer == null) continue;
                        Bundle forwarded = entry.origin() ? new Bundle(id, bundle.message(), false, now + retentionMillis, List.of(node.localId())) : bundle;
                        offeredTo.put(id, peer); nextAttempt.put(id, now + 10000);
                        sending = new Message(UUID.randomUUID(), node.localId(), peer, MessageType.CUSTODY_DATA, 1, now, encode(forwarded));
                    }
                }
                if (budget-- <= 0) break;
                // Neither application handlers nor blocking carrier writes run under the custody monitor.
                if (local) { if (node.deliverStored(bundle.message())) synchronized (this) { remove(id); } }
                else node.sendTo(peer, sending);
            } catch (IOException e) { error = "Stored data unavailable: " + e.getMessage(); }
        }
    }
    private void remove(UUID id) throws IOException { spool.remove(id); index.remove(id); offeredTo.remove(id); nextAttempt.remove(id); }
    public synchronized void completed(MessageType type, String recipient, byte[] payload) {
        try { remove(id(new Message(UUID.randomUUID(), node.localId(), recipient, type, 8, 0, payload))); }
        catch (IOException e) { error = "Could not remove delivered temporary data: " + e.getMessage(); }
    }
    public synchronized void completedTransfer(UUID transfer, String recipient) {
        for (UUID id : new ArrayList<>(index.keySet())) {
            Index entry = index.get(id);
            if (!entry.origin() || !entry.destination().equals(recipient) || entry.type() == MessageType.CHAT) continue;
            try {
                byte[] payload = decode(spool.get(id)).message().payload();
                if (payload.length >= 16) {
                    ByteBuffer b = ByteBuffer.wrap(payload);
                    if (new UUID(b.getLong(), b.getLong()).equals(transfer)) remove(id);
                }
            } catch (IOException e) { error = e.getMessage(); }
        }
    }
    public synchronized List<Message> pendingChats() throws IOException {
        List<Message> result = new ArrayList<>();
        for (var entry : index.entrySet()) if (entry.getValue().origin() && entry.getValue().type() == MessageType.CHAT) result.add(decode(spool.get(entry.getKey())).message());
        return result;
    }
    public synchronized Map<String, Object> status() {
        long used; try { used = spool.used(); } catch (IOException e) { used = -1; }
        Map<String, Long> byType = new TreeMap<>(); index.values().forEach(e -> byType.merge(e.type().name(), 1L, Long::sum));
        return Map.of("byType", byType, "available", true, "pending", index.size(), "usedBytes", used, "quotaBytes", spool.quota(), "retentionHours", retentionMillis / 3600000, "expired", expired, "error", error);
    }
    public synchronized void configure(long quotaBytes, long retentionHours) throws IOException {
        if (retentionHours < 1 || retentionHours > 168) throw new IllegalArgumentException("Choose retention between 1 and 168 hours");
        spool.quota(quotaBytes); retentionMillis = Duration.ofHours(retentionHours).toMillis();
    }
    private static byte[] encode(Bundle bundle) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeLong(bundle.id().getMostSignificantBits()); out.writeLong(bundle.id().getLeastSignificantBits());
            out.writeBoolean(bundle.origin()); out.writeLong(bundle.expiresAt()); out.writeInt(bundle.visited().size());
            for (String peer : bundle.visited()) out.writeUTF(peer);
            byte[] message = MessageCodec.encode(bundle.message()); out.writeInt(message.length); out.write(message);
        }
        if (bytes.size() > Message.MAX_PAYLOAD) throw new IOException("Message is too large for a custody envelope");
        return bytes.toByteArray();
    }
    private static Bundle decode(byte[] bytes) throws IOException {
        if (bytes.length > Message.MAX_PAYLOAD) throw new IOException("Custody envelope too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Update Relay to read this custody version");
            UUID id = new UUID(in.readLong(), in.readLong()); boolean origin = in.readBoolean(); long expires = in.readLong();
            int count = in.readInt(); if (count < 1 || count > Message.DEFAULT_TTL + 1) throw new IOException("Invalid custody hop count");
            List<String> visited = new ArrayList<>(); for (int i = 0; i < count; i++) { String peer = in.readUTF(); if (peer.length() > 64) throw new IOException("Invalid peer"); visited.add(peer); }
            int length = in.readInt(); if (length < 0 || length > Message.MAX_PAYLOAD) throw new IOException("Invalid stored frame length");
            byte[] frame = in.readNBytes(length); if (frame.length != length || in.available() != 0) throw new IOException("Truncated stored message");
            return new Bundle(id, MessageCodec.decode(frame), origin, expires, visited);
        }
    }
    @Override public synchronized void close() { closed = true; timer.shutdownNow(); }
}
