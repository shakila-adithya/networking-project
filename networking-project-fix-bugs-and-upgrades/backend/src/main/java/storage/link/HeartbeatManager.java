package storage.link;

import storage.cluster.ReplicationManager;
import storage.transport.PeerClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class HeartbeatManager implements AutoCloseable {
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    private final String localPeerName;
    private final Duration interval;
    private final int failureThreshold;
    private final HeartbeatProbe probe;
    private final Consumer<String> stateChangeLogger;
    private final CopyOnWriteArrayList<Consumer<PeerTransition>> transitionListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, PeerStatus> statuses = new ConcurrentHashMap<>();
    private final Set<String> checksInProgress = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private volatile boolean running;
    private volatile boolean closed;
    private ScheduledFuture<?> scheduledTask;

    public HeartbeatManager(String localPeerName,
                            List<ReplicationManager.PeerEndpoint> configuredPeers) {
        this(localPeerName, configuredPeers, DEFAULT_INTERVAL,
                DEFAULT_FAILURE_THRESHOLD,
                defaultProbe(DEFAULT_CONNECT_TIMEOUT_MILLIS), System.out::println);
    }

    public HeartbeatManager(String localPeerName,
                            List<ReplicationManager.PeerEndpoint> configuredPeers,
                            Duration interval,
                            int connectTimeoutMillis,
                            int failureThreshold) {
        this(localPeerName, configuredPeers, interval, failureThreshold,
                defaultProbe(connectTimeoutMillis), System.out::println);
    }

    public HeartbeatManager(String localPeerName,
                     List<ReplicationManager.PeerEndpoint> configuredPeers,
                     Duration interval,
                     int failureThreshold,
                     HeartbeatProbe probe,
                     Consumer<String> stateChangeLogger) {
        this.localPeerName = Objects.requireNonNull(localPeerName, "localPeerName");
        Objects.requireNonNull(configuredPeers, "configuredPeers");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.stateChangeLogger = Objects.requireNonNull(stateChangeLogger, "stateChangeLogger");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure threshold must be at least 1");
        }
        this.failureThreshold = failureThreshold;

        long localPeerCount = configuredPeers.stream()
                .filter(peer -> peer.name().equals(localPeerName))
                .count();
        if (localPeerCount != 1) {
            throw new IllegalArgumentException("configuration must contain the local peer exactly once");
        }

        configuredPeers.stream()
                .filter(peer -> !peer.name().equals(localPeerName))
                .forEach(peer -> {
                    PeerStatus previous = statuses.putIfAbsent(peer.name(),
                            new PeerStatus(peer, PeerState.ALIVE, 0, null, null));
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate peer name: " + peer.name());
                    }
                });

        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("heartbeat-scheduler-" + localPeerName).unstarted(runnable));
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
    }

    private static HeartbeatProbe defaultProbe(int connectTimeoutMillis) {
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("connection timeout must be positive");
        }
        return peer -> "PONG".equals(PeerClient.ping(
                peer.host(), peer.port(),
                connectTimeoutMillis, connectTimeoutMillis));
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("heartbeat manager is closed");
        }
        if (running) {
            return;
        }
        running = true;
        scheduledTask = scheduler.scheduleAtFixedRate(
                this::scheduleChecks, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void scheduleChecks() {
        if (!running) {
            return;
        }
        statuses.values().forEach(status -> {
            String peerName = status.peer().name();
            if (checksInProgress.add(peerName)) {
                try {
                    workers.submit(() -> {
                        try {
                            checkPeer(status.peer());
                        } finally {
                            checksInProgress.remove(peerName);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    checksInProgress.remove(peerName);
                }
            }
        });
    }

    public void checkNow() {
        statuses.values().stream()
                .map(PeerStatus::peer)
                .toList()
                .forEach(this::checkPeer);
    }

    private void checkPeer(ReplicationManager.PeerEndpoint peer) {
        boolean alive;
        try {
            alive = probe.isAlive(peer);
        } catch (Exception e) {
            alive = false;
        }

        Instant checkedAt = Instant.now();
        AtomicReference<String> logMessage = new AtomicReference<>();
        AtomicReference<PeerTransition> transition = new AtomicReference<>();
        boolean heartbeatSucceeded = alive;
        statuses.computeIfPresent(peer.name(), (name, previous) -> {
            PeerStatus next;
            if (heartbeatSucceeded) {
                next = new PeerStatus(peer, PeerState.ALIVE, 0, checkedAt, checkedAt);
            } else {
                int failures = previous.consecutiveFailures() + 1;
                PeerState state = failures >= failureThreshold ? PeerState.DEAD : PeerState.SUSPECTED;
                next = new PeerStatus(peer, state, failures, checkedAt, previous.lastSuccessfulAt());
            }
            if (next.state() != previous.state()) {
                logMessage.set("Heartbeat " + peer.name() + " " + previous.state()
                        + " -> " + next.state() + " (failures=" + next.consecutiveFailures() + ")");
                transition.set(new PeerTransition(peer, previous.state(), next.state(), checkedAt));
            }
            return next;
        });
        if (logMessage.get() != null) {
            stateChangeLogger.accept(logMessage.get());
        }
        if (transition.get() != null) {
            transitionListeners.forEach(listener -> {
                try { listener.accept(transition.get()); }
                catch (RuntimeException e) { System.err.println("Heartbeat transition listener failed: " + e.getMessage()); }
            });
        }
    }

    public List<PeerStatus> statuses() {
        return statuses.values().stream()
                .sorted((left, right) -> left.peer().name().compareTo(right.peer().name()))
                .toList();
    }

    public void addTransitionListener(Consumer<PeerTransition> listener) {
        transitionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Adds discovered endpoints and refreshes changed addresses without resetting existing peer state unnecessarily. */
    public void refreshPeers(List<ReplicationManager.PeerEndpoint> configuredPeers) {
        Objects.requireNonNull(configuredPeers).stream()
                .filter(peer -> !peer.name().equals(localPeerName))
                .forEach(peer -> statuses.compute(peer.name(), (name, previous) -> {
                    if (previous == null) return new PeerStatus(peer, PeerState.ALIVE, 0, Instant.now(), Instant.now());
                    if (!previous.peer().equals(peer)) {
                        return new PeerStatus(peer, PeerState.ALIVE, 0, Instant.now(), Instant.now());
                    }
                    return previous;
                }));
    }

    public void removeTransitionListener(Consumer<PeerTransition> listener) {
        transitionListeners.remove(listener);
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }
        scheduler.shutdownNow();
        workers.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum PeerState {
        ALIVE,
        SUSPECTED,
        DEAD
    }

    public record PeerStatus(ReplicationManager.PeerEndpoint peer,
                             PeerState state,
                             int consecutiveFailures,
                             Instant lastCheckedAt,
                             Instant lastSuccessfulAt) {
    }

    public record PeerTransition(ReplicationManager.PeerEndpoint peer,
                                 PeerState previousState, PeerState currentState,
                                 Instant at) { }

    @FunctionalInterface
    public interface HeartbeatProbe {
        boolean isAlive(ReplicationManager.PeerEndpoint peer) throws IOException;
    }
}
