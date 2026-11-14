package storage.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.config.LocalNetworks;
import storage.config.PeerConfiguration;
import storage.config.SetupConfigStore;
import storage.device.DeviceProfile;
import storage.device.DeviceProfileStore;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "settings-preserved-password";
    private static final List<LocalNetworks.Candidate> NETWORKS = List.of(new LocalNetworks.Candidate("192.0.2.10", "Test network", false));
    private SetupConfigStore store() { return new SetupConfigStore(root.resolve("managed.json")); }
    private SetupConfigStore.Saved saved(int peer, int rest) {
        return new SetupConfigStore.Saved(new PeerConfiguration("A", "0.0.0.0", "192.0.2.9", peer,
                root.resolve("existing-files").toString(), List.of(new PeerConfiguration.PeerDefinition("A", "192.0.2.9", peer),
                new PeerConfiguration.PeerDefinition("B", "192.0.2.11", peer)), "my-mesh", true, "239.255.42.99", 45555, SECRET), rest);
    }
    private HttpResponse<String> call(SetupAgent agent, String path, String body, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/setup/" + path));
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    @Test void nameCanBeSavedBeforeJoiningAndPreservesVisibility() throws Exception {
        var profile = new DeviceProfileStore(root.resolve("device-profile.json"));
        profile.save(new DeviceProfile("Old name", true));
        try (var agent = new SetupAgent(0, store(), root, () -> NETWORKS)) {
            agent.start();
            assertEquals(200, call(agent, "device", "{\"displayName\":\"My laptop\"}", null).statusCode());
            assertEquals(new DeviceProfile("My laptop", true), profile.load());
            var response = call(agent, "device", null, null);
            assertTrue(response.body().contains("My laptop"));
            assertFalse(response.body().contains("visible"));
            assertFalse(store().exists());
            assertEquals(400, call(agent, "device", "{\"displayName\":\"name\",\"visible\":false}", null).statusCode());
            assertEquals(400, call(agent, "device", "{\"displayName\":\"" + "x".repeat(33) + "\"}", null).statusCode());
            assertEquals(400, call(agent, "device", "null", null).statusCode());
            assertEquals(413, call(agent, "device", "x".repeat(4097), null).statusCode());
            assertEquals("My laptop", profile.load().displayName());
        }
    }
    @Test void newSettingsMutationsRequireLocalOrigin() throws Exception {
        try (var agent = new SetupAgent(0, store(), root, () -> NETWORKS)) {
            agent.start();
            for (String path : List.of("device", "network"))
                assertEquals(403, call(agent, path, "{}", "https://untrusted.example").statusCode());
            assertFalse(Files.exists(root.resolve("device-profile.json")));
            assertFalse(store().exists());
        }
    }
    @Test void networkRepairPreservesPasswordFilesPeersAndPorts() throws Exception {
        var store = store();
        try (var a = new ServerSocket(0); var b = new ServerSocket(0)) { store.save(saved(a.getLocalPort(), b.getLocalPort())); }
        var before = store.load();
        Path received = Path.of(before.peer().storageDirectory()).resolve("received.txt");
        Files.createDirectories(received.getParent()); Files.writeString(received, "keep this file");
        try (var agent = new SetupAgent(0, store, root, () -> NETWORKS)) {
            agent.start();
            assertEquals(400, call(agent, "network", "{\"address\":\"203.0.113.9\"}", null).statusCode());
            assertEquals(before, store.load());
            var response = call(agent, "network", "{\"address\":\"192.0.2.10\"}", null);
            assertEquals(202, response.statusCode()); assertFalse(response.body().contains(SECRET));
            var after = store.load();
            assertEquals("192.0.2.10", after.peer().advertisedAddress());
            assertEquals(SECRET, after.peer().discoverySecret());
            assertEquals(before.peer().storageDirectory(), after.peer().storageDirectory());
            assertEquals(before.peer().peers().get(1), after.peer().peers().get(1));
            assertEquals("192.0.2.10", after.peer().peers().getFirst().address());
            assertEquals(before.peer().port(), after.peer().port()); assertEquals(before.restPort(), after.restPort());
            assertEquals("keep this file", Files.readString(received));
            assertFalse(JSON.writeValueAsString(agent.status()).contains(SECRET));
        }
    }
    @Test void repairCannotSilentlyRejoinAfterLeaving() throws Exception {
        var store = store(); store.save(saved(5010, 6010)); store.setAutoJoin(false);
        try (var agent = new SetupAgent(0, store, root, () -> NETWORKS)) {
            agent.start();
            var response = call(agent, "network", "{\"address\":\"192.0.2.10\"}", null);
            assertEquals(400, response.statusCode()); assertTrue(response.body().contains("Join again"));
            assertFalse(store.autoJoin()); assertEquals("LEFT", agent.status().get("phase"));
            assertEquals("192.0.2.9", store.load().peer().advertisedAddress());
        }
    }
    @Test void manualSetupKeepsExistingStorageAndOtherPeerAddresses() throws Exception {
        var store = store(); store.save(saved(5010, 6010)); store.setAutoJoin(false);
        try (var agent = new SetupAgent(0, store, root, () -> NETWORKS);
             var a = new ServerSocket(0); var b = new ServerSocket(0)) {
            agent.start();
            int port = Math.min(a.getLocalPort(), 63535);
            a.close(); b.close();
            var candidate = agent.validate(new SetupAgent.Join("new-A", "my-mesh", SECRET, "192.0.2.10", port,
                    b.getLocalPort(), "239.255.42.99", 45555));
            assertEquals(store.load().peer().storageDirectory(), candidate.peer().storageDirectory());
            assertTrue(candidate.peer().peers().stream().anyMatch(p -> p.id().equals("B")));
            assertFalse(candidate.peer().peers().stream().anyMatch(p -> p.id().equals("A")));
        }
    }
}
