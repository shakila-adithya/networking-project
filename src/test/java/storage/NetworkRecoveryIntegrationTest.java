package storage;

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

class NetworkRecoveryIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void interruptedPeerMovesThroughFailureStatesAndRecoversWithoutDataChange() throws Exception {
        int portA = freePort();
        int portB = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(
                endpoint("A", portA), endpoint("B", portB));
        Path storeA = temporaryDirectory.resolve("network-A");
        Path storeB = temporaryDirectory.resolve("network-B");
        Path source = temporaryDirectory.resolve("network-source.bin");
        Files.write(source, new byte[] {3, 1, 4, 1, 5, 9, 2, 6});

        PeerRegistry registry = new PeerRegistry("A", peers);
        ReplicationManager replication = new ReplicationManager("A", registry::endpoints);
        FileStore fileStoreA = new FileStore(storeA);
        List<String> transitions = new ArrayList<>();

        try (PeerServer serverA = new PeerServer("127.0.0.1", portA, storeA, replication);
             HeartbeatManager heartbeat = new HeartbeatManager("A", peers,
                     Duration.ofHours(1), 2_000, 2);
             SelfHealingManager healing = new SelfHealingManager("A", fileStoreA,
                     replication, heartbeat, Duration.ofHours(1), 2, Duration.ofMillis(10));
             ReconciliationManager reconciliation = new ReconciliationManager("A", fileStoreA,
                     replication, heartbeat, healing, Duration.ofHours(1))) {
            heartbeat.addTransitionListener(transition -> {
                transitions.add(transition.previousState() + "->" + transition.currentState());
                registry.updateState(transition.peer().name(),
                        PeerRegistry.PeerState.valueOf(transition.currentState().name()));
            });

            serverA.start();
            PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
            serverB.start();
            try {
                UUID id = UUID.randomUUID();
                assertTrue(PeerClient.upload("127.0.0.1", portA, source, id).success());
                heartbeat.checkNow();
                FileMetadata original = find(PeerClient.listFiles("127.0.0.1", portA), id);
                assertTrue(PeerClient.verifyFile("127.0.0.1", portA, original));
                assertTrue(PeerClient.verifyFile("127.0.0.1", portB, original));

                // Stop only the disposable Peer B service; its temporary store is retained.
                serverB.close();
                heartbeat.checkNow();
                heartbeat.checkNow();
                assertEquals(List.of("ALIVE->SUSPECTED", "SUSPECTED->DEAD"), transitions);
                assertEquals(HeartbeatManager.PeerState.DEAD, status(heartbeat, "B").state());
                assertEquals(PeerRegistry.PeerState.DEAD, member(registry, "B").state());

                healing.checkNow();
                await(() -> healing.status().recentOutcomes().stream()
                        .anyMatch(outcome -> id.equals(outcome.fileId())
                                && "DEFERRED".equals(outcome.state())
                                && outcome.message().contains("fewer than two reachable peers")));
                reconciliation.checkNow();
                await(() -> reconciliation.status().files().stream()
                        .anyMatch(view -> id.equals(view.fileId()) && "DEFERRED".equals(view.state())
                                && view.unavailablePeers().contains("B")));

                PeerServer recoveredB = new PeerServer("127.0.0.1", portB, storeB);
                recoveredB.start();
                try {
                    heartbeat.checkNow();
                    assertEquals(HeartbeatManager.PeerState.ALIVE, status(heartbeat, "B").state());
                    assertEquals(PeerRegistry.PeerState.ALIVE, member(registry, "B").state());
                    assertEquals(List.of("ALIVE->SUSPECTED", "SUSPECTED->DEAD", "DEAD->ALIVE"), transitions);

                    await(() -> reconciliation.status().recentOutcomes().stream()
                            .anyMatch(outcome -> "RECOVERY_DETECTED".equals(outcome.state())
                                    && "B".equals(outcome.message())));
                    healing.checkNow();
                    reconciliation.checkNow();
                    await(() -> {
                        try {
                            return PeerClient.verifyFile("127.0.0.1", portB, original);
                        } catch (IOException e) {
                            return false;
                        }
                    });
                    await(() -> reconciliation.status().files().stream()
                            .anyMatch(view -> id.equals(view.fileId()) && "STABLE".equals(view.state())
                                    && view.preferredReplicas().equals(List.of("A", "B"))));

                    FileMetadata recovered = find(PeerClient.listFiles("127.0.0.1", portB), id);
                    assertEquals(original, recovered);
                    assertEquals(HashUtils.sha256(source), recovered.sha256());
                    assertEquals(2, countCopies(peers, id));
                    assertEquals(1, PeerClient.listFiles("127.0.0.1", portA).stream()
                            .filter(metadata -> id.equals(metadata.fileId())).count());
                    assertEquals(1, PeerClient.listFiles("127.0.0.1", portB).stream()
                            .filter(metadata -> id.equals(metadata.fileId())).count());
                } finally {
                    recoveredB.close();
                }
            } finally {
                serverB.close();
            }
        }
    }

    private static FileMetadata find(List<FileMetadata> files, UUID id) {
        return files.stream().filter(metadata -> id.equals(metadata.fileId())).findFirst()
                .orElseThrow(() -> new AssertionError("missing file " + id));
    }

    private static HeartbeatManager.PeerStatus status(HeartbeatManager heartbeat, String name) {
        return heartbeat.statuses().stream().filter(status -> name.equals(status.peer().name()))
                .findFirst().orElseThrow();
    }

    private static PeerRegistry.Member member(PeerRegistry registry, String name) {
        return registry.members().stream().filter(member -> name.equals(member.endpoint().name()))
                .findFirst().orElseThrow();
    }

    private static int countCopies(List<ReplicationManager.PeerEndpoint> peers, UUID id) {
        int count = 0;
        for (ReplicationManager.PeerEndpoint peer : peers) {
            try {
                if (PeerClient.listFiles(peer.host(), peer.port()).stream()
                        .anyMatch(metadata -> id.equals(metadata.fileId()))) count++;
            } catch (IOException ignored) {
                // An unavailable peer is not a copy for this assertion.
            }
        }
        return count;
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
