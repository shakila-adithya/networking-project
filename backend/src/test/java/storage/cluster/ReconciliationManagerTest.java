package storage.cluster;

import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.files.HashUtils;
import storage.link.HeartbeatManager;
import storage.transport.PeerClient;
import storage.transport.PeerServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconciliationManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void threeVerifiedCopiesSelectAAndBAndMarkCAsCleanupCandidate() throws Exception {
        int portA = freePort(), portB = freePort(), portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = peers(portA, portB, portC);
        Path source = temporaryDirectory.resolve("recovered.bin");
        Files.write(source, new byte[] {4, 8, 15, 16, 23, 42});
        UUID id = UUID.randomUUID();
        try (PeerServer a = new PeerServer("127.0.0.1", portA, temporaryDirectory.resolve("A"));
             PeerServer b = new PeerServer("127.0.0.1", portB, temporaryDirectory.resolve("B"));
             PeerServer c = new PeerServer("127.0.0.1", portC, temporaryDirectory.resolve("C"));
             HeartbeatManager heartbeat = new HeartbeatManager("B", peers,
                     Duration.ofHours(1), 2_000, 1);
             SelfHealingManager repair = new SelfHealingManager("B", new FileStore(temporaryDirectory.resolve("B")),
                     new ReplicationManager("B", peers), heartbeat, Duration.ofHours(1), 1, Duration.ofMillis(10));
             ReconciliationManager reconcile = new ReconciliationManager("B",
                     new FileStore(temporaryDirectory.resolve("B")), new ReplicationManager("B", peers),
                     heartbeat, repair, Duration.ofHours(1))) {
            a.start(); b.start(); c.start();
            assertTrue(PeerClient.upload("127.0.0.1", portB, source, id).success());
            FileMetadata metadata = PeerClient.listFiles("127.0.0.1", portB).getFirst();
            assertTrue(PeerClient.uploadReplica("127.0.0.1", portA, metadata, source).success());
            assertTrue(PeerClient.uploadReplica("127.0.0.1", portC, metadata, source).success());
            heartbeat.checkNow();
            reconcile.checkNow();
            await(() -> reconcile.status().files().stream().anyMatch(view -> id.equals(view.fileId())));
            ReconciliationManager.ReconciliationView view = reconcile.status().files().stream()
                    .filter(candidate -> id.equals(candidate.fileId())).findFirst().orElseThrow();
            assertEquals("EXTRA_CANDIDATE", view.state());
            assertEquals(List.of("A", "B"), view.preferredReplicas());
            assertEquals(List.of("C"), view.extraCandidates());
            await(() -> reconcile.status().activeReconciliations().isEmpty());
            long loggedBeforeUnchangedScan = reconcile.status().recentOutcomes().stream()
                    .filter(outcome -> id.equals(outcome.fileId())).count();
            reconcile.checkNow();
            await(() -> reconcile.status().activeReconciliations().isEmpty());
            long loggedAfterUnchangedScan = reconcile.status().recentOutcomes().stream()
                    .filter(outcome -> id.equals(outcome.fileId())).count();
            assertEquals(loggedBeforeUnchangedScan, loggedAfterUnchangedScan,
                    "unchanged reconciliation results must not be logged repeatedly");
        }
    }

    @Test
    void conflictingMetadataIsDeferredAndUnreachablePeerIsReported() throws Exception {
        int portA = freePort(), portB = freePort(), unavailableC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = peers(portA, portB, unavailableC);
        Path first = temporaryDirectory.resolve("first.bin");
        Path second = temporaryDirectory.resolve("second.bin");
        Files.write(first, new byte[] {1});
        Files.write(second, new byte[] {2, 3});
        UUID id = UUID.randomUUID();
        try (PeerServer a = new PeerServer("127.0.0.1", portA, temporaryDirectory.resolve("conflict-A"));
             PeerServer b = new PeerServer("127.0.0.1", portB, temporaryDirectory.resolve("conflict-B"));
             HeartbeatManager heartbeat = new HeartbeatManager("B", peers,
                     Duration.ofHours(1), 2_000, 1);
             SelfHealingManager repair = new SelfHealingManager("B", new FileStore(temporaryDirectory.resolve("conflict-B")),
                     new ReplicationManager("B", peers), heartbeat, Duration.ofHours(1), 1, Duration.ofMillis(10));
             ReconciliationManager reconcile = new ReconciliationManager("B",
                     new FileStore(temporaryDirectory.resolve("conflict-B")), new ReplicationManager("B", peers),
                     heartbeat, repair, Duration.ofHours(1))) {
            a.start(); b.start();
            assertTrue(PeerClient.upload("127.0.0.1", portB, first, id).success());
            FileMetadata conflicting = new FileMetadata(id, "different.bin", 2,
                    HashUtils.sha256(second), Instant.now().toString());
            assertTrue(PeerClient.uploadReplica("127.0.0.1", portA, conflicting, second).success());
            heartbeat.checkNow();
            reconcile.checkNow();
            await(() -> reconcile.status().files().stream().anyMatch(view -> id.equals(view.fileId())));
            ReconciliationManager.ReconciliationView view = reconcile.status().files().stream()
                    .filter(candidate -> id.equals(candidate.fileId())).findFirst().orElseThrow();
            assertEquals("CONFLICT", view.state());
            assertTrue(view.metadataConflicts().contains("A"));
            assertTrue(view.unavailablePeers().contains("C"));
        }
    }

    @Test
    void deadToAliveTransitionTriggersRecoveryOutcomeWithoutDuplicateScan() throws Exception {
        int portA = freePort(), portB = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(endpoint("A", portA), endpoint("B", portB));
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(false);
        try (HeartbeatManager heartbeat = new HeartbeatManager("A", peers, Duration.ofHours(1),
                     3, peer -> alive.get(), ignored -> { });
             SelfHealingManager repair = new SelfHealingManager("A", new FileStore(temporaryDirectory.resolve("event")),
                     new ReplicationManager("A", peers), heartbeat, Duration.ofHours(1), 1, Duration.ofMillis(10));
             ReconciliationManager reconcile = new ReconciliationManager("A", new FileStore(temporaryDirectory.resolve("event")),
                     new ReplicationManager("A", peers), heartbeat, repair, Duration.ofHours(1))) {
            heartbeat.checkNow(); heartbeat.checkNow(); heartbeat.checkNow();
            alive.set(true);
            heartbeat.checkNow();
            assertTrue(reconcile.status().recentOutcomes().stream()
                    .anyMatch(outcome -> "RECOVERY_DETECTED".equals(outcome.state())));
        }
    }

    @Test
    void outcomeLoggingUpdatesSignatureAcrossTransitionsAndIsAtomicForDuplicates() throws Exception {
        List<ReplicationManager.PeerEndpoint> peers = List.of(endpoint("A", freePort()));
        try (HeartbeatManager heartbeat = new HeartbeatManager("A", peers);
             SelfHealingManager repair = new SelfHealingManager("A",
                     new FileStore(temporaryDirectory.resolve("logging")),
                     new ReplicationManager("A", peers), heartbeat,
                     Duration.ofHours(1), 1, Duration.ofMillis(10));
             ReconciliationManager reconcile = new ReconciliationManager("A",
                     new FileStore(temporaryDirectory.resolve("logging")),
                     new ReplicationManager("A", peers), heartbeat, repair, Duration.ofHours(1))) {
            UUID same = UUID.randomUUID();
            reconcile.outcome(same, "EXTRA_CANDIDATE", "A", "A");
            reconcile.outcome(same, "EXTRA_CANDIDATE", "A", "A");
            assertEquals(1, countOutcomes(reconcile, same));

            UUID changed = UUID.randomUUID();
            reconcile.outcome(changed, "EXTRA_CANDIDATE", "A", "A");
            reconcile.outcome(changed, "DEFERRED", "B", "B");
            assertEquals(2, countOutcomes(reconcile, changed));

            UUID returned = UUID.randomUUID();
            reconcile.outcome(returned, "EXTRA_CANDIDATE", "A", "A");
            reconcile.outcome(returned, "DEFERRED", "B", "B");
            reconcile.outcome(returned, "EXTRA_CANDIDATE", "A", "A");
            assertEquals(3, countOutcomes(reconcile, returned));

            UUID concurrent = UUID.randomUUID();
            java.util.stream.IntStream.range(0, 32).parallel()
                    .forEach(ignored -> reconcile.outcome(concurrent, "STABLE", "same", "same"));
            assertEquals(1, countOutcomes(reconcile, concurrent));
        }
    }

    private static long countOutcomes(ReconciliationManager manager, UUID fileId) {
        return manager.status().recentOutcomes().stream()
                .filter(outcome -> fileId.equals(outcome.fileId())).count();
    }

    private static List<ReplicationManager.PeerEndpoint> peers(int a, int b, int c) {
        return List.of(endpoint("A", a), endpoint("B", b), endpoint("C", c));
    }
    private static ReplicationManager.PeerEndpoint endpoint(String name, int port) {
        return new ReplicationManager.PeerEndpoint(name, "127.0.0.1", port);
    }
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean());
    }
}
