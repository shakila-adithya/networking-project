package storage.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import storage.config.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceIdentitySettingsTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private String previousTransport;
    @BeforeEach void useTcp() { previousTransport = System.getProperty("mesh.transport"); System.setProperty("mesh.transport", "tcp"); }
    @AfterEach void restoreTransport() {
        if (previousTransport == null) System.clearProperty("mesh.transport"); else System.setProperty("mesh.transport", previousTransport);
    }
    private SetupConfigStore store() { return new SetupConfigStore(root.resolve("local-setup.json")); }
    private SetupAgent agent(SetupConfigStore store) throws Exception {
        var agent = new SetupAgent(0, store, root, List::of); agent.start(); return agent;
    }
    private HttpResponse<String> change(SetupAgent agent, String body, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/setup/identity"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        try (var client = HttpClient.newHttpClient()) { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    @Test void freshDevicesHaveDifferentStableIdsAndCanRenameBeforeJoining() throws Exception {
        String first;
        try (var firstAgent = agent(store()); var secondAgent = agent(new SetupConfigStore(root.resolve("second/local-setup.json")))) {
            first = Files.readString(root.resolve("device-id.txt")).strip();
            assertNotEquals(first, Files.readString(root.resolve("second/device-id.txt")).strip());
            var response = change(firstAgent, "{\"deviceId\":\"Windows_PC_2\"}", null);
            assertEquals(200, response.statusCode()); assertFalse(json.readTree(response.body()).path("restarting").asBoolean());
            assertFalse(store().exists());
            assertEquals("Windows_PC_2", Files.readString(root.resolve("device-id.txt")).strip());
        }
        try (var restarted = agent(store())) {
            assertEquals("Windows_PC_2", Files.readString(root.resolve("device-id.txt")).strip());
            assertEquals(200, change(restarted, "{\"deviceId\":\"Windows_PC_2\"}", null).statusCode());
        }
    }
    private SetupConfigStore.Saved saved(int peer, int rest) {
        return new SetupConfigStore.Saved(new PeerConfiguration("A", "0.0.0.0", "127.0.0.1", peer,
                root.resolve("existing-files").toString(), List.of(new PeerConfiguration.PeerDefinition("A", "127.0.0.1", peer),
                new PeerConfiguration.PeerDefinition("B", "192.0.2.11", peer)), "identity-test", true,
                "239.255.42.99", 45555, "preserve-this-mesh-password"), rest);
    }
    @Test void renamePreservesFilesSettingsAndLeftStateAndRejectsKnownDuplicates() throws Exception {
        var store = store(); store.save(saved(5010, 6010)); store.setAutoJoin(false);
        var before = store.load();
        Path file = root.resolve("existing-files/keep.txt"); Files.createDirectories(file.getParent()); Files.writeString(file, "keep");
        try (var agent = agent(store)) {
            assertEquals(400, change(agent, "{\"deviceId\":\"B\"}", null).statusCode());
            assertEquals(before, store.load());
            var response = change(agent, "{\"deviceId\":\"Windows_PC_2\"}", null);
            assertEquals(200, response.statusCode()); assertFalse(response.body().contains(before.peer().discoverySecret()));
            var after = store.load();
            assertEquals("Windows_PC_2", after.peer().localPeerId());
            assertEquals("Windows_PC_2", after.peer().peers().getFirst().id());
            assertEquals(before.peer().peers().get(1), after.peer().peers().get(1));
            assertEquals(before.peer().storageDirectory(), after.peer().storageDirectory());
            assertEquals(before.peer().discoverySecret(), after.peer().discoverySecret());
            assertEquals(before.peer().port(), after.peer().port()); assertEquals(before.restPort(), after.restPort());
            assertEquals("keep", Files.readString(file)); assertFalse(store.autoJoin());
            assertEquals("LEFT", agent.status().get("phase"));
        }
        try (var reopened = agent(store)) { assertEquals("Windows_PC_2", Files.readString(root.resolve("device-id.txt")).strip()); }
    }
    @Test void invalidAndCrossOriginRequestsCannotChangeIdentity() throws Exception {
        try (var agent = agent(store())) {
            String before = Files.readString(root.resolve("device-id.txt"));
            for (String body : List.of("{}", "{\"deviceId\":\"\"}", "{\"deviceId\":\"has spaces\"}",
                    "{\"deviceId\":\"" + "x".repeat(41) + "\"}", "{\"deviceId\":\"new\",\"visible\":true}", "null"))
                assertEquals(400, change(agent, body, null).statusCode(), body);
            assertEquals(403, change(agent, "{\"deviceId\":\"new\"}", "https://another.example").statusCode());
            assertEquals(before, Files.readString(root.resolve("device-id.txt")));
        }
    }
    @Test void activeNodeReconnectsWithNewIdentityAtTheSamePorts() throws Exception {
        int peer, rest;
        // Check the mesh port as well as the peer port before starting the real service.
        while (true) {
            try (var a = new ServerSocket(0)) {
                peer = a.getLocalPort();
                if (peer > 63535) continue;
                try (var mesh = new ServerSocket(peer + 2000); var b = new ServerSocket(0)) {
                    rest = b.getLocalPort(); break;
                } catch (java.io.IOException busy) { /* Try another peer/mesh port pair. */ }
            }
        }
        var store = store();
        var original = saved(peer, rest);
        var p = original.peer();
        store.save(new SetupConfigStore.Saved(new PeerConfiguration(p.localPeerId(), p.bindAddress(), p.advertisedAddress(),
                p.port(), p.storageDirectory(), List.of(p.peers().getFirst()), p.clusterId(), p.discoveryEnabled(),
                p.discoveryGroup(), p.discoveryPort(), p.discoverySecret()), rest));
        try (var agent = agent(store)) {
            awaitReady(agent);
            var response = change(agent, "{\"deviceId\":\"Windows_PC_2\"}", null);
            assertEquals(200, response.statusCode()); assertTrue(json.readTree(response.body()).path("restarting").asBoolean());
            awaitReady(agent);
            assertEquals("Windows_PC_2", ((java.util.Map<?, ?>)agent.status().get("settings")).get("peerId"));
            try (var client = HttpClient.newHttpClient()) {
                var profile = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/device")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals("Windows_PC_2", json.readTree(profile.body()).path("deviceId").asText());
            }
        }
    }
    private void awaitReady(SetupAgent agent) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while ("STARTING".equals(agent.status().get("phase")) && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotEquals("STARTING", agent.status().get("phase"));
        assertNotEquals("ERROR", agent.status().get("phase"), agent.status().toString());
    }
}
