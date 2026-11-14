package storage.link;

import storage.cluster.ReplicationManager;
import storage.files.TransferResult;
import storage.transport.PeerClient;
import storage.transport.PeerServer;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeartbeatManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void successfulPingPongMarksReachablePeerAlive() throws Exception {
        int portB = freePort();
        Path storeB = temporaryDirectory.resolve("heartbeat-B");
        List<ReplicationManager.PeerEndpoint> peers = List.of(
                endpoint("A", freePort()), endpoint("B", portB));

        try (PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB);
             HeartbeatManager manager = new HeartbeatManager("A", peers)) {
            serverB.start();
            assertEquals("PONG", PeerClient.ping("127.0.0.1", portB));

            manager.checkNow();
            HeartbeatManager.PeerStatus status = onlyStatus(manager);
            assertEquals(HeartbeatManager.PeerState.ALIVE, status.state());
            assertEquals(0, status.consecutiveFailures());
            assertNotNull(status.lastCheckedAt());
            assertNotNull(status.lastSuccessfulAt());
            assertEquals("B", status.peer().name());
        }
    }

    @Test
    void firstFailedHeartbeatMarksPeerSuspected() {
        try (HeartbeatManager manager = manager(peer -> false, new ArrayList<>())) {
            manager.checkNow();
            HeartbeatManager.PeerStatus status = onlyStatus(manager);
            assertEquals(HeartbeatManager.PeerState.SUSPECTED, status.state());
            assertEquals(1, status.consecutiveFailures());
        }
    }

    @Test
    void thirdConsecutiveFailureMarksPeerDead() {
        try (HeartbeatManager manager = manager(peer -> false, new ArrayList<>(), 3)) {
            manager.checkNow();
            manager.checkNow();
            manager.checkNow();
            HeartbeatManager.PeerStatus status = onlyStatus(manager);
            assertEquals(HeartbeatManager.PeerState.DEAD, status.state());
            assertEquals(3, status.consecutiveFailures());
        }
    }

    @Test
    void successfulHeartbeatRecoversDeadPeerToAlive() {
        AtomicBoolean reachable = new AtomicBoolean(false);
        List<String> stateChanges = new ArrayList<>();
        try (HeartbeatManager manager = manager(peer -> reachable.get(), stateChanges, 3)) {
            manager.checkNow();
            manager.checkNow();
            manager.checkNow();
            assertEquals(HeartbeatManager.PeerState.DEAD, onlyStatus(manager).state());

            reachable.set(true);
            manager.checkNow();
            HeartbeatManager.PeerStatus recovered = onlyStatus(manager);
            assertEquals(HeartbeatManager.PeerState.ALIVE, recovered.state());
            assertEquals(0, recovered.consecutiveFailures());
            assertNotNull(recovered.lastSuccessfulAt());
            assertEquals(3, stateChanges.size());
            assertTrue(stateChanges.get(0).contains("SUSPECTED"));
            assertTrue(stateChanges.get(1).contains("DEAD"));
            assertTrue(stateChanges.get(2).contains("ALIVE"));
        }
    }

    @Test
    void closeStopsHeartbeatScheduling() {
        HeartbeatManager manager = new HeartbeatManager(
                "A", twoPeers(), Duration.ofMillis(10),
                HeartbeatManager.DEFAULT_FAILURE_THRESHOLD,
                peer -> true, message -> { });
        manager.start();
        assertTrue(manager.isRunning());
        manager.close();
        assertFalse(manager.isRunning());
        assertThrows(IllegalStateException.class, manager::start);
    }

    @Test
    void heartbeatMonitoringRemainsCompatibleWithReplication() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = List.of(
                endpoint("A", portA), endpoint("B", portB), endpoint("C", portC));
        Path storeB = temporaryDirectory.resolve("compatible-B");
        Path storeC = temporaryDirectory.resolve("compatible-C");
        Path source = temporaryDirectory.resolve("compatible.bin");
        byte[] content = new byte[] {4, 8, 15, 16, 23, 42};
        Files.write(source, content);

        try (PeerServer serverB = new PeerServer("127.0.0.1", portB, storeB,
                     new ReplicationManager("B", peers));
             PeerServer serverC = new PeerServer("127.0.0.1", portC, storeC);
             HeartbeatManager heartbeat = new HeartbeatManager("B", peers)) {
            serverB.start();
            serverC.start();
            heartbeat.checkNow();

            HeartbeatManager.PeerStatus peerC = heartbeat.statuses().stream()
                    .filter(status -> status.peer().name().equals("C"))
                    .findFirst().orElseThrow();
            assertEquals(HeartbeatManager.PeerState.ALIVE, peerC.state());

            TransferResult upload = PeerClient.upload("127.0.0.1", portB, source);
            assertTrue(upload.success(), upload.message());
            Path replica = storeC.resolve("files").resolve(upload.fileId() + ".bin");
            assertArrayEquals(content, Files.readAllBytes(replica));
        }
    }

    private static HeartbeatManager manager(HeartbeatManager.HeartbeatProbe probe,
                                            List<String> stateChanges) {
        return manager(probe, stateChanges, HeartbeatManager.DEFAULT_FAILURE_THRESHOLD);
    }

    private static HeartbeatManager manager(HeartbeatManager.HeartbeatProbe probe,
                                            List<String> stateChanges, int failureThreshold) {
        return new HeartbeatManager(
                "A", twoPeers(), Duration.ofHours(1),
                failureThreshold,
                probe, stateChanges::add);
    }

    private static List<ReplicationManager.PeerEndpoint> twoPeers() {
        return List.of(endpoint("A", 5100), endpoint("B", 5101));
    }

    private static ReplicationManager.PeerEndpoint endpoint(String name, int port) {
        return new ReplicationManager.PeerEndpoint(name, "127.0.0.1", port);
    }

    private static HeartbeatManager.PeerStatus onlyStatus(HeartbeatManager manager) {
        assertEquals(1, manager.statuses().size());
        return manager.statuses().getFirst();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
