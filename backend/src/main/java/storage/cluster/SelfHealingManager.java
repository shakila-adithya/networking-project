package storage.cluster;

import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.files.TransferResult;
import storage.link.HeartbeatManager;
import storage.transport.PeerClient;
import storage.transport.Protocol;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Phase 5B: asynchronous, bounded self-healing for missing replicas. */
public final class SelfHealingManager implements AutoCloseable {
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(2);

    private final String localPeerName;
    private final FileStore fileStore;
    private final ReplicationManager replicationManager;
    private final HeartbeatManager heartbeatManager;
    private final Duration interval;
    private final int maxRetries;
    private final Duration backoff;
    private final ScheduledExecutorService executor;
    private final Set<UUID> activeRepairs = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RepairView> views = new ConcurrentHashMap<>();
    private final List<RepairOutcome> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<UUID, String> lastLoggedSignatures = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SelfHealingManager(String localPeerName, FileStore fileStore,
                              ReplicationManager replicationManager,
                              HeartbeatManager heartbeatManager) {
        this(localPeerName, fileStore, replicationManager, heartbeatManager,
                DEFAULT_INTERVAL, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF);
    }

    SelfHealingManager(String localPeerName, FileStore fileStore,
                       ReplicationManager replicationManager, HeartbeatManager heartbeatManager,
                       Duration interval, int maxRetries, Duration backoff) {
        this.localPeerName = Objects.requireNonNull(localPeerName);
        this.fileStore = Objects.requireNonNull(fileStore);
        this.replicationManager = Objects.requireNonNull(replicationManager);
        this.heartbeatManager = Objects.requireNonNull(heartbeatManager);
        this.interval = Objects.requireNonNull(interval);
        this.backoff = Objects.requireNonNull(backoff);
        if (interval.isNegative() || interval.isZero() || maxRetries < 1
                || backoff.isNegative() || backoff.isZero()) {
            throw new IllegalArgumentException("repair timing and retry values must be positive");
        }
        this.maxRetries = maxRetries;
        this.executor = Executors.newScheduledThreadPool(2, runnable ->
                Thread.ofPlatform().daemon().name("repair-worker-" + localPeerName).unstarted(runnable));
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("self-healing manager is closed");
        executor.scheduleWithFixedDelay(this::safeCheck, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Synchronous discovery hook useful for deterministic tests and the CLI. */
    public void checkNow() {
        safeCheck();
    }

    private void safeCheck() {
        if (closed.get()) return;
        try {
            Snapshot snapshot = snapshot();
            snapshot.files().forEach((id, copies) -> inspect(id, copies, snapshot));
        } catch (Exception e) {
            System.err.println("Repair inventory check failed: " + Protocol.safeMessage(e.getMessage()));
        }
    }

    private Snapshot snapshot() throws IOException {
        Map<String, List<FileMetadata>> inventories = new LinkedHashMap<>();
        inventories.put(localPeerName, fileStore.inventory());
        for (HeartbeatManager.PeerStatus status : heartbeatManager.statuses()) {
            if (status.state() != HeartbeatManager.PeerState.ALIVE) continue;
            try {
                inventories.put(status.peer().name(),
                        PeerClient.listFiles(status.peer().host(), status.peer().port()));
            } catch (IOException e) {
                // An inventory failure is intentionally treated as unavailable, never as empty.
            }
        }
        Map<UUID, Map<String, FileMetadata>> files = new LinkedHashMap<>();
        inventories.forEach((peer, list) -> list.forEach(metadata -> {
            // Inventory proves that the metadata and file size are structurally usable;
            // placement decisions additionally require content-integrity verification.
            if (contentVerified(peer, metadata)) {
                files.computeIfAbsent(metadata.fileId(), ignored -> new LinkedHashMap<>())
                        .put(peer, metadata);
            }
        }));
        return new Snapshot(inventories, files);
    }

    private boolean contentVerified(String peerName, FileMetadata metadata) {
        try {
            if (localPeerName.equals(peerName)) {
                return fileStore.verify(metadata.fileId(), metadata);
            }
            ReplicationManager.PeerEndpoint peer = endpointFor(peerName);
            return PeerClient.verifyFile(peer.host(), peer.port(), metadata);
        } catch (Exception e) {
            return false;
        }
    }

    private void inspect(UUID fileId, Map<String, FileMetadata> copies, Snapshot snapshot) {
        FileMetadata canonical = copies.values().iterator().next();
        // Explicit shares have fixed recipients and must never be repaired onto other peers.
        if (canonical.recipients() != null) { views.remove(fileId); return; }
        if (copies.values().stream().anyMatch(metadata -> !canonical.equals(metadata))) {
            defer(fileId, "conflicting metadata");
            return;
        }
        if (copies.size() >= ReplicationManager.REPLICATION_FACTOR) {
            views.remove(fileId);
            return;
        }
        List<String> reachable = new ArrayList<>(snapshot.inventories().keySet());
        if (reachable.size() < ReplicationManager.REPLICATION_FACTOR) {
            defer(fileId, "fewer than two reachable peers");
            return;
        }
        views.put(fileId, new RepairView(fileId, canonical, copies.keySet().stream().sorted().toList(),
                "UNDER_REPLICATED", Instant.now()));
        System.out.printf("Repair detected: %s copies=%s%n", fileId, copies.keySet());

        String owner = copies.keySet().stream().min(String::compareTo).orElse(localPeerName);
        if (!localPeerName.equals(owner) || !activeRepairs.add(fileId)) {
            if (!localPeerName.equals(owner)) logOutcome(fileId, "DEFERRED", "owned by " + owner);
            return;
        }
        try {
            executor.execute(() -> repair(fileId, canonical));
        } catch (RejectedExecutionException e) {
            activeRepairs.remove(fileId);
            logOutcome(fileId, "CANCELLED", "repair executor rejected job: " + Protocol.safeMessage(e.getMessage()));
        }
    }

    private void repair(UUID fileId, FileMetadata expected) {
        logOutcome(fileId, "STARTED", "repair owner=" + localPeerName);
        try {
            for (int attempt = 1; attempt <= maxRetries && !closed.get(); attempt++) {
                try {
                    Snapshot before = snapshot();
                    Map<String, FileMetadata> copies = before.files().get(fileId);
                    if (copies == null || copies.size() >= ReplicationManager.REPLICATION_FACTOR) {
                        logOutcome(fileId, "DEFERRED", "placement changed before transfer");
                        return;
                    }
                    FileMetadata canonical = copies.values().iterator().next();
                    if (!expected.equals(canonical)
                            || copies.values().stream().anyMatch(metadata -> !canonical.equals(metadata))) {
                        logOutcome(fileId, "FAILED", "metadata conflict or changed during repair");
                        return;
                    }
                    ReplicationManager.PeerEndpoint source = endpointFor(copies.keySet().stream()
                            .sorted().findFirst().orElseThrow());
                    ReplicationManager.PeerEndpoint destination = replicationManager.peers().stream()
                            .filter(peer -> !copies.containsKey(peer.name()))
                            .filter(peer -> peer.name().equals(localPeerName)
                                    || isAlive(peer.name(), before.inventories().keySet()))
                            .findFirst().orElse(null);
                    if (destination == null) {
                        logOutcome(fileId, "DEFERRED", "no healthy destination");
                        return;
                    }
                    TransferResult result = PeerClient.replicateFromPeer(source, destination, canonical);
                    if (!result.success()) {
                        if ("DUPLICATE_FILE_ID".equals(result.code())) {
                            logOutcome(fileId, "DEFERRED", "destination already contains UUID");
                            return;
                        }
                        throw new IOException(result.code() + ": " + result.message());
                    }
                    List<FileMetadata> destinationInventory =
                            PeerClient.listFiles(destination.host(), destination.port());
                    FileMetadata stored = destinationInventory.stream()
                            .filter(metadata -> metadata.fileId().equals(fileId)).findFirst().orElse(null);
                    if (!canonical.equals(stored)) throw new IOException("destination inventory verification failed");
                    Snapshot after = snapshot();
                    Map<String, FileMetadata> finalCopies = after.files().get(fileId);
                    if (finalCopies == null || finalCopies.size() < ReplicationManager.REPLICATION_FACTOR
                            || finalCopies.values().stream().anyMatch(metadata -> !canonical.equals(metadata))) {
                        throw new IOException("placement changed during repair");
                    }
                    logOutcome(fileId, "SUCCEEDED", "replica verified on " + destination.name());
                    return;
                } catch (Exception e) {
                    String message = "attempt " + attempt + "/" + maxRetries + ": "
                            + Protocol.safeMessage(e.getMessage());
                    logOutcome(fileId, "FAILED", message);
                    System.err.printf("Repair attempt failed: %s (%s)%n", fileId, message);
                    if (attempt < maxRetries) {
                        try { Thread.sleep(backoff.multipliedBy(1L << (attempt - 1)).toMillis()); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
        } finally {
            activeRepairs.remove(fileId);
        }
    }

    private boolean isAlive(String name, Set<String> reachable) {
        return reachable.contains(name);
    }

    private ReplicationManager.PeerEndpoint endpointFor(String name) {
        return replicationManager.peers().stream().filter(peer -> peer.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown peer " + name));
    }

    private void defer(UUID fileId, String reason) {
        views.put(fileId, new RepairView(fileId, null, List.of(), "DEFERRED: " + reason, Instant.now()));
        logOutcome(fileId, "DEFERRED", reason);
    }

    private void logOutcome(UUID fileId, String state, String message) {
        if (fileId != null) {
            AtomicBoolean changed = new AtomicBoolean();
            lastLoggedSignatures.compute(fileId, (ignored, previous) -> {
                String signature = state + "|" + message;
                if (!signature.equals(previous)) {
                    changed.set(true);
                    return signature;
                }
                return previous;
            });
            if (!changed.get()) return;
        }
        RepairOutcome outcome = new RepairOutcome(fileId, state, message, Instant.now());
        outcomes.add(outcome);
        while (outcomes.size() > 100) outcomes.remove(0);
        if ("STARTED".equals(state) || "SUCCEEDED".equals(state) || "DEFERRED".equals(state)) {
            System.out.printf("Repair %s: %s (%s)%n", state.toLowerCase(), fileId, message);
        }
    }

    public RepairStatus status() {
        return new RepairStatus(views.values().stream().sorted(Comparator.comparing(RepairView::fileId)).toList(),
                List.copyOf(activeRepairs), List.copyOf(outcomes));
    }

    public boolean isRepairActive(UUID fileId) {
        return activeRepairs.contains(fileId);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            activeRepairs.clear();
        }
    }

    private record Snapshot(Map<String, List<FileMetadata>> inventories,
                           Map<UUID, Map<String, FileMetadata>> files) { }
    public record RepairView(UUID fileId, FileMetadata metadata, List<String> copies,
                             String state, Instant updatedAt) { }
    public record RepairOutcome(UUID fileId, String state, String message, Instant at) { }
    public record RepairStatus(List<RepairView> underReplicated, List<UUID> activeRepairs,
                               List<RepairOutcome> recentOutcomes) { }
}
