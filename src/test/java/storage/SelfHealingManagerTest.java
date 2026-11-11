package storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
