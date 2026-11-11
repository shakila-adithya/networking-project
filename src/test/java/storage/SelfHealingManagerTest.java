package storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class SelfHealingManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void repairsFromBToAWhenCIsUnavailableAndPreservesMetadata() throws Exception {
        int portA = freePort(), portB = freePort(), portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(endpoint("A", portA),
                endpoint("B", portB), endpoint("C", portC));
        Path source = temporaryDirectory.resolve("payload.bin");
        Files.write(source, new byte[] {1, 3, 3, 7, 9});
        Path storeA = temporaryDirectory.resolve("A");
        Path storeB = temporaryDirectory.resolve("B");
        ReplicationManager replication = new ReplicationManager("B", peers);
        try (PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA);
             PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
             HeartbeatManager heartbeat = new HeartbeatManager("B", peers,
                     Duration.ofHours(1), 2_000, 1);
             SelfHealingManager healing = new SelfHealingManager("B", new FileStore(storeB),
                     replication, heartbeat, Duration.ofHours(1), 2, Duration.ofMillis(10))) {
            serverA.start();
            serverB.start();
            UUID id = UUID.randomUUID();
            assertTrue(PeerClient.upload("127.0.0.1", portB, source, id).success());
            heartbeat.checkNow();
            healing.checkNow();
            await(() -> {
                try {
                    return PeerClient.listFiles("127.0.0.1", portA).stream()
                            .anyMatch(metadata -> metadata.fileId().equals(id));
                } catch (IOException e) {
                    return false;
                }
            });
            FileMetadata b = PeerClient.listFiles("127.0.0.1", portB).getFirst();
            FileMetadata a = PeerClient.listFiles("127.0.0.1", portA).getFirst();
            assertEquals(b, a);
            assertEquals(HashUtils.sha256(source), a.sha256());
            assertTrue(healing.status().recentOutcomes().stream()
                    .anyMatch(outcome -> outcome.fileId().equals(id) && "SUCCEEDED".equals(outcome.state())));
        }
    }

    @Test
    void conflictingMetadataIsDeferredWithoutWritingDestination() throws Exception {
        int portA = freePort(), portB = freePort(), portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(endpoint("A", portA),
                endpoint("B", portB), endpoint("C", portC));
        Path storeB = temporaryDirectory.resolve("conflict-B");
        Path storeA = temporaryDirectory.resolve("conflict-A");
        UUID id = UUID.randomUUID();
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        Files.write(first, new byte[] {1});
        Files.write(second, new byte[] {2, 2});
        try (PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA);
             PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
             HeartbeatManager heartbeat = new HeartbeatManager("B", peers,
                     Duration.ofHours(1), 2_000, 1);
             SelfHealingManager healing = new SelfHealingManager("B", new FileStore(storeB),
                     new ReplicationManager("B", peers), heartbeat,
                     Duration.ofHours(1), 1, Duration.ofMillis(10))) {
            serverA.start();
            serverB.start();
            assertTrue(PeerClient.upload("127.0.0.1", portB, first, id).success());
            FileMetadata conflicting = new FileMetadata(id, "other.bin", 2,
                    HashUtils.sha256(second), Instant.now().toString());
            assertTrue(PeerClient.uploadReplica("127.0.0.1", portA, conflicting, second).success());
            heartbeat.checkNow();
            healing.checkNow();
            assertTrue(healing.status().recentOutcomes().stream()
                    .anyMatch(outcome -> outcome.fileId().equals(id) && "DEFERRED".equals(outcome.state())));
            assertFalse(PeerClient.listFiles("127.0.0.1", portA).isEmpty());
        }
    }

    @Test
    void repeatedSinglePeerDeferralIsLoggedOnceAndRepairResumesAfterJoin() throws Exception {
        int portA = freePort(), portB = freePort();
        Path storeA = temporaryDirectory.resolve("bootstrap-A");
        Path storeB = temporaryDirectory.resolve("bootstrap-B");
        Path source = temporaryDirectory.resolve("bootstrap.bin");
        Files.write(source, new byte[] {9, 8, 7});
        PeerRegistry registry = new PeerRegistry("B", List.of(endpoint("B", portB)));
        ReplicationManager replication = new ReplicationManager("B", registry::endpoints);
        try (PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB, replication);
             HeartbeatManager heartbeat = new HeartbeatManager("B", registry.endpoints());
             SelfHealingManager healing = new SelfHealingManager("B", new FileStore(storeB),
                     replication, heartbeat, Duration.ofHours(1), 1, Duration.ofMillis(10));
             PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA)) {
            serverB.start();
            UUID id = UUID.randomUUID();
            assertTrue(PeerClient.upload("127.0.0.1", portB, source, id).success());
            healing.checkNow();
            healing.checkNow();
            long deferred = healing.status().recentOutcomes().stream()
                    .filter(outcome -> id.equals(outcome.fileId()) && "DEFERRED".equals(outcome.state())).count();
            assertEquals(1, deferred);

            serverA.start();
            registry.join(endpoint("A", portA));
            heartbeat.refreshPeers(registry.endpoints());
            heartbeat.checkNow();
            healing.checkNow();
            await(() -> {
                try {
                    return PeerClient.listFiles("127.0.0.1", portA).stream()
                            .anyMatch(metadata -> metadata.fileId().equals(id));
                } catch (IOException e) {
                    return false;
                }
            });
            assertTrue(healing.status().recentOutcomes().stream()
                    .anyMatch(outcome -> id.equals(outcome.fileId()) && "SUCCEEDED".equals(outcome.state())));
        }
    }

    @Test
    void lostReplicaIsRestoredAndReconcilesToStableWithoutTouchingUnrelatedFiles() throws Exception {
        int portA = freePort(), portB = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(
                endpoint("A", portA), endpoint("B", portB));
        Path storeA = temporaryDirectory.resolve("integration-A");
        Path storeB = temporaryDirectory.resolve("integration-B");
        Path targetSource = temporaryDirectory.resolve("target.bin");
        Path unrelatedSource = temporaryDirectory.resolve("unrelated.bin");
        Files.write(targetSource, new byte[] {11, 22, 33, 44, 55});
        Files.write(unrelatedSource, new byte[] {91, 92, 93});

        PeerRegistry registry = new PeerRegistry("A", peers);
        ReplicationManager replication = new ReplicationManager("A", registry::endpoints);
        FileStore fileStoreA = new FileStore(storeA);
        try (PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA, replication);
             PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
             HeartbeatManager heartbeat = new HeartbeatManager("A", peers,
                     Duration.ofHours(1), 2_000, 1);
             SelfHealingManager healing = new SelfHealingManager("A", fileStoreA,
                     replication, heartbeat, Duration.ofHours(1), 2, Duration.ofMillis(10));
             ReconciliationManager reconciliation = new ReconciliationManager("A", fileStoreA,
                     replication, heartbeat, healing, Duration.ofHours(1))) {
            serverA.start();
            serverB.start();

            UUID targetId = UUID.randomUUID();
            UUID unrelatedId = UUID.randomUUID();
            assertTrue(PeerClient.upload("127.0.0.1", portA, targetSource, targetId).success());
            assertTrue(PeerClient.upload("127.0.0.1", portA, unrelatedSource, unrelatedId).success());
            heartbeat.checkNow();

            FileMetadata targetBefore = find(PeerClient.listFiles("127.0.0.1", portA), targetId);
            FileMetadata unrelatedBefore = find(PeerClient.listFiles("127.0.0.1", portB), unrelatedId);
            byte[] unrelatedContentBefore = Files.readAllBytes(contentPath(storeB, unrelatedId));
            byte[] unrelatedMetadataBefore = Files.readAllBytes(metadataPath(storeB, unrelatedId));
            assertEquals(targetBefore, find(PeerClient.listFiles("127.0.0.1", portB), targetId));
            assertEquals(HashUtils.sha256(targetSource), targetBefore.sha256());

            // Deliberately simulate loss only inside this test's temporary store and only
            // for the test UUID. No production deletion API or real storage directory is used.
            Files.delete(contentPath(storeB, targetId));
            Files.delete(metadataPath(storeB, targetId));
            assertFalse(PeerClient.listFiles("127.0.0.1", portB).stream()
                    .anyMatch(metadata -> metadata.fileId().equals(targetId)));

            healing.checkNow();
            await(() -> contains("127.0.0.1", portB, targetId));

            FileMetadata restoredA = find(PeerClient.listFiles("127.0.0.1", portA), targetId);
            FileMetadata restoredB = find(PeerClient.listFiles("127.0.0.1", portB), targetId);
            assertEquals(targetBefore, restoredA);
            assertEquals(restoredA, restoredB);
            assertEquals(targetSource.toFile().length(), restoredB.size());
            assertEquals(HashUtils.sha256(targetSource), restoredB.sha256());
            assertEquals(HashUtils.sha256(contentPath(storeB, targetId)), restoredB.sha256());
            assertEquals(2, countCopies(peers, targetId));
            assertArrayEquals(unrelatedContentBefore, Files.readAllBytes(contentPath(storeB, unrelatedId)));
            assertArrayEquals(unrelatedMetadataBefore, Files.readAllBytes(metadataPath(storeB, unrelatedId)));
            assertEquals(unrelatedBefore, find(PeerClient.listFiles("127.0.0.1", portB), unrelatedId));

            reconciliation.checkNow();
            await(() -> reconciliation.status().files().stream()
                    .anyMatch(view -> targetId.equals(view.fileId()) && "STABLE".equals(view.state())));
            assertTrue(healing.status().recentOutcomes().stream()
                    .anyMatch(outcome -> targetId.equals(outcome.fileId())
                            && "SUCCEEDED".equals(outcome.state())));
        }
    }

    @Test
    void corruptedReplicaIsDetectedRepairedAndReconcilesToStable() throws Exception {
        int portA = freePort(), portB = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(
                endpoint("A", portA), endpoint("B", portB));
        Path storeA = temporaryDirectory.resolve("corruption-A");
        Path storeB = temporaryDirectory.resolve("corruption-B");
        Path source = temporaryDirectory.resolve("corruption-source.bin");
        Path unrelatedSource = temporaryDirectory.resolve("corruption-unrelated.bin");
        Files.write(source, new byte[] {10, 20, 30, 40, 50, 60});
        Files.write(unrelatedSource, new byte[] {70, 80, 90});

        PeerRegistry registry = new PeerRegistry("A", peers);
        ReplicationManager replication = new ReplicationManager("A", registry::endpoints);
        FileStore fileStoreA = new FileStore(storeA);
        try (PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA, replication);
             PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
             HeartbeatManager heartbeat = new HeartbeatManager("A", peers,
                     Duration.ofHours(1), 2_000, 1);
             SelfHealingManager healing = new SelfHealingManager("A", fileStoreA,
                     replication, heartbeat, Duration.ofHours(1), 2, Duration.ofMillis(10));
             ReconciliationManager reconciliation = new ReconciliationManager("A", fileStoreA,
                     replication, heartbeat, healing, Duration.ofHours(1))) {
            serverA.start();
            serverB.start();

            UUID id = UUID.randomUUID();
            UUID unrelatedId = UUID.randomUUID();
            assertTrue(PeerClient.upload("127.0.0.1", portA, source, id).success());
            assertTrue(PeerClient.upload("127.0.0.1", portA, unrelatedSource, unrelatedId).success());
            heartbeat.checkNow();

            FileMetadata original = find(PeerClient.listFiles("127.0.0.1", portA), id);
            FileMetadata unrelated = find(PeerClient.listFiles("127.0.0.1", portB), unrelatedId);
            byte[] unrelatedContent = Files.readAllBytes(contentPath(storeB, unrelatedId));
            byte[] unrelatedMetadata = Files.readAllBytes(metadataPath(storeB, unrelatedId));
            assertEquals(original, find(PeerClient.listFiles("127.0.0.1", portB), id));
            assertTrue(PeerClient.verifyFile("127.0.0.1", portA, original));
            assertTrue(PeerClient.verifyFile("127.0.0.1", portB, original));

            // Keep the metadata and byte length unchanged, but corrupt only this test replica.
            Files.write(contentPath(storeB, id), new byte[] {99, 20, 30, 40, 50, 60});
            assertFalse(PeerClient.verifyFile("127.0.0.1", portB, original));
            assertEquals(original, find(PeerClient.listFiles("127.0.0.1", portB), id));

            healing.checkNow();
            await(() -> {
                try {
                    return PeerClient.verifyFile("127.0.0.1", portB, original)
                            && HashUtils.sha256(contentPath(storeB, id)).equals(original.sha256());
                } catch (IOException e) {
                    return false;
                }
            });

            FileMetadata repaired = find(PeerClient.listFiles("127.0.0.1", portB), id);
            assertEquals(original, repaired);
            assertEquals(source.toFile().length(), repaired.size());
            assertEquals(HashUtils.sha256(source), repaired.sha256());
            assertEquals(2, countCopies(peers, id));
            assertArrayEquals(unrelatedContent, Files.readAllBytes(contentPath(storeB, unrelatedId)));
            assertArrayEquals(unrelatedMetadata, Files.readAllBytes(metadataPath(storeB, unrelatedId)));
            assertEquals(unrelated, find(PeerClient.listFiles("127.0.0.1", portB), unrelatedId));

            reconciliation.checkNow();
            await(() -> reconciliation.status().files().stream()
                    .anyMatch(view -> id.equals(view.fileId()) && "STABLE".equals(view.state())));
            assertTrue(healing.status().recentOutcomes().stream()
                    .anyMatch(outcome -> id.equals(outcome.fileId())
                            && "SUCCEEDED".equals(outcome.state())));
        }
    }

    private static FileMetadata find(List<FileMetadata> files, UUID id) {
        return files.stream().filter(metadata -> id.equals(metadata.fileId())).findFirst()
                .orElseThrow(() -> new AssertionError("missing file " + id));
    }

    private static boolean contains(String host, int port, UUID id) {
        try {
            return PeerClient.listFiles(host, port).stream()
                    .anyMatch(metadata -> id.equals(metadata.fileId()));
        } catch (IOException e) {
            return false;
        }
    }

    private static int countCopies(List<ReplicationManager.PeerEndpoint> peers, UUID id) {
        int count = 0;
        for (ReplicationManager.PeerEndpoint peer : peers) {
            if (contains(peer.host(), peer.port(), id)) count++;
        }
        return count;
    }

    private static Path contentPath(Path store, UUID id) {
        return store.resolve("files").resolve(id + ".bin");
    }

    private static Path metadataPath(Path store, UUID id) {
        return store.resolve("metadata").resolve(id + ".json");
    }

    private static ReplicationManager.PeerEndpoint endpoint(String name, int port) {
        return new ReplicationManager.PeerEndpoint(name, "127.0.0.1", port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean());
    }
}
