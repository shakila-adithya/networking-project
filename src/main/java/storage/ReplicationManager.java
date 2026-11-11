package storage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ReplicationManager {
    public static final int REPLICATION_FACTOR = 2;

    private static final List<PeerEndpoint> INITIAL_PEERS = List.of(
            new PeerEndpoint("A", "127.0.0.1", 5000),
            new PeerEndpoint("B", "127.0.0.1", 5001),
            new PeerEndpoint("C", "127.0.0.1", 5002));

    private final String localPeerName;
    private final Supplier<List<PeerEndpoint>> peerSupplier;

    public ReplicationManager(String localPeerName, List<PeerEndpoint> peers) {
        this(localPeerName, () -> peers);
    }

    public ReplicationManager(String localPeerName, Supplier<List<PeerEndpoint>> peerSupplier) {
        this.localPeerName = Objects.requireNonNull(localPeerName, "localPeerName");
        this.peerSupplier = Objects.requireNonNull(peerSupplier, "peerSupplier");
        List<PeerEndpoint> peers = List.copyOf(Objects.requireNonNull(peerSupplier.get(), "peers"));
        long matchingPeers = peers.stream()
                .filter(peer -> peer.name().equals(localPeerName))
                .count();
        if (matchingPeers != 1) {
            throw new IllegalArgumentException("configuration must contain the local peer exactly once");
        }
    }

    public static ReplicationManager initialConfiguration(String localPeerName) {
        return new ReplicationManager(localPeerName, INITIAL_PEERS);
    }

    public static List<PeerEndpoint> initialPeers() {
        return INITIAL_PEERS;
    }

    public Optional<PeerEndpoint> replicaTarget() {
        List<PeerEndpoint> peers = peers();
        if (peers.size() < REPLICATION_FACTOR) {
            return Optional.empty();
        }
        for (int index = 0; index < peers.size(); index++) {
            if (peers.get(index).name().equals(localPeerName)) {
                for (int offset = 1; offset < peers.size(); offset++) {
                    PeerEndpoint candidate = peers.get((index + offset) % peers.size());
                    if (!candidate.name().equals(localPeerName)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    public String localPeerName() {
        return localPeerName;
    }

    public List<PeerEndpoint> peers() {
        List<PeerEndpoint> current = List.copyOf(Objects.requireNonNull(peerSupplier.get(), "peers"));
        long matchingPeers = current.stream().filter(peer -> peer.name().equals(localPeerName)).count();
        if (matchingPeers != 1) throw new IllegalStateException("dynamic peer registry lost the local peer");
        return current;
    }

    public ReplicationResult replicate(FileStore.StoredFile storedFile) {
        Optional<PeerEndpoint> target = replicaTarget();
        if (target.isEmpty()) {
            return ReplicationResult.failure("NO_REPLICA_TARGET",
                    "no different peer is configured for replication", null);
        }

        PeerEndpoint endpoint = target.get();
        try {
            TransferResult transfer = PeerClient.uploadReplica(
                    endpoint.host(), endpoint.port(), storedFile.metadata(), storedFile.contentPath());
            if (!transfer.success()) {
                return ReplicationResult.failure(transfer.code(), transfer.message(), endpoint);
            }
            return ReplicationResult.success(endpoint);
        } catch (IOException e) {
            return ReplicationResult.failure("REPLICA_UNAVAILABLE", e.getMessage(), endpoint);
        }
    }

    public record PeerEndpoint(String name, String host, int port) {
        public PeerEndpoint {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(host, "host");
            if (name.isBlank()) {
                throw new IllegalArgumentException("peer name cannot be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("peer port must be between 1 and 65535");
            }
        }
    }

    public record ReplicationResult(boolean success, String code, String message, PeerEndpoint target) {
        public static ReplicationResult success(PeerEndpoint target) {
            return new ReplicationResult(true, "OK", "replicated to " + target.name(), target);
        }

        public static ReplicationResult failure(String code, String message, PeerEndpoint target) {
            String safeMessage = message == null || message.isBlank() ? "replication failed" : message;
            return new ReplicationResult(false, code, safeMessage, target);
        }
    }
}
