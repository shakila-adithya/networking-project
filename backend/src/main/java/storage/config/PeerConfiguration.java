package storage.config;

import storage.cluster.ReplicationManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validated external configuration for one LAN peer process. */
public record PeerConfiguration(String localPeerId, String bindAddress,
                                String advertisedAddress, int port,
                                String storageDirectory, List<PeerDefinition> peers,
                                String clusterId, boolean discoveryEnabled,
                                String discoveryGroup, int discoveryPort,
                                String discoverySecret) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public static final String DEFAULT_CLUSTER_ID = "default-cluster";
    public static final String DEFAULT_DISCOVERY_GROUP = "239.255.42.99";
    public static final int DEFAULT_DISCOVERY_PORT = 45555;
    public static final int MAX_DISCOVERY_SECRET_LENGTH = 256;

    public PeerConfiguration {
        localPeerId = requirePeerId(localPeerId, "localPeerId");
        bindAddress = requireAddress(bindAddress, "bindAddress");
        advertisedAddress = requireAddress(advertisedAddress, "advertisedAddress");
        if (isWildcard(advertisedAddress)) {
            throw new IllegalArgumentException("advertisedAddress must be a reachable address, not a wildcard");
        }
        validatePort(port, "port");
        if (storageDirectory == null || storageDirectory.isBlank()) {
            throw new IllegalArgumentException("storageDirectory cannot be blank");
        }
        clusterId = clusterId == null || clusterId.isBlank() ? DEFAULT_CLUSTER_ID : clusterId;
        if (!clusterId.matches("[A-Za-z0-9_.-]{1,80}")) {
            throw new IllegalArgumentException("clusterId must contain 1-80 letters, numbers, '.', '_' or '-'");
        }
        discoveryGroup = discoveryGroup == null || discoveryGroup.isBlank()
                ? DEFAULT_DISCOVERY_GROUP : discoveryGroup;
        if (isWildcard(discoveryGroup) || !isMulticastAddress(discoveryGroup)) {
            throw new IllegalArgumentException("discoveryGroup must be a multicast address");
        }
        if (discoveryPort == 0) discoveryPort = DEFAULT_DISCOVERY_PORT;
        validatePort(discoveryPort, "discoveryPort");
        if (discoveryEnabled) {
            if (discoverySecret == null || discoverySecret.length() < 16
                    || discoverySecret.length() > MAX_DISCOVERY_SECRET_LENGTH) {
                throw new IllegalArgumentException("discoverySecret must contain 16-256 characters when discovery is enabled");
            }
        }
        peers = List.copyOf(Objects.requireNonNull(peers, "peers"));
        if (!discoveryEnabled && peers.size() < ReplicationManager.REPLICATION_FACTOR) {
            throw new IllegalArgumentException("at least " + ReplicationManager.REPLICATION_FACTOR
                    + " peers are required for replication factor " + ReplicationManager.REPLICATION_FACTOR);
        }
        Set<String> ids = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        PeerDefinition local = null;
        for (PeerDefinition peer : peers) {
            if (!ids.add(peer.id())) throw new IllegalArgumentException("duplicate peer ID: " + peer.id());
            String endpoint = endpointKey(peer.address(), peer.port());
            if (!endpoints.add(endpoint)) throw new IllegalArgumentException("duplicate peer endpoint: " + endpoint);
            if (peer.id().equals(localPeerId)) {
                if (local != null) throw new IllegalArgumentException("local peer appears more than once");
                local = peer;
            }
        }
        if (local == null) throw new IllegalArgumentException("localPeerId is not present in peers");
        if (!sameAddress(local.address(), advertisedAddress) || local.port() != port) {
            throw new IllegalArgumentException("local peer endpoint must match advertisedAddress and port");
        }
    }

    public PeerConfiguration(String localPeerId, String bindAddress, String advertisedAddress,
                             int port, String storageDirectory, List<PeerDefinition> peers) {
        this(localPeerId, bindAddress, advertisedAddress, port, storageDirectory, peers,
                DEFAULT_CLUSTER_ID, false, DEFAULT_DISCOVERY_GROUP, DEFAULT_DISCOVERY_PORT, null);
    }

    public static PeerConfiguration load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) throw new IOException("configuration file does not exist: " + path);
        try {
            return MAPPER.readValue(Files.readAllBytes(path.toAbsolutePath().normalize()), PeerConfiguration.class);
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof IOException io && !(e.getCause() instanceof IllegalArgumentException)) throw io;
            throw new IllegalArgumentException("invalid peer configuration: " + e.getMessage(), e);
        }
    }

    public static PeerConfiguration localhost(String localPeerId, String bindAddress, int port) {
        List<PeerDefinition> peers = List.of(
                new PeerDefinition("A", "127.0.0.1", 5000),
                new PeerDefinition("B", "127.0.0.1", 5001),
                new PeerDefinition("C", "127.0.0.1", 5002));
        PeerDefinition local = peers.stream().filter(peer -> peer.id().equals(localPeerId)).findFirst()
                .orElse(new PeerDefinition(localPeerId, "127.0.0.1", port));
        if (!local.id().equals(localPeerId) || (local.port() != port && peers.stream().anyMatch(p -> p.id().equals(localPeerId)))) {
            throw new IllegalArgumentException("legacy localhost mode expects A/B/C with ports 5000/5001/5002");
        }
        return new PeerConfiguration(localPeerId, bindAddress, local.address(), local.port(),
                Path.of("storage-data", localPeerId).toString(), peers,
                DEFAULT_CLUSTER_ID, false, DEFAULT_DISCOVERY_GROUP, DEFAULT_DISCOVERY_PORT, null);
    }

    public List<ReplicationManager.PeerEndpoint> peerEndpoints() {
        return peers.stream().map(peer -> new ReplicationManager.PeerEndpoint(
                peer.id(), peer.address(), peer.port())).toList();
    }

    public Path storagePath() {
        return Path.of(storageDirectory);
    }

    private static String requirePeerId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException(field + " must contain 1-40 letters, numbers, '_' or '-'");
        }
        return value;
    }

    private static String requireAddress(String value, String field) {
        if (value == null || value.isBlank() || value.contains(" ")) {
            throw new IllegalArgumentException(field + " must be a nonblank host or IP address");
        }
        return value;
    }

    private static void validatePort(int value, String field) {
        if (value < 1 || value > 65_535) throw new IllegalArgumentException(field + " must be between 1 and 65535");
    }

    private static String endpointKey(String address, int port) {
        return address.toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static boolean sameAddress(String left, String right) {
        return left.equalsIgnoreCase(right);
    }

    private static boolean isWildcard(String address) {
        if (address.equals("0.0.0.0") || address.equals("::") || address.equals("0:0:0:0:0:0:0:0")) return true;
        try {
            return InetAddress.getByName(address).isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static boolean isWildcardForDiscovery(String address) {
        return isWildcard(address);
    }

    private static boolean isMulticastAddress(String address) {
        try {
            return InetAddress.getByName(address).isMulticastAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public record PeerDefinition(String id, String address, int port) {
        public PeerDefinition {
            id = requirePeerId(id, "peer ID");
            address = requireAddress(address, "peer address");
            if (isWildcard(address)) throw new IllegalArgumentException("peer address cannot be a wildcard: " + address);
            validatePort(port, "peer port");
        }
    }
}
