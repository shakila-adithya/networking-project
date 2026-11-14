package storage.web;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import storage.config.*;
import storage.discovery.MeshDirectory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(30)
class VisibilityDiscoveryTest {
    @TempDir Path root;
    String previous;
    final ObjectMapper json = new ObjectMapper();
    @BeforeEach void disableRealRadioChanges() { previous = System.getProperty("mesh.transport"); System.setProperty("mesh.transport", "tcp"); }
    @AfterEach void restore() { if (previous == null) System.clearProperty("mesh.transport"); else System.setProperty("mesh.transport", previous); }
    HttpResponse<String> call(SetupAgent agent, String path, String body, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/" + path));
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) request.header("Origin", origin);
        try (var client = HttpClient.newHttpClient()) { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    @Test void visibilityBeforeJoiningUsesStableIdentityAndHidingPersistsLeave() throws Exception {
        var store = new SetupConfigStore(root.resolve("setup.json")); String identity;
        try (var agent = new SetupAgent(0, store, root, () -> List.of(new LocalNetworks.Candidate("127.0.0.1", "Test loopback", true)))) {
            agent.start();
            var unjoined = call(agent, "health", null, null);
            assertEquals(503, unjoined.statusCode());
            assertEquals("MESH_NOT_JOINED", json.readTree(unjoined.body()).path("code").asText());
            assertEquals(200, call(agent, "visibility", "{\"visible\":true}", null).statusCode());
            identity = json.readTree(call(agent, "visibility", null, null).body()).path("deviceId").asText();
            assertFalse(identity.isBlank()); assertFalse(store.exists());
            assertEquals(403, call(agent, "visibility", "{\"visible\":false}", "https://untrusted.example").statusCode());
            assertEquals(400, call(agent, "visibility", "{\"visible\":\"true\"}", null).statusCode());
            assertEquals(202, call(agent, "membership/create", "{\"meshId\":\"visible-test\",\"password\":\"visibility-test-password\",\"localAddress\":\"127.0.0.1\"}", null).statusCode());
            long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (agent.status().get("phase").equals("STARTING") && System.nanoTime() < until) Thread.sleep(20);
            assertNotEquals("ERROR", agent.status().get("phase"));
            assertEquals(identity, store.load().peer().localPeerId());
            try (var client = HttpClient.newHttpClient()) {
                var internal = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + store.load().restPort() + "/api/device"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"visible\":false}")).build();
                assertEquals(409, client.send(internal, HttpResponse.BodyHandlers.ofString()).statusCode(), "Internal API must not bypass leave semantics");
            }
            assertEquals(200, call(agent, "visibility", "{\"visible\":false}", null).statusCode());
            assertEquals("LEFT", agent.status().get("phase")); assertFalse(store.autoJoin());
            assertFalse(json.readTree(call(agent, "visibility", null, null).body()).path("visible").asBoolean());
            assertEquals(200, call(agent, "visibility", "{\"visible\":true}", null).statusCode());
            assertEquals("LEFT", agent.status().get("phase"), "Visible again must not silently rejoin");
        }
        try (var agent = new SetupAgent(0, store, root, List::of)) {
            agent.start(); assertEquals("LEFT", agent.status().get("phase"));
            assertEquals(identity, json.readTree(call(agent, "visibility", null, null).body()).path("deviceId").asText());
        }
    }
    @Test void visibleUnjoinedDeviceBeaconsAreUnverifiedBoundedAndWithdrawable() throws Exception {
        try (var directory = new MeshDirectory(() -> null, endpoint -> fail("Unjoined beacons must not create membership"))) {
            var d = new MeshDirectory.DeviceAdvertisement("Relay-device-v3", "Nearby", "Nearby laptop", "", 0, true);
            assertTrue(directory.accept(json.writeValueAsBytes(d), InetAddress.getLoopbackAddress()));
            assertEquals(1, directory.devices().size()); assertTrue(directory.nearby().isEmpty()); assertFalse(directory.devices().getFirst().verified());
            assertTrue(directory.accept(json.writeValueAsBytes(new MeshDirectory.DeviceAdvertisement("Relay-device-v3", "Nearby", "Nearby laptop", "", 0, false)), InetAddress.getLoopbackAddress()));
            assertTrue(directory.devices().isEmpty());
            assertFalse(directory.accept(json.writeValueAsBytes(new MeshDirectory.DeviceAdvertisement("Relay-device-v3", "Bad/id", "name", "", 0, true)), InetAddress.getLoopbackAddress()));
        }
    }
    @Test void storagePolicyIsLocalOnlyBoundedAndSurvivesRestartWithoutPasswords() throws Exception {
        var store = new SetupConfigStore(root.resolve("setup.json"));
        try (var agent = new SetupAgent(0, store, root, List::of)) {
            agent.start();
            assertEquals(1_000_000_000L, json.readTree(call(agent, "relay-storage", null, null).body()).path("policy").path("quotaBytes").asLong());
            assertEquals(403, call(agent, "relay-storage", "{}", "https://foreign.example").statusCode());
            assertEquals(200, call(agent, "relay-storage", "{\"quotaBytes\":256000000,\"retentionHours\":6}", null).statusCode());
            assertNotEquals(200, call(agent, "relay-storage", "{\"quotaBytes\":-1,\"retentionHours\":0}", null).statusCode());
        }
        try (var agent = new SetupAgent(0, store, root, List::of)) {
            agent.start(); var body = json.readTree(call(agent, "relay-storage", null, null).body());
            assertEquals(256000000, body.path("policy").path("quotaBytes").asLong()); assertEquals(6, body.path("policy").path("retentionHours").asInt());
            assertFalse(body.toString().contains("password")); assertFalse(body.path("status").path("available").asBoolean());
        }
    }
}
