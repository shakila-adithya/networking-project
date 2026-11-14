package storage.link;

import storage.discovery.Candidate;
import storage.messaging.MeshNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Grows and repairs the neighbour graph. Discovery code offers {@link Candidate}s; a periodic task
 * ({@link TopologyTask}) decides which to connect to:
 * <ol>
 *   <li>Candidates not known to be reachable are treated as bridges to another part of the mesh and
 *       may use any free slot up to {@code maxLinks}.</li>
 *   <li>Candidates already reachable through other neighbours are redundancy links, added only while
 *       the device has fewer than {@code targetLinks}; this keeps one slot free for bridging.</li>
 *   <li>Weak candidates are skipped unless the device has fewer than {@code minLinks}.</li>
 *   <li>A lost neighbour wakes the task so a replacement is attempted immediately. Failing addresses
 *       back off exponentially.</li>
 * </ol>
 * "Reachable" comes from the router's valid routes, so a device that was never contacted looks like a
 * bridge and may cause one extra link; the reserved slot bounds that cost.
 */
public final class TopologyController implements AutoCloseable {
    private static final long PERMANENT = Long.MAX_VALUE;

    /** What the controller currently knows about one candidate. */
    public record CandidateStatus(Candidate candidate, boolean connected, int failures, String lastError) { }

    private static final class Entry {
        Candidate candidate;
        long expiresAt;
        int failures;
        long nextAttemptAt;
        long pendingUntil;
        String lastError = "";

        Entry(Candidate candidate, long expiresAt) {
            this.candidate = candidate;
            this.expiresAt = expiresAt;
        }
    }

    private final MeshNode node;
    private final TopologyPolicy policy;
    private final Supplier<Set<String>> reachable;
    private final LongSupplier clock;
    private final Map<String, Entry> candidates = new HashMap<>();
    private final Object wakeLock = new Object();
    private boolean wakeRequested;
    private volatile boolean running;
    private volatile boolean closed;
    private Thread worker;

    public TopologyController(MeshNode node, TopologyPolicy policy, Supplier<Set<String>> reachable) {
        this(node, policy, reachable, System::currentTimeMillis);
    }

    TopologyController(MeshNode node, TopologyPolicy policy, Supplier<Set<String>> reachable, LongSupplier clock) {
        this.node = node;
        this.policy = policy;
        this.reachable = reachable;
        this.clock = clock;
        node.onNeighbourChange(id -> wake(), id -> wake());
    }

    /** Reports a device seen nearby. It stays a candidate until the TTL passes without being re-offered. */
    public synchronized void offer(Candidate candidate) {
        add(candidate, clock.getAsLong() + policy.candidateTtl().toMillis());
    }

    /** Reports a device that should always be considered (for example a configured peer). */
    public synchronized void offerPermanent(Candidate candidate) {
        add(candidate, PERMANENT);
    }

    private void add(Candidate candidate, long expiresAt) {
        if (candidate.deviceId().equals(node.localId())) {
            return;
        }
        Entry entry = candidates.get(candidate.deviceId());
        if (entry == null) {
            candidates.put(candidate.deviceId(), new Entry(candidate, expiresAt));
        } else {
            if (!entry.candidate.address().equals(candidate.address())) {
                entry.failures = 0;
                entry.nextAttemptAt = 0;
            }
            entry.candidate = candidate;
            entry.expiresAt = Math.max(entry.expiresAt == PERMANENT ? PERMANENT : expiresAt, entry.expiresAt);
        }
        wake();
    }

    /**
     * Connects to a known candidate immediately (the UI's Connect button). Success and failure are recorded like
     * automatic dials, so a failure shows its reason and the periodic task backs off instead of retrying at once.
     */
    public void connectNow(String deviceId) throws IOException {
        Candidate candidate;
        synchronized (this) {
            Entry entry = candidates.get(deviceId);
            if (entry == null) {
                throw new IOException("unknown device " + deviceId + ": scan again to find it");
            }
            candidate = entry.candidate;
        }
        if (node.neighbourIds().contains(deviceId)) {
            return; // already connected
        }
        try {
            node.connectTo(candidate.address());
            recordSuccess(candidate);
        } catch (IOException | RuntimeException e) {
            recordFailure(candidate, String.valueOf(e.getMessage()));
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public synchronized List<CandidateStatus> status() {
        Set<String> neighbours = node.neighbourIds();
        List<CandidateStatus> result = new ArrayList<>();
        for (Entry entry : candidates.values()) {
            result.add(new CandidateStatus(entry.candidate, neighbours.contains(entry.candidate.deviceId()),
                    entry.failures, entry.lastError));
        }
        result.sort(Comparator.comparing(item -> item.candidate().deviceId()));
        return result;
    }

    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("already started");
        }
        running = true;
        worker = new Thread(new TopologyTask(this), "topology-" + node.localId());
        worker.setDaemon(true);
        worker.start();
    }

