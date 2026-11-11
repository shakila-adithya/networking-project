package storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiPeerScalabilityIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void fivePeerClusterHandlesConcurrentUploadsFailoverAndRecovery() throws Exception {
        runScaleScenario(5, 10);
    }

    @Test
    void tenPeerClusterHandlesConcurrentUploadsFailoverAndRecovery() throws Exception {
        runScaleScenario(10, 20);
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

    private void runScaleScenario(int peerCount, int fileCount) throws Exception {
        List<ReplicationManager.PeerEndpoint> peers = endpoints(peerCount);
        List<Path> stores = new ArrayList<>();
        List<PeerServer> servers = new ArrayList<>();
        PeerRegistry registry = new PeerRegistry("A", List.of(peers.getFirst()));
        for (int index = 1; index < peers.size(); index++) {
            assertTrue(registry.join(peers.get(index)));
        }
        assertEquals(peerCount, registry.members().size());
        assertEquals(peerCount - 1, registry.members().stream()
                .filter(member -> !member.configured()).count());

        ReplicationManager localReplication = new ReplicationManager("A", registry::endpoints);
        ExecutorService uploads = Executors.newFixedThreadPool(Math.min(4, peerCount));
        try {
            for (int index = 0; index < peerCount; index++) {
                Path store = temporaryDirectory.resolve("scale-" + peerCount + "-" + index);
                stores.add(store);
                ReplicationManager manager = index == 0
                        ? localReplication
                        : new ReplicationManager(peers.get(index).name(), peers);
                servers.add(new PeerServer("127.0.0.1", peers.get(index).port(), store, manager));
            }
            servers.forEach(server -> {
                try {
                    server.start();
                } catch (IOException e) {
                    throw new ScaleTestException(e);
                }
            });

            try (HeartbeatManager heartbeat = new HeartbeatManager("A", peers,
                         Duration.ofHours(1), 2_000, 2);
                 SelfHealingManager healing = new SelfHealingManager("A", new FileStore(stores.getFirst()),
                         localReplication, heartbeat, Duration.ofHours(1), 2, Duration.ofMillis(10));
                 ReconciliationManager reconciliation = new ReconciliationManager("A",
                         new FileStore(stores.getFirst()), localReplication, heartbeat, healing,
                         Duration.ofHours(1))) {
                heartbeat.addTransitionListener(transition -> registry.updateState(
                        transition.peer().name(),
                        PeerRegistry.PeerState.valueOf(transition.currentState().name())));
                heartbeat.checkNow();

            Map<UUID, UploadExpectation> expected = uploadConcurrently(
                    peers, sourceFiles(fileCount), uploads);
            long verifiedAtStart = verifyExpectedReplicas(peers, expected);
            assertEquals(fileCount * 2L, verifiedAtStart);

            UUID failoverId = expected.values().stream()
                    .filter(upload -> "A".equals(upload.owner().name()))
                    .findFirst().orElseThrow().fileId();
            UploadExpectation failover = expected.get(failoverId);
            assertEquals("B", failover.target().name());
            reconciliation.checkNow();
            await(() -> viewIs(reconciliation, failoverId, "STABLE",
                    List.of("A", "B"), List.of()));

            Instant interruptionStarted = Instant.now();
            servers.get(1).close();
            heartbeat.checkNow();
            heartbeat.checkNow();
            assertEquals(HeartbeatManager.PeerState.DEAD, status(heartbeat, "B").state());
            healing.checkNow();
            await(() -> verifiedAt(peers.get(2), failover.metadata(), peers));
            assertEquals(2, countVerifiedCopies(peers, failover.metadata()));
            await(() -> !healing.isRepairActive(failoverId));
            reconciliation.checkNow();
            await(() -> viewIs(reconciliation, failoverId, "STABLE",
                    List.of("A", "C"), List.of()));

            PeerServer recoveredB = new PeerServer("127.0.0.1", peers.get(1).port(), stores.get(1),
                    new ReplicationManager("B", peers));
            servers.set(1, recoveredB);
            recoveredB.start();
            heartbeat.checkNow();
            assertEquals(HeartbeatManager.PeerState.ALIVE, status(heartbeat, "B").state());
            await(() -> reconciliation.status().recentOutcomes().stream()
                    .anyMatch(outcome -> "RECOVERY_DETECTED".equals(outcome.state())
                            && "B".equals(outcome.message())));
            healing.checkNow();
            reconciliation.checkNow();
            await(() -> viewIs(reconciliation, failoverId, "EXTRA_CANDIDATE",
                    List.of("A", "B"), List.of("C")));
            assertEquals(3, countVerifiedCopies(peers, failover.metadata()));
            assertTrue(Duration.between(interruptionStarted, Instant.now()).toSeconds() < 10);
                verifyNoDuplicateOrConflictingMetadata(peers, expected);
            }
        } finally {
            uploads.shutdownNow();
            uploads.awaitTermination(2, TimeUnit.SECONDS);
            for (int index = servers.size() - 1; index >= 0; index--) servers.get(index).close();
        }
    }

    private Map<UUID, UploadExpectation> uploadConcurrently(
            List<ReplicationManager.PeerEndpoint> peers, List<Path> sources,
            ExecutorService uploads) throws Exception {
        List<Future<UploadExpectation>> futures = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            int ownerIndex = index % peers.size();
            UUID fileId = UUID.randomUUID();
            Path source = sources.get(index);
            ReplicationManager.PeerEndpoint owner = peers.get(ownerIndex);
            futures.add(uploads.submit(() -> {
                TransferResult result = PeerClient.upload(owner.host(), owner.port(), source, fileId);
                if (!result.success()) throw new IOException(result.code() + ": " + result.message());
                FileMetadata metadata = find(PeerClient.listFiles(owner.host(), owner.port()), fileId);
                ReplicationManager.PeerEndpoint target = peers.get((ownerIndex + 1) % peers.size());
                return new UploadExpectation(fileId, owner, target, metadata, source);
            }));
        }
        Map<UUID, UploadExpectation> result = new HashMap<>();
        for (Future<UploadExpectation> future : futures) {
            UploadExpectation uploaded;
            try {
                uploaded = future.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new AssertionError("concurrent upload failed", e.getCause());
            }
            result.put(uploaded.fileId(), uploaded);
        }
        return result;
    }

    private List<Path> sourceFiles(int count) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Path source = temporaryDirectory.resolve("scale-source-" + index + ".bin");
            Files.write(source, new byte[] {(byte) index, 17, 29, 43, 59, 71});
            sources.add(source);
        }
        return sources;
    }

    private long verifyExpectedReplicas(List<ReplicationManager.PeerEndpoint> peers,
                                        Map<UUID, UploadExpectation> expected) {
        long verified = 0;
        for (UploadExpectation upload : expected.values()) {
            assertTrue(verifiedAt(upload.owner(), upload.metadata(), peers));
            assertTrue(verifiedAt(upload.target(), upload.metadata(), peers));
            assertEquals(2, countVerifiedCopies(peers, upload.metadata()));
            verified += 2;
        }
        return verified;
    }

    private void verifyNoDuplicateOrConflictingMetadata(List<ReplicationManager.PeerEndpoint> peers,
                                                         Map<UUID, UploadExpectation> expected)
            throws IOException {
        for (UploadExpectation upload : expected.values()) {
            int copies = 0;
            for (ReplicationManager.PeerEndpoint peer : peers) {
                List<FileMetadata> inventory = PeerClient.listFiles(peer.host(), peer.port());
                List<FileMetadata> matching = inventory.stream()
                        .filter(metadata -> upload.fileId().equals(metadata.fileId())).toList();
                assertTrue(matching.size() <= 1);
                if (!matching.isEmpty()) {
                    assertEquals(upload.metadata(), matching.getFirst());
                    copies++;
                }
            }
            assertTrue(copies >= 2);
        }
    }

    private static boolean viewIs(ReconciliationManager manager, UUID id, String state,
                                  List<String> preferred, List<String> extras) {
        return manager.status().files().stream().anyMatch(view -> id.equals(view.fileId())
                && state.equals(view.state())
                && preferred.equals(view.preferredReplicas())
                && extras.equals(view.extraCandidates()));
    }

    private static FileMetadata find(List<FileMetadata> files, UUID id) {
        return files.stream().filter(metadata -> id.equals(metadata.fileId())).findFirst()
                .orElseThrow(() -> new AssertionError("missing file " + id));
    }

    private static boolean verifiedAt(ReplicationManager.PeerEndpoint endpoint,
                                     FileMetadata metadata, List<ReplicationManager.PeerEndpoint> ignored) {
        try {
            return PeerClient.verifyFile(endpoint.host(), endpoint.port(), metadata);
        } catch (IOException e) {
            return false;
        }
    }

    private static int countVerifiedCopies(List<ReplicationManager.PeerEndpoint> peers,
                                           FileMetadata metadata) {
        int count = 0;
        for (ReplicationManager.PeerEndpoint peer : peers) {
            if (verifiedAt(peer, metadata, peers)) count++;
        }
        return count;
    }

    private static HeartbeatManager.PeerStatus status(HeartbeatManager manager, String name) {
        return manager.statuses().stream().filter(status -> name.equals(status.peer().name()))
                .findFirst().orElseThrow();
    }

    private static List<ReplicationManager.PeerEndpoint> endpoints(int count) throws IOException {
        List<ReplicationManager.PeerEndpoint> peers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            peers.add(new ReplicationManager.PeerEndpoint(String.valueOf((char) ('A' + index)),
                    "127.0.0.1", freePort()));
        }
        return peers;
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

    private record UploadExpectation(UUID fileId, ReplicationManager.PeerEndpoint owner,
                                     ReplicationManager.PeerEndpoint target,
                                     FileMetadata metadata, Path source) { }

    private static final class ScaleTestException extends RuntimeException {
        private ScaleTestException(IOException cause) { super(cause); }
    }
}
