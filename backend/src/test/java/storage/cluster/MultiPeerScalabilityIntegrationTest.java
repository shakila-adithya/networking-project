package storage.cluster;

import storage.config.PeerConfiguration;
import storage.discovery.DiscoveryManager;
import storage.link.HeartbeatManager;
import storage.transport.PeerClient;
import storage.transport.PeerServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class MultiPeerScalabilityIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test void fivePeersHandleConcurrentDeliveriesAndPeerRecovery() throws Exception {
        runScaleScenario(5, 10);
    }

    @Test void tenPeersHandleConcurrentDeliveriesAndPeerRecovery() throws Exception {
        runScaleScenario(10, 20);
    }

    private void runScaleScenario(int peerCount, int fileCount) throws Exception {
        List<PeerEndpoint> peers = new ArrayList<>();
        List<PeerServer> servers = new ArrayList<>();
        try {
            for (int i = 0; i < peerCount; i++) {
                PeerEndpoint peer = new PeerEndpoint("P" + i, "127.0.0.1", freePort());
                peers.add(peer);
                PeerServer server = new PeerServer(peer.host(), peer.port(), temporaryDirectory.resolve(peer.name()), peer.name());
                servers.add(server);
                server.start();
            }
            PeerRegistry registry = new PeerRegistry("P0", List.of(peers.getFirst()));
            for (var peer : peers.subList(1, peers.size())) assertTrue(registry.join(peer));
            assertEquals(peerCount, registry.members().size());
            try (var executor = Executors.newFixedThreadPool(4);
                 var heartbeat = new HeartbeatManager("P0", peers, Duration.ofHours(1), 500, 2)) {
                List<java.util.concurrent.Callable<Void>> uploads = new ArrayList<>();
                for (int i = 0; i < fileCount; i++) {
                    Path source = temporaryDirectory.resolve("file-" + i + ".txt");
                    Files.writeString(source, "file content " + i);
                    PeerEndpoint target = peers.get(i % peerCount);
                    uploads.add(() -> {
                        var result = PeerClient.upload(target.host(), target.port(), source);
                        assertTrue(result.success(), result.message());
                        Path download = temporaryDirectory.resolve(result.fileId() + ".txt");
                        assertTrue(PeerClient.download(target.host(), target.port(), result.fileId(), download).success());
                        assertEquals(-1, Files.mismatch(source, download));
                        return null;
                    });
                }
                for (var result : executor.invokeAll(uploads)) result.get();
                for (var server : servers) assertEquals(fileCount / peerCount, server.inventory().size());
                heartbeat.checkNow();
                assertTrue(heartbeat.statuses().stream().allMatch(p -> p.state() == HeartbeatManager.PeerState.ALIVE));
                servers.get(1).close();
                heartbeat.checkNow(); heartbeat.checkNow();
                assertEquals(HeartbeatManager.PeerState.DEAD, heartbeat.statuses().stream()
                        .filter(p -> p.peer().name().equals("P1")).findFirst().orElseThrow().state());
                for (int i = 2; i < servers.size(); i++) assertEquals(fileCount / peerCount, servers.get(i).inventory().size());
                PeerServer recovered = new PeerServer("127.0.0.1", peers.get(1).port(), temporaryDirectory.resolve("P1"), "P1");
                servers.set(1, recovered);
                recovered.start(); heartbeat.checkNow();
                assertTrue(heartbeat.statuses().stream().allMatch(p -> p.state() == HeartbeatManager.PeerState.ALIVE));
                for (var server : servers) assertEquals(fileCount / peerCount, server.inventory().size());
            }
        } finally {
            for (var server : servers) server.close();
        }
    }
    @Test
    void authenticatedMulticastDiscoveryWorksWhenLoopbackSupportsMulticast() throws Exception {
        int discoveryPort = freePort();
        int tcpPortA = freePort();
        int tcpPortB = freePort();
        PeerConfiguration configA = discoveryConfig("A", tcpPortA, discoveryPort,
                temporaryDirectory.resolve("discovery-A"));
        PeerConfiguration configB = discoveryConfig("B", tcpPortB, discoveryPort,
                temporaryDirectory.resolve("discovery-B"));
        PeerRegistry registryA = new PeerRegistry("A", configA.peerEndpoints());
        PeerRegistry registryB = new PeerRegistry("B", configB.peerEndpoints());
        try (DiscoveryManager discoveryA = new DiscoveryManager(configA, registryA, ignored -> { });
             DiscoveryManager discoveryB = new DiscoveryManager(configB, registryB, ignored -> { })) {
            try {
                discoveryA.start();
                discoveryB.start();
            } catch (IOException | RuntimeException e) {
                Assumptions.assumeTrue(false, "multicast unavailable: " + e.getMessage());
            }
            await(() -> registryA.members().stream()
                    .anyMatch(member -> "B".equals(member.endpoint().name())));
            await(() -> registryB.members().stream()
                    .anyMatch(member -> "A".equals(member.endpoint().name())));
            assertTrue(discoveryA.status().accepted() > 0 || discoveryB.status().accepted() > 0);
        }
    }

    private PeerConfiguration discoveryConfig(String id, int tcpPort, int discoveryPort, Path storage) {
        return new PeerConfiguration(id, "127.0.0.1", "127.0.0.1", tcpPort,
                storage.toString(), List.of(new PeerConfiguration.PeerDefinition(
                        id, "127.0.0.1", tcpPort)), "phase7-5-discovery", true,
                "239.255.42.99", discoveryPort, "phase7-5-shared-secret");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

}
