package storage.cluster;

import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.link.HeartbeatManager;
import storage.transport.PeerClient;
import storage.transport.Protocol;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Phase 5C: detects recovered replicas and reports safe placement decisions. */
public final class ReconciliationManager implements AutoCloseable {
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(10);

    private final String localPeerName;
    private final FileStore fileStore;
    private final ReplicationManager replicationManager;
    private final HeartbeatManager heartbeatManager;
    private final SelfHealingManager selfHealingManager;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ReconciliationView> views = new ConcurrentHashMap<>();
    private final List<ReconciliationOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> lastLoggedSignatures = new ConcurrentHashMap<>();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final Consumer<HeartbeatManager.PeerTransition> transitionListener;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ReconciliationManager(String localPeerName, FileStore fileStore,
                                 ReplicationManager replicationManager,
                                 HeartbeatManager heartbeatManager,
                                 SelfHealingManager selfHealingManager) {
        this(localPeerName, fileStore, replicationManager, heartbeatManager,
                selfHealingManager, DEFAULT_INTERVAL);
    }

    ReconciliationManager(String localPeerName, FileStore fileStore,
                          ReplicationManager replicationManager,
                          HeartbeatManager heartbeatManager,
                          SelfHealingManager selfHealingManager, Duration interval) {
        this.localPeerName = Objects.requireNonNull(localPeerName);
        this.fileStore = Objects.requireNonNull(fileStore);
        this.replicationManager = Objects.requireNonNull(replicationManager);
        this.heartbeatManager = Objects.requireNonNull(heartbeatManager);
        this.selfHealingManager = Objects.requireNonNull(selfHealingManager);
        this.interval = Objects.requireNonNull(interval);
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        this.executor = Executors.newScheduledThreadPool(2, runnable ->
                Thread.ofPlatform().daemon().name("reconciliation-worker-" + localPeerName).unstarted(runnable));
        this.transitionListener = this::onHeartbeatTransition;
        heartbeatManager.addTransitionListener(transitionListener);
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("reconciliation manager is closed");
        executor.scheduleWithFixedDelay(this::queueScan, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Deterministic test/CLI hook; recovery-triggered scans remain asynchronous. */
    public void checkNow() {
        runScan();
    }

    private void onHeartbeatTransition(HeartbeatManager.PeerTransition transition) {
        if (transition.previousState() == HeartbeatManager.PeerState.DEAD
                && transition.currentState() == HeartbeatManager.PeerState.ALIVE) {
            System.out.printf("Peer recovery detected: %s%n", transition.peer().name());
            outcome(null, "RECOVERY_DETECTED", transition.peer().name());
            queueScan();
        }
    }

    private void queueScan() {
        if (closed.get() || !scanQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { runScan(); }
                finally { scanQueued.set(false); }
            });
        } catch (RuntimeException e) {
            scanQueued.set(false);
        }
    }

    private void runScan() {
        if (closed.get()) return;
        try {
            Snapshot snapshot = snapshot();
            snapshot.files().forEach((id, copies) -> {
                if (active.add(id)) executor.execute(() -> reconcile(id, copies, snapshot));
            });
        } catch (Exception e) {
            System.err.println("Reconciliation inventory scan failed: " + Protocol.safeMessage(e.getMessage()));
        }
    }

    private Snapshot snapshot() throws IOException {
        Map<String, InventoryState> inventories = new LinkedHashMap<>();
        try {
            inventories.put(localPeerName, InventoryState.available(localPeerName, fileStore.inventory()));
        } catch (IOException e) {
            inventories.put(localPeerName, InventoryState.unavailable(localPeerName, e.getMessage()));
        }
        for (HeartbeatManager.PeerStatus status : heartbeatManager.statuses()) {
            if (status.state() != HeartbeatManager.PeerState.ALIVE) {
                inventories.put(status.peer().name(), InventoryState.unavailable(status.peer().name(), status.state().name()));
                continue;
            }
            try {
                inventories.put(status.peer().name(), InventoryState.available(status.peer().name(),
                        PeerClient.listFiles(status.peer().host(), status.peer().port())));
            } catch (IOException e) {
                inventories.put(status.peer().name(), InventoryState.unavailable(status.peer().name(), e.getMessage()));
            }
        }
        Map<UUID, Map<String, FileMetadata>> files = new LinkedHashMap<>();
        inventories.values().stream().filter(InventoryState::available).forEach(inventory ->
                inventory.files().forEach(metadata -> files.computeIfAbsent(metadata.fileId(), ignored -> new LinkedHashMap<>())
                        .put(inventory.peerName(), metadata)));
        return new Snapshot(inventories, files);
    }

