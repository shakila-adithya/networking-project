package storage.cluster;

import java.util.Objects;

/** Address of a peer's TCP file service, shared by discovery and connection monitoring. */
public record PeerEndpoint(String name, String host, int port) {
    public PeerEndpoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        if (name.isBlank()) throw new IllegalArgumentException("peer name cannot be blank");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("peer port must be between 1 and 65535");
        }
    }
}
