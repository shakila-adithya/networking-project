package storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreePeerFailoverIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void failedReplicaIsReplacedByThirdPeerAndRecoveryReportsSafeExtraCandidate() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(
                endpoint("A", portA), endpoint("B", portB), endpoint("C", portC));
        Path storeA = temporaryDirectory.resolve("three-peer-A");
        Path storeB = temporaryDirectory.resolve("three-peer-B");
        Path storeC = temporaryDirectory.resolve("three-peer-C");
        Path source = temporaryDirectory.resolve("three-peer-source.bin");
        Path unrelatedSource = temporaryDirectory.resolve("three-peer-unrelated.bin");
        Files.write(source, new byte[] {2, 7, 1, 8, 2, 8, 1, 8});
        Files.write(unrelatedSource, new byte[] {4, 2, 4, 2});

        PeerRegistry registry = new PeerRegistry("A", peers);
        ReplicationManager replication = new ReplicationManager("A", registry::endpoints);
        FileStore fileStoreA = new FileStore(storeA);
        List<String> transitions = new ArrayList<>();

        try (PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA, replication);
             PeerServer serverC = new PeerServer("127.0.0.1", portC, storeC);
             HeartbeatManager heartbeat = new HeartbeatManager("A", peers,
                     Duration.ofHours(1), 2_000, 2);
             SelfHealingManager healing = new SelfHealingManager("A", fileStoreA,
                     replication, heartbeat, Duration.ofHours(1), 2, Duration.ofMillis(10));
             ReconciliationManager reconciliation = new ReconciliationManager("A", fileStoreA,
                     replication, heartbeat, healing, Duration.ofHours(1))) {
            heartbeat.addTransitionListener(transition -> {
                transitions.add(transition.peer().name() + ":"
                        + transition.previousState() + "->" + transition.currentState());
                registry.updateState(transition.peer().name(),
                        PeerRegistry.PeerState.valueOf(transition.currentState().name()));
            });

            serverA.start();
            serverC.start();
            PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
            serverB.start();
            try {
                UUID id = UUID.randomUUID();
                UUID unrelatedId = UUID.randomUUID();
                assertTrue(PeerClient.upload("127.0.0.1", portA, source, id).success());
                assertTrue(PeerClient.upload("127.0.0.1", portC, unrelatedSource, unrelatedId).success());
                heartbeat.checkNow();

                FileMetadata original = find(PeerClient.listFiles("127.0.0.1", portA), id);
                assertEquals(original, find(PeerClient.listFiles("127.0.0.1", portB), id));
                assertTrue(PeerClient.verifyFile("127.0.0.1", portA, original));
                assertTrue(PeerClient.verifyFile("127.0.0.1", portB, original));
                assertEquals(2, countVerifiedCopies(peers, original));
                assertEquals(Files.size(source), original.size());

                byte[] unrelatedContent = Files.readAllBytes(contentPath(storeC, unrelatedId));
                byte[] unrelatedMetadata = Files.readAllBytes(metadataPath(storeC, unrelatedId));

                reconciliation.checkNow();
                await(() -> viewIs(reconciliation, id, "STABLE", List.of("A", "B"), List.of()));

                // Stop only disposable Peer B; its temporary storage remains intact for recovery.
                serverB.close();
                heartbeat.checkNow();
                heartbeat.checkNow();
                assertEquals(HeartbeatManager.PeerState.DEAD, status(heartbeat, "B").state());
                assertTrue(transitions.contains("B:ALIVE->SUSPECTED"));
                assertTrue(transitions.contains("B:SUSPECTED->DEAD"));
                assertEquals(PeerRegistry.PeerState.DEAD, member(registry, "B").state());

                healing.checkNow();
                await(() -> verifiedAt("127.0.0.1", portC, original));
                assertEquals(2, countVerifiedCopies(peers, original));
                reconciliation.checkNow();
                await(() -> viewIs(reconciliation, id, "STABLE", List.of("A", "C"), List.of()));
                assertTrue(healing.status().recentOutcomes().stream()
                        .anyMatch(outcome -> id.equals(outcome.fileId())
                                && "SUCCEEDED".equals(outcome.state())));

                PeerServer recoveredB = new PeerServer("127.0.0.1", portB, storeB);
                recoveredB.start();
                try {
                    heartbeat.checkNow();
                    assertEquals(HeartbeatManager.PeerState.ALIVE, status(heartbeat, "B").state());
                    assertEquals(PeerRegistry.PeerState.ALIVE, member(registry, "B").state());
                    assertTrue(transitions.contains("B:DEAD->ALIVE"));

                    await(() -> reconciliation.status().recentOutcomes().stream()
                            .anyMatch(outcome -> "RECOVERY_DETECTED".equals(outcome.state())
                                    && "B".equals(outcome.message())));
                    healing.checkNow();
                    reconciliation.checkNow();

                    // Phase 5C deliberately never deletes a recovered copy. The deterministic
                    // preferred pair is A/B and C is reported as a cleanup candidate.
                    await(() -> viewIs(reconciliation, id, "EXTRA_CANDIDATE",
                            List.of("A", "B"), List.of("C")));
                    assertEquals(3, countVerifiedCopies(peers, original));
                    assertEquals(original, find(PeerClient.listFiles("127.0.0.1", portB), id));
                    assertTrue(PeerClient.verifyFile("127.0.0.1", portB, original));
                    assertTrue(PeerClient.verifyFile("127.0.0.1", portC, original));
                    assertEquals(HashUtils.sha256(source), original.sha256());
                    assertEquals(1, countId(PeerClient.listFiles("127.0.0.1", portA), id));
                    assertEquals(1, countId(PeerClient.listFiles("127.0.0.1", portB), id));
                    assertEquals(1, countId(PeerClient.listFiles("127.0.0.1", portC), id));

                    assertArrayEquals(unrelatedContent, Files.readAllBytes(contentPath(storeC, unrelatedId)));
                    assertArrayEquals(unrelatedMetadata, Files.readAllBytes(metadataPath(storeC, unrelatedId)));
                } finally {
                    recoveredB.close();
                }
            } finally {
                serverB.close();
            }
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

    private static int countId(List<FileMetadata> files, UUID id) {
        return (int) files.stream().filter(metadata -> id.equals(metadata.fileId())).count();
    }

    private static boolean verifiedAt(String host, int port, FileMetadata metadata) {
        try {
            return PeerClient.verifyFile(host, port, metadata);
        } catch (IOException e) {
            return false;
        }
    }

    private static int countVerifiedCopies(List<ReplicationManager.PeerEndpoint> peers,
                                           FileMetadata metadata) {
        int count = 0;
        for (ReplicationManager.PeerEndpoint peer : peers) {
            if (verifiedAt(peer.host(), peer.port(), metadata)) count++;
        }
        return count;
    }

    private static HeartbeatManager.PeerStatus status(HeartbeatManager manager, String name) {
        return manager.statuses().stream().filter(status -> name.equals(status.peer().name()))
                .findFirst().orElseThrow();
    }

    private static PeerRegistry.Member member(PeerRegistry registry, String name) {
        return registry.members().stream().filter(member -> name.equals(member.endpoint().name()))
                .findFirst().orElseThrow();
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
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }
}