    private void reconcile(UUID fileId, Map<String, FileMetadata> initialCopies, Snapshot initialSnapshot) {
        try {
            if (selfHealingManager.isRepairActive(fileId)) {
                outcome(fileId, "DEFERRED", "automatic repair is active");
                return;
            }
            Map<String, FileMetadata> copies = initialCopies;
            List<String> conflicts = conflictingPeers(copies);
            if (!conflicts.isEmpty()) {
                ReconciliationView view = new ReconciliationView(fileId, "CONFLICT", List.of(), List.of(),
                        conflicts, unavailable(initialSnapshot), Instant.now());
                record(fileId, view);
                outcome(fileId, "DEFERRED", "conflicting metadata on " + conflicts, signature(view));
                return;
            }
            FileMetadata metadata = copies.values().iterator().next();
            List<String> verified = copies.keySet().stream().sorted()
                    .filter(peer -> verify(peer, metadata)).toList();
            if (metadata.recipients() != null) {
                boolean complete = verified.containsAll(metadata.recipients());
                var view = new ReconciliationView(fileId, complete ? "SHARE_COMPLETE" : "SHARE_INCOMPLETE",
                        metadata.recipients(), List.of(), List.of(), unavailable(initialSnapshot), Instant.now());
                record(fileId, view);
                outcome(fileId, view.state(), "Explicit recipients; no automatic redistribution", signature(view));
                return;
            }
            if (verified.size() < ReplicationManager.REPLICATION_FACTOR) {
                ReconciliationView view = new ReconciliationView(fileId, "DEFERRED", verified, List.of(),
                        List.of(), unavailable(initialSnapshot), Instant.now());
                record(fileId, view);
                outcome(fileId, "DEFERRED", "fewer than two content-verified copies", signature(view));
                return;
            }
            List<String> preferred = verified.subList(0, ReplicationManager.REPLICATION_FACTOR);
            List<String> extras = verified.subList(ReplicationManager.REPLICATION_FACTOR, verified.size());
            Snapshot finalSnapshot = snapshot();
            if (!stillAvailable(finalSnapshot, fileId, metadata, preferred)) {
                ReconciliationView view = new ReconciliationView(fileId, "DEFERRED", verified, List.of(),
                        List.of(), unavailable(finalSnapshot), Instant.now());
                record(fileId, view);
                outcome(fileId, "DEFERRED", "peer became unavailable during reconciliation", signature(view));
                return;
            }
            String state = extras.isEmpty() ? "STABLE" : "EXTRA_CANDIDATE";
            ReconciliationView view = new ReconciliationView(fileId, state, preferred, extras,
                    List.of(), unavailable(finalSnapshot), Instant.now());
            record(fileId, view);
            outcome(fileId, state, extras.isEmpty() ? "two verified preferred copies" :
                    "cleanup candidates=" + extras + " (not deleted)", signature(view));
        } catch (Exception e) {
            outcome(fileId, "DEFERRED", Protocol.safeMessage(e.getMessage()));
        } finally {
            active.remove(fileId);
        }
    }

    private boolean verify(String peerName, FileMetadata metadata) {
        try {
            if (peerName.equals(localPeerName)) return fileStore.verify(metadata.fileId(), metadata);
            ReplicationManager.PeerEndpoint peer = endpoint(peerName);
            return PeerClient.verifyFile(peer.host(), peer.port(), metadata);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean stillAvailable(Snapshot snapshot, UUID fileId, FileMetadata metadata, List<String> preferred) {
        return preferred.stream().allMatch(peer -> {
            InventoryState state = snapshot.inventories().get(peer);
            return state != null && state.available() && state.files().stream()
                    .anyMatch(candidate -> candidate.fileId().equals(fileId) && candidate.equals(metadata));
        });
    }

    private List<String> conflictingPeers(Map<String, FileMetadata> copies) {
        if (copies.isEmpty()) return List.of();
        FileMetadata first = copies.values().iterator().next();
        return copies.entrySet().stream().filter(entry -> !first.equals(entry.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private List<String> unavailable(Snapshot snapshot) {
        return snapshot.inventories().values().stream().filter(inventory -> !inventory.available())
                .map(InventoryState::peerName).sorted().toList();
    }

    private ReplicationManager.PeerEndpoint endpoint(String name) {
        return replicationManager.peers().stream().filter(peer -> peer.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown peer " + name));
    }

    private void record(UUID fileId, ReconciliationView view) { views.put(fileId, view); }

    void outcome(UUID fileId, String state, String message) {
        outcome(fileId, state, message, state + "|" + message);
    }

    void outcome(UUID fileId, String state, String message, String signature) {
        if (fileId != null) {
            AtomicBoolean changed = new AtomicBoolean();
            lastLoggedSignatures.compute(fileId, (ignored, previous) -> {
                if (!signature.equals(previous)) {
                    changed.set(true);
                    return signature;
                }
                return previous;
            });
            if (!changed.get()) return;
        }
        outcomes.add(new ReconciliationOutcome(fileId, state, message, Instant.now()));
        while (outcomes.size() > 100) outcomes.remove(0);
        System.out.printf("Reconciliation %s: %s (%s)%n", state.toLowerCase(), fileId, message);
    }

    private static String signature(ReconciliationView view) {
        return view.state() + "|preferred=" + view.preferredReplicas()
                + "|extra=" + view.extraCandidates()
                + "|conflicts=" + view.metadataConflicts()
                + "|unavailable=" + view.unavailablePeers();
    }

    public ReconciliationStatus status() {
        return new ReconciliationStatus(views.values().stream()
                        .sorted(Comparator.comparing(ReconciliationView::fileId)).toList(),
                List.copyOf(active), List.copyOf(outcomes));
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            heartbeatManager.removeTransitionListener(transitionListener);
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private record Snapshot(Map<String, InventoryState> inventories,
                            Map<UUID, Map<String, FileMetadata>> files) { }
    private record InventoryState(String peerName, boolean available,
                                  List<FileMetadata> files, String reason) {
        static InventoryState available(String peer, List<FileMetadata> files) {
            return new InventoryState(peer, true, List.copyOf(files), null);
        }
        static InventoryState unavailable(String peer, String reason) {
            return new InventoryState(peer, false, List.of(), reason);
        }
    }
    public record ReconciliationView(UUID fileId, String state, List<String> preferredReplicas,
                                     List<String> extraCandidates, List<String> metadataConflicts,
                                     List<String> unavailablePeers, Instant updatedAt) { }
    public record ReconciliationOutcome(UUID fileId, String state, String message, Instant at) { }
    public record ReconciliationStatus(List<ReconciliationView> files, List<UUID> activeReconciliations,
                                       List<ReconciliationOutcome> recentOutcomes) { }
}
