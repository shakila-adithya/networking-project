package storage.cluster;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Thread-safe identity-based membership view. It never removes stored data. */
public final class PeerRegistry {
    private final String localPeerId;
    private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Member>> listeners = new CopyOnWriteArrayList<>();

    public PeerRegistry(String localPeerId, List<ReplicationManager.PeerEndpoint> initialPeers) {
        this.localPeerId = Objects.requireNonNull(localPeerId);
        Objects.requireNonNull(initialPeers).forEach(endpoint -> {
            if (members.putIfAbsent(endpoint.name(), new Member(endpoint, PeerState.ALIVE,
                    Instant.now(), true)) != null) {
                throw new IllegalArgumentException("duplicate peer ID: " + endpoint.name());
            }
        });
        if (!members.containsKey(localPeerId)) throw new IllegalArgumentException("local peer is not registered");
    }

    public boolean join(ReplicationManager.PeerEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        if (endpoint.name().equals(localPeerId)) return false;
        for (Member member : members.values()) {
            if (!member.endpoint().name().equals(endpoint.name())
                    && member.endpoint().host().equalsIgnoreCase(endpoint.host())
                    && member.endpoint().port() == endpoint.port()) {
                return false;
            }
        }
        Member updated = members.compute(endpoint.name(), (id, previous) -> {
            if (previous == null) return new Member(endpoint, PeerState.ALIVE, Instant.now(), false);
            return new Member(endpoint, PeerState.ALIVE, Instant.now(), previous.configured());
        });
        notifyListeners(updated);
        return true;
    }

    public void updateState(String peerId, PeerState state) {
        if (peerId == null || state == null) return;
        members.computeIfPresent(peerId, (id, previous) -> {
            Member updated = new Member(previous.endpoint(), state, Instant.now(), previous.configured());
            notifyListeners(updated);
            return updated;
        });
    }

    public void addListener(Consumer<Member> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public List<ReplicationManager.PeerEndpoint> endpoints() {
        return members.values().stream().map(Member::endpoint)
                .sorted(Comparator.comparing(ReplicationManager.PeerEndpoint::name)).toList();
    }

    public List<Member> members() {
        return members.values().stream().sorted(Comparator.comparing(member -> member.endpoint().name())).toList();
    }

    public String localPeerId() { return localPeerId; }

    private void notifyListeners(Member member) {
        listeners.forEach(listener -> {
            try { listener.accept(member); }
            catch (RuntimeException e) { System.err.println("Peer registry listener failed: " + e.getMessage()); }
        });
    }

    public enum PeerState { ALIVE, SUSPECTED, DEAD }

    public record Member(ReplicationManager.PeerEndpoint endpoint, PeerState state,
                         Instant lastSeen, boolean configured) { }
}
