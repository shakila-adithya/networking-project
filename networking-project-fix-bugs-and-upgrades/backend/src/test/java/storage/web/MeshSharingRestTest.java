package storage.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import storage.cluster.ReplicationManager;
import storage.files.FileStore;
import storage.link.HeartbeatManager;
import storage.messaging.ChatService;
import storage.messaging.MeshNode;
import storage.routing.AodvRouter;
import storage.routing.LinkCost;
import storage.transfer.MeshFileService;
import storage.transfer.TransferConfig;
import storage.transport.InMemoryTransport;

/** File sharing through the REST API to devices that are only reachable over the mesh. */
@Timeout(60)
class MeshSharingRestTest {
    @TempDir
    Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();

    @AfterEach
    void cleanUp() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) closeables.get(i).close();
    }

    private record Device(MeshNode node, AodvRouter router, FileStore store, MeshFileService files, ChatService chat) { }

    private Device device(String id) throws Exception {
        AodvRouter router = new AodvRouter(LinkCost.HOP_COUNT, Duration.ofMillis(300), 3, Duration.ofSeconds(60));
        MeshNode node = new MeshNode(id, net.node(id), router);
        node.start();
        FileStore store = new FileStore(root.resolve("store-" + id));
        MeshFileService files = new MeshFileService(node, store, new TransferConfig(4096, 8,
                Duration.ofMillis(300), Duration.ofSeconds(10), Duration.ZERO, Duration.ofMillis(300)));
        ChatService chat = new ChatService(node, 3, Duration.ofMillis(300));
        closeables.add(node);
        closeables.add(files);
        closeables.add(chat);
        return new Device(node, router, store, files, chat);
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private RestApiServer api(Device a, List<ReplicationManager.PeerEndpoint> peers) throws Exception {
        ReplicationManager replication = new ReplicationManager("A", peers);
        HeartbeatManager heartbeat = new HeartbeatManager("A", peers);
        RestApiServer api = new RestApiServer("127.0.0.1", 0, "A", "127.0.0.1", peers.get(0).port(),
                a.store(), heartbeat, replication);
        closeables.add(heartbeat);
        closeables.add(api);
        api.attachMesh(a.node(), a.chat(), "memory", a.router()::routes, a.files());
        api.start();
        return api;
    }

    private void connect(Device a, String aId, Device b, String bId) throws Exception {
        net.setInRange(aId, bId, true);
        a.node().connectTo(bId);
        await(() -> a.node().neighbourIds().contains(bId) && b.node().neighbourIds().contains(aId));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached in time");
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> post(RestApiServer api, String recipient, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                        + "/api/files?recipient=" + recipient)).header("X-File-Name", "photo.bin")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(RestApiServer api, String path) throws Exception {
        return json.readTree(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private void awaitDelivered(RestApiServer api, String transferId) throws Exception {
        await(() -> {
            try {
                return "DELIVERED".equals(get(api, "/api/mesh/transfers/" + transferId).path("state").asText());
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void meshOnlyRecipientReceivesTheFileAndProgressIsReported() throws Exception {
        Device a = device("A"), b = device("B"), c = device("C");
        connect(a, "A", b, "B");
        connect(b, "B", c, "C");
        RestApiServer api = api(a, List.of(new ReplicationManager.PeerEndpoint("A", "127.0.0.1", closedPort())));
        byte[] content = new byte[200_000];
        new java.util.Random(7).nextBytes(content);

        // C is two hops away and not a registered storage peer; only the mesh knows it after a chat.
        a.chat().send("C", "hello");
        await(() -> a.router().routes().stream().anyMatch(r -> r.destination().equals("C") && r.valid()));

        HttpResponse<String> response = post(api, "C", content);
        assertEquals(202, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertEquals("TRANSFERRING", body.path("shareStatus").asText());
        assertEquals("C", body.path("transfers").get(0).path("peer").asText());
        String transferId = body.path("transfers").get(0).path("transferId").asText();

        awaitDelivered(api, transferId);
        JsonNode done = get(api, "/api/mesh/transfers/" + transferId);
        assertEquals(100, done.path("percent").asInt());
        assertEquals("photo.bin", done.path("fileName").asText());
        UUID fileId = UUID.fromString(body.path("fileId").asText());
        assertArrayEquals(content, Files.readAllBytes(c.store().open(fileId).contentPath()));
        assertEquals(List.of("C"), c.store().open(fileId).metadata().recipients());
        assertEquals(1, get(api, "/api/mesh/transfers").path("transfers").size());
        assertEquals(409, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                + "/api/mesh/transfers/" + transferId + "/resume")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "a delivered transfer cannot be resumed");
    }

    @Test
    void deadDirectConnectionFallsBackToTheMesh() throws Exception {
        Device a = device("A"), b = device("B");
        connect(a, "A", b, "B");
        // B is a registered storage peer, but its TCP port is closed: the direct upload fails.
        RestApiServer api = api(a, List.of(new ReplicationManager.PeerEndpoint("A", "127.0.0.1", closedPort()),
                new ReplicationManager.PeerEndpoint("B", "127.0.0.1", closedPort())));
        byte[] content = "routed because direct failed".getBytes();
        HttpResponse<String> response = post(api, "B", content);
        assertEquals(202, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertEquals(0, body.path("failedPeers").size());
        awaitDelivered(api, body.path("transfers").get(0).path("transferId").asText());
        assertArrayEquals(content, Files.readAllBytes(
                b.store().open(UUID.fromString(body.path("fileId").asText())).contentPath()));
    }

    @Test
    void deviceTheMeshDoesNotKnowIsStillRejected() throws Exception {
        Device a = device("A"), b = device("B");
        connect(a, "A", b, "B");
        RestApiServer api = api(a, List.of(new ReplicationManager.PeerEndpoint("A", "127.0.0.1", closedPort())));
        assertEquals(400, post(api, "NOBODY", new byte[] {1}).statusCode());
        assertEquals(404, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                + "/api/mesh/transfers/" + UUID.randomUUID())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
