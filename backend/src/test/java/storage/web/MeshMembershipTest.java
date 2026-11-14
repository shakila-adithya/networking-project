package storage.web;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import storage.config.*;
import storage.discovery.MeshDirectory;
import storage.messaging.*;
import storage.security.*;
import storage.transport.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class MeshMembershipTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final String password = "membership-test-password";
    private final String mesh = "membership_" + UUID.randomUUID();
    private int peerPort() throws Exception {
        for (int i = 0; i < 100; i++) {
            try (var socket = new ServerSocket(0)) {
                int port = socket.getLocalPort(); if (port > 63535) continue;
                try (var second = new ServerSocket(port + 2000)) { return port; }
                catch (java.io.IOException busy) { /* Retry with another port pair. */ }
            }
        }
        throw new java.io.IOException("No free ports");
    }
    private HttpResponse<String> post(HttpClient client, SetupAgent agent, String path, Map<String, ?> form) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/membership/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(form))).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void wrongPasswordCannotSaveJoinAndLeavePreservesStorageAndSurvivesRestart() throws Exception {
        String oldMode = System.getProperty("mesh.transport"); System.setProperty("mesh.transport", "tcp");
        int remotePort = peerPort(), localPort = peerPort(), restPort;
        try (var reserve = new ServerSocket(0)) { restPort = reserve.getLocalPort(); }
        Path storage = root.resolve("existing-files"); Files.createDirectories(storage);
        Files.writeString(storage.resolve("keep.txt"), "existing user bytes");
        var store = new SetupConfigStore(root.resolve("setup.json"));
        var previous = new PeerConfiguration("B", "127.0.0.1", "127.0.0.1", localPort, storage.toString(),
                List.of(new PeerConfiguration.PeerDefinition("B", "127.0.0.1", localPort)), "old-mesh", true,
                "239.255.42.99", 45555, "previous-mesh-password");
        store.save(new SetupConfigStore.Saved(previous, restPort)); store.setAutoJoin(false);
        try (var remote = new MeshNode("A", new SecureTransport(new TcpTransport("127.0.0.1", remotePort + 2000),
                new MeshSecurity(mesh, password)), new FloodForwarder());
             var client = HttpClient.newHttpClient()) {
            remote.start();
            try (var agent = new SetupAgent(0, store, root, () -> List.of(new LocalNetworks.Candidate("127.0.0.1", "Test loopback", true)))) {
                agent.start();
                byte[] beacon = json.writeValueAsBytes(new MeshDirectory.Advertisement("Relay-v2", mesh, "A", remotePort));
                assertTrue(agent.directory.accept(beacon, InetAddress.getLoopbackAddress()));
                var form = new HashMap<String, Object>(Map.of("meshId", mesh, "peerId", "A", "address", "127.0.0.1", "localAddress", "127.0.0.1", "password", "wrong-mesh-password"));
                assertEquals(409, post(client, agent, "join", form).statusCode());
                assertEquals("old-mesh", store.load().peer().clusterId(), "Wrong password cannot alter previous settings");
                assertFalse(store.autoJoin());
                form.put("password", password);
                var joined = post(client, agent, "join", form);
                assertEquals(202, joined.statusCode(), joined.body());
                assertFalse(joined.body().contains(password));
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!Set.of("CONNECTED", "DISCOVERING", "ERROR").contains(agent.status().get("phase")) && System.nanoTime() < deadline) Thread.sleep(20);
                assertNotEquals("ERROR", agent.status().get("phase"), agent.status().toString());
                assertEquals(mesh, store.load().peer().clusterId());
                assertEquals(storage.toString(), store.load().peer().storageDirectory());
                assertEquals(400, post(client, agent, "create", Map.of("meshId", "another", "password", password)).statusCode());
                assertEquals(200, post(client, agent, "leave", Map.of()).statusCode());
                assertFalse(store.autoJoin());
                assertEquals("existing user bytes", Files.readString(storage.resolve("keep.txt")));
                var status = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/membership")).build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(json.readTree(status.body()).path("bluetoothAvailable").isBoolean(), "Report Bluetooth availability explicitly");
                assertFalse(json.readTree(status.body()).path("bluetoothAvailable").asBoolean(), "TCP-only runs must not report Bluetooth as available");
                assertFalse(status.body().contains(password));
                assertFalse(status.body().contains("previous-mesh-password"));
            }
            try (var agent = new SetupAgent(0, store, root, List::of)) {
                agent.start(); assertEquals("LEFT", agent.status().get("phase"));
            }
        } finally { if (oldMode == null) System.clearProperty("mesh.transport"); else System.setProperty("mesh.transport", oldMode); }
    }
}
