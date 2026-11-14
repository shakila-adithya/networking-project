package storage.discovery;

import storage.cluster.PeerRegistry;
import storage.cluster.ReplicationManager;
import storage.config.PeerConfiguration;
import storage.link.HeartbeatManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void authenticatedJoinAddsUnknownPeerAndUpdatesItsChangedAddress() throws Exception {
        PeerConfiguration local = config("A", "192.168.70.10", 6200, "shared-discovery-secret");
        PeerRegistry registry = new PeerRegistry("A", local.peerEndpoints());
        try (DiscoveryManager discovery = new DiscoveryManager(local, registry,
                endpoint -> { })) {
            PeerConfiguration joining = config("D", "192.168.70.13", 6203, "shared-discovery-secret");
            byte[] first = DiscoveryManager.encode(DiscoveryManager.createJoin(
                    joining, System.currentTimeMillis(), UUID.randomUUID().toString()));
            assertTrue(discovery.acceptDatagram(first, InetAddress.getByName("192.168.70.13")));
            assertEquals("192.168.70.13", registry.members().stream()
                    .filter(member -> member.endpoint().name().equals("D")).findFirst().orElseThrow().endpoint().host());

            PeerConfiguration moved = config("D", "192.168.70.33", 6203, "shared-discovery-secret");
            byte[] second = DiscoveryManager.encode(DiscoveryManager.createJoin(
                    moved, System.currentTimeMillis(), UUID.randomUUID().toString()));
            assertTrue(discovery.acceptDatagram(second, InetAddress.getByName("192.168.70.33")));
            assertEquals("192.168.70.33", registry.members().stream()
                    .filter(member -> member.endpoint().name().equals("D")).findFirst().orElseThrow().endpoint().host());
        }
    }

    @Test
    void invalidClusterSecretAndMalformedMessagesAreRejected() throws Exception {
        PeerConfiguration local = config("A", "192.168.71.10", 6300, "shared-discovery-secret");
        PeerRegistry registry = new PeerRegistry("A", local.peerEndpoints());
        try (DiscoveryManager discovery = new DiscoveryManager(local, registry, endpoint -> { })) {
            PeerConfiguration wrongSecret = config("D", "192.168.71.13", 6303, "wrong-discovery-secret");
            byte[] unauthorized = DiscoveryManager.encode(DiscoveryManager.createJoin(
                    wrongSecret, System.currentTimeMillis(), UUID.randomUUID().toString()));
            assertFalse(discovery.acceptDatagram(unauthorized, InetAddress.getByName("192.168.71.13")));
            assertFalse(discovery.acceptDatagram("not-json".getBytes(), InetAddress.getByName("192.168.71.13")));
            assertEquals(2, discovery.status().rejected());
            assertEquals(2, registry.members().size());
        }
    }

    @Test
    void dynamicRegistryAcceptsConcurrentJoinsWithoutDuplicateIdentity() {
        PeerConfiguration local = config("A", "192.168.72.10", 6400, "shared-discovery-secret");
        PeerRegistry registry = new PeerRegistry("A", local.peerEndpoints());
        java.util.stream.IntStream.range(0, 32).parallel().forEach(ignored ->
                registry.join(new ReplicationManager.PeerEndpoint("D", "192.168.72.13", 6403)));
        assertEquals(3, registry.members().size());
        assertEquals(1, registry.members().stream().filter(member -> member.endpoint().name().equals("D")).count());
    }

    @Test
    void heartbeatRefreshesDiscoveredEndpoints() {
        List<ReplicationManager.PeerEndpoint> initial = List.of(
                new ReplicationManager.PeerEndpoint("A", "192.168.73.10", 6500),
                new ReplicationManager.PeerEndpoint("B", "192.168.73.11", 6501));
        try (HeartbeatManager heartbeat = new HeartbeatManager("A", initial)) {
            heartbeat.refreshPeers(List.of(
                    new ReplicationManager.PeerEndpoint("A", "192.168.73.10", 6500),
                    new ReplicationManager.PeerEndpoint("B", "192.168.73.33", 6501),
                    new ReplicationManager.PeerEndpoint("C", "192.168.73.13", 6503)));
            assertEquals("192.168.73.33", heartbeat.statuses().stream()
                    .filter(status -> status.peer().name().equals("B")).findFirst().orElseThrow().peer().host());
            assertTrue(heartbeat.statuses().stream().anyMatch(status -> status.peer().name().equals("C")));
        }
    }

    @Test
    void replicationUsesDynamicRegistryForTwoThreeAndFivePeers() {
        List<ReplicationManager.PeerEndpoint> initial = List.of(
                new ReplicationManager.PeerEndpoint("A", "192.168.74.10", 6600),
                new ReplicationManager.PeerEndpoint("B", "192.168.74.11", 6601));
        PeerRegistry registry = new PeerRegistry("A", initial);
        ReplicationManager replication = new ReplicationManager("A", registry::endpoints);
        assertEquals(2, replication.peers().size());
        registry.join(new ReplicationManager.PeerEndpoint("C", "192.168.74.12", 6602));
        assertEquals(3, replication.peers().size());
        registry.join(new ReplicationManager.PeerEndpoint("D", "192.168.74.13", 6603));
        registry.join(new ReplicationManager.PeerEndpoint("E", "192.168.74.14", 6604));
        assertEquals(5, replication.peers().size());
        assertEquals("B", replication.replicaTarget().orElseThrow().name());
    }

    @Test
    void twoSinglePeerConfigsDiscoverEachOtherAndEnableReplication() throws Exception {
        PeerConfiguration a = singlePeerConfig("A", "192.168.75.10", 6800);
        PeerConfiguration b = singlePeerConfig("B", "192.168.75.11", 6801);
        PeerRegistry registryA = new PeerRegistry("A", a.peerEndpoints());
        ReplicationManager replicationA = new ReplicationManager("A", registryA::endpoints);
        try (DiscoveryManager discoveryA = new DiscoveryManager(a, registryA,
                endpoint -> { })) {
            byte[] join = DiscoveryManager.encode(DiscoveryManager.createJoin(
                    b, System.currentTimeMillis(), UUID.randomUUID().toString()));
            assertTrue(discoveryA.acceptDatagram(join, InetAddress.getByName("192.168.75.11")));
            assertEquals(2, registryA.members().size());
            assertEquals("B", replicationA.replicaTarget().orElseThrow().name());
            assertTrue(registryA.members().stream().anyMatch(member ->
                    member.endpoint().name().equals("B") && !member.configured()));
        }
    }

    private static PeerConfiguration config(String id, String address, int port, String secret) {
        java.util.ArrayList<PeerConfiguration.PeerDefinition> peers = new java.util.ArrayList<>();
        if (id.equals("A")) {
            peers.add(new PeerConfiguration.PeerDefinition("A", address, port));
            peers.add(new PeerConfiguration.PeerDefinition("B", "192.168.70.11", 6201));
        } else {
            peers.add(new PeerConfiguration.PeerDefinition("A", "192.168.70.10", 6200));
            peers.add(new PeerConfiguration.PeerDefinition("B", "192.168.70.11", 6201));
        }
        if (!id.equals("A") && !id.equals("B")) peers.add(
                new PeerConfiguration.PeerDefinition(id, address, port));
        return new PeerConfiguration(id, "0.0.0.0", address, port,
                Path.of("data", id).toString(), peers,
                "test-cluster", true, "239.255.42.99", 45555, secret);
    }

    private static PeerConfiguration singlePeerConfig(String id, String address, int port) {
        return new PeerConfiguration(id, "0.0.0.0", address, port, Path.of("data", id).toString(),
                List.of(new PeerConfiguration.PeerDefinition(id, address, port)),
                "single-peer-test", true, "239.255.42.99", 45555, "single-peer-shared-secret");
    }
}