    boolean isRunning() {
        return running;
    }

    /** Asks the task to evaluate again now (a neighbour changed or a candidate was offered). */
    public void wake() {
        synchronized (wakeLock) {
            wakeRequested = true;
            wakeLock.notifyAll();
        }
    }

    /** Blocks until woken or the interval elapses. Called by the task thread. */
    void awaitNextTick() throws InterruptedException {
        synchronized (wakeLock) {
            if (!wakeRequested) {
                wakeLock.wait(policy.interval().toMillis());
            }
            wakeRequested = false;
        }
    }

    /** One evaluation: dial the candidates the policy selects. */
    void tick() {
        List<Candidate> chosen;
        synchronized (this) {
            chosen = plan(node.neighbourIds(), reachable.get());
        }
        for (Candidate candidate : chosen) {
            if (closed) {
                return;
            }
            try {
                node.connectTo(candidate.address());
                recordSuccess(candidate);
            } catch (IOException | RuntimeException e) {
                recordFailure(candidate, String.valueOf(e.getMessage()));
            }
        }
    }

    private synchronized void recordSuccess(Candidate candidate) {
        Entry entry = candidates.get(candidate.deviceId());
        if (entry != null) {
            entry.lastError = "";
            // The link registers asynchronously after HELLO. Until then it counts toward the link cap
            // and is not dialled again.
            long until = clock.getAsLong() + policy.retryBase().toMillis();
            entry.pendingUntil = until;
            entry.nextAttemptAt = until;
        }
    }

    private synchronized void recordFailure(Candidate candidate, String error) {
        Entry entry = candidates.get(candidate.deviceId());
        if (entry == null) {
            return;
        }
        entry.failures++;
        entry.lastError = error;
        long delay = policy.retryBase().toMillis() << Math.min(entry.failures - 1, 20);
        entry.nextAttemptAt = clock.getAsLong() + Math.min(delay, policy.retryMax().toMillis());
    }

    /**
     * Pure decision step (caller holds the lock): which candidates to connect to now.
     *
     * @param neighbours ids of current neighbours
     * @param reachableIds ids known to be reachable through the mesh (may include neighbours)
     */
    synchronized List<Candidate> plan(Set<String> neighbours, Set<String> reachableIds) {
        long now = clock.getAsLong();
        candidates.values().removeIf(entry -> entry.expiresAt != PERMANENT && entry.expiresAt < now);
        Set<String> known = new HashSet<>(reachableIds);
        known.addAll(neighbours);

        int inFlight = 0;
        for (Entry entry : candidates.values()) {
            if (entry.pendingUntil > now && !neighbours.contains(entry.candidate.deviceId())) {
                inFlight++;
            }
        }
        List<Candidate> bridges = new ArrayList<>();
        List<Candidate> redundant = new ArrayList<>();
        for (Entry entry : candidates.values()) {
            String id = entry.candidate.deviceId();
            if (neighbours.contains(id)) {
                entry.failures = 0;
                entry.lastError = "";
                continue;
            }
            if (entry.nextAttemptAt > now) {
                continue;
            }
            (known.contains(id) ? redundant : bridges).add(entry.candidate);
        }
        Comparator<Candidate> strongestFirst = Comparator.comparingInt(Candidate::signal).reversed()
                .thenComparing(Candidate::deviceId);
        bridges.sort(strongestFirst);
        redundant.sort(strongestFirst);

        int degree = neighbours.size() + inFlight;
        int free = policy.maxLinks() - degree;
        List<Candidate> chosen = new ArrayList<>();
        for (Candidate candidate : bridges) {
            if (chosen.size() < free && strongEnough(candidate, degree + chosen.size())) {
                chosen.add(candidate);
            }
        }
        for (Candidate candidate : redundant) {
            int projected = degree + chosen.size();
            if (projected < policy.targetLinks() && chosen.size() < free && strongEnough(candidate, projected)) {
                chosen.add(candidate);
            }
        }
        return chosen;
    }

    private boolean strongEnough(Candidate candidate, int projectedDegree) {
        return candidate.signal() >= policy.minSignal() || projectedDegree < policy.minLinks();
    }

    @Override
    public void close() {
        closed = true;
        running = false;
        wake();
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
