package storage.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import storage.cluster.PeerEndpoint;
import storage.cluster.PeerRegistry;
import storage.files.FileStore;
import storage.link.HeartbeatManager;
import storage.messaging.ChatService;
import storage.messaging.MeshNode;
import storage.routing.AodvRouter;
import storage.routing.LinkCost;
import storage.transfer.MeshFileService;
import storage.transfer.TransferConfig;
import storage.transport.InMemoryTransport;

/** The device, nearby, scan and connect endpoints, with a stub behind {@link DeviceApi}. */
class DeviceApiRestTest {
    @TempDir
    Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) closeables.get(i).close();
    }

    /** Records what the REST layer asked for. */
    private static final class StubDevice implements DeviceApi {
        String name = "";
        boolean visible;
        boolean bluetoothOn = true;
        final List<String> connected = new ArrayList<>();
        String connectFailure;

        @Override
        public Map<String, Object> deviceStatus() {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("deviceId", "C");
            status.put("displayName", name.isEmpty() ? "C" : name);
            status.put("customName", name);
            status.put("visible", visible);
            return status;
        }

        @Override
        public Map<String, Object> updateDevice(String displayName, Boolean visibleValue) {
            if (displayName != null) {
                if (displayName.length() > 32) throw new IllegalArgumentException("The device name can be at most 32 characters.");
                name = displayName;
            }
            if (visibleValue != null) visible = visibleValue;
            return deviceStatus();
        }

        @Override
        public Map<String, Object> nearby() {
            return Map.of("scanning", false, "items", List.of(Map.of("kind", "bluetooth", "deviceId", "B", "state", "AVAILABLE")));
        }

        @Override
        public boolean scanNow() {
            return bluetoothOn;
        }

        @Override
        public void connectDevice(String deviceId) throws IOException {
            if (connectFailure != null) throw new IOException(connectFailure);
            connected.add(deviceId);
        }
    }

    private RestApiServer api(DeviceApi device) throws Exception {
        InMemoryTransport.Network net = new InMemoryTransport.Network();
        AodvRouter router = new AodvRouter(LinkCost.HOP_COUNT, Duration.ofMillis(300), 3, Duration.ofSeconds(60));
        MeshNode node = new MeshNode("C", net.node("C"), router);
        node.start();
        FileStore store = new FileStore(root.resolve("store"));
        MeshFileService files = new MeshFileService(node, store, TransferConfig.defaults());
        ChatService chat = new ChatService(node, 2, Duration.ofMillis(300));
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<PeerEndpoint> peers = List.of(new PeerEndpoint("C", "127.0.0.1", port));
        HeartbeatManager heartbeat = new HeartbeatManager("C", peers);
        RestApiServer server = new RestApiServer("127.0.0.1", 0, "C", "127.0.0.1", port, store, heartbeat,
                new PeerRegistry("C", peers));
        closeables.add(node);
        closeables.add(files);
        closeables.add(chat);
        closeables.add(heartbeat);
        closeables.add(server);
        server.attachMesh(node, chat, "memory", router::routes, files);
        if (device != null) server.attachDevice(device);
        server.start();
        return server;
    }

    private HttpResponse<String> call(RestApiServer api, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deviceNameAndVisibilityAreReadAndChanged() throws Exception {
        StubDevice device = new StubDevice();
        RestApiServer api = api(device);
        JsonNode before = json.readTree(call(api, "GET", "/api/device", null).body());
        assertEquals("C", before.path("displayName").asText());
        assertFalse(before.path("visible").asBoolean());

        HttpResponse<String> renamed = call(api, "POST", "/api/device", "{\"displayName\":\"Maya's laptop\"}");
        assertEquals(200, renamed.statusCode());
        assertEquals("Maya's laptop", json.readTree(renamed.body()).path("displayName").asText());
        assertFalse(device.visible, "renaming alone does not change visibility");

        HttpResponse<String> shown = call(api, "POST", "/api/device", "{\"visible\":true}");
        assertTrue(json.readTree(shown.body()).path("visible").asBoolean());
        assertEquals("Maya's laptop", device.name, "changing visibility alone keeps the name");
    }

    @Test
    void invalidDeviceRequestsAreRejectedWithAReadableReason() throws Exception {
        StubDevice device = new StubDevice();
        RestApiServer api = api(device);
        HttpResponse<String> tooLong = call(api, "POST", "/api/device", "{\"displayName\":\"" + "x".repeat(40) + "\"}");
        assertEquals(400, tooLong.statusCode());
        assertTrue(tooLong.body().contains("at most 32"), tooLong.body());
        assertEquals("", device.name, "nothing changed");
        assertEquals(400, call(api, "POST", "/api/device", "{\"displayName\":42}").statusCode());
        assertEquals(400, call(api, "POST", "/api/device", "{\"visible\":\"yes\"}").statusCode());
        assertEquals(404, call(api, "GET", "/api/device/other", null).statusCode());
    }

    @Test
    void scanNearbyAndConnectByDeviceId() throws Exception {
        StubDevice device = new StubDevice();
        RestApiServer api = api(device);
        assertEquals("B", json.readTree(call(api, "GET", "/api/nearby", null).body()).path("items").get(0).path("deviceId").asText());

        assertEquals(202, call(api, "POST", "/api/bluetooth/scan", "").statusCode());
        device.bluetoothOn = false;
        HttpResponse<String> off = call(api, "POST", "/api/bluetooth/scan", "");
        assertEquals(409, off.statusCode());
        assertTrue(off.body().contains("BLUETOOTH_OFF"));

        assertEquals(202, call(api, "POST", "/api/mesh/connect", "{\"deviceId\":\"B\"}").statusCode());
        assertEquals(List.of("B"), device.connected);
        device.connectFailure = "cannot connect to AA:BB: the device refused the connection";
        HttpResponse<String> failed = call(api, "POST", "/api/mesh/connect", "{\"deviceId\":\"B\"}");
        assertEquals(502, failed.statusCode());
        assertTrue(failed.body().contains("refused the connection"), failed.body());
        assertEquals(400, call(api, "POST", "/api/mesh/connect", "{}").statusCode(), "needs an address or a device id");
    }

    @Test
    void withoutADeviceServiceTheEndpointsAnswerUnavailable() throws Exception {
        RestApiServer api = api(null);
        assertEquals(503, call(api, "GET", "/api/device", null).statusCode());
        assertEquals(503, call(api, "GET", "/api/nearby", null).statusCode());
        assertEquals(503, call(api, "POST", "/api/bluetooth/scan", "").statusCode());
    }

    @Test
    void meshStatusCarriesTheFriendlyNames() throws Exception {
        RestApiServer api = api(new StubDevice());
        JsonNode mesh = json.readTree(call(api, "GET", "/api/mesh", null).body());
        assertEquals("C", mesh.path("deviceName").asText(), "no name chosen: the id");
        assertTrue(mesh.has("names"));
    }
    @Test void rejectsCrossOriginDeviceChangesBeforeCallingService() throws Exception {
        StubDevice device = new StubDevice();
        RestApiServer api = api(device);
        for (String path : List.of("/api/device", "/api/bluetooth/scan", "/api/mesh/connect")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                    .header("Origin", "https://attacker.example")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals("", device.name);
        assertTrue(device.connected.isEmpty());
    }
}
