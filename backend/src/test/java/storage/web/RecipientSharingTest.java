package storage.web;

import storage.cluster.PeerEndpoint;
import storage.cluster.PeerRegistry;
import storage.files.FileStore;
import storage.link.HeartbeatManager;
import storage.transport.PeerClient;
import storage.transport.PeerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class RecipientSharingTest {
    @TempDir Path root;
    @Test void chosenRecipientOnlyAndAllDeliveryRespectSharingPolicy() throws Exception {
        List<PeerEndpoint> peers;
        try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0); ServerSocket c = new ServerSocket(0)) {
            peers = List.of(new PeerEndpoint("A","127.0.0.1",a.getLocalPort()),
                    new PeerEndpoint("B","127.0.0.1",b.getLocalPort()),
                    new PeerEndpoint("C","127.0.0.1",c.getLocalPort()));
        }
        var registryB = new PeerRegistry("B",peers);
        try (var a = new PeerServer("127.0.0.1",peers.get(0).port(),root.resolve("A"),"A");
             var b = new PeerServer("127.0.0.1",peers.get(1).port(),root.resolve("B"),"B");
             var c = new PeerServer("127.0.0.1",peers.get(2).port(),root.resolve("C"),"C");
             var heartbeat = new HeartbeatManager("B",peers);
             var api = new RestApiServer("127.0.0.1",0,"B","127.0.0.1",peers.get(1).port(),b.fileStore(),heartbeat,registryB);
             var client = HttpClient.newHttpClient()) {
            a.start(); b.start(); c.start(); heartbeat.checkNow(); api.start();
            String url = "http://127.0.0.1:"+api.port()+"/api/files?recipient=";
            var json = new ObjectMapper();
            var selected = post(client,url+"C");
            assertEquals(201,selected.statusCode());
            UUID selectedId = UUID.fromString(json.readTree(selected.body()).path("fileId").asText());
            assertTrue(a.inventory().isEmpty()); assertTrue(b.inventory().isEmpty());
            assertEquals(List.of("C"),c.inventory().getFirst().recipients());
            assertEquals(selectedId,c.inventory().getFirst().fileId());
            // Recipient metadata remains readable after reopening the existing store.
            assertEquals(List.of("C"),new FileStore(root.resolve("C")).open(selectedId).metadata().recipients());
            var stored = c.fileStore().open(selectedId);
            var refused = PeerClient.deliver("127.0.0.1",a.port(),stored.metadata(),stored.contentPath());
            assertFalse(refused.success()); assertEquals("NOT_A_RECIPIENT",refused.code());
            var all = post(client,url+"all");
            assertEquals(201,all.statusCode());
            var allBody = json.readTree(all.body());
            assertEquals(3,allBody.path("deliveredTo").size());
            UUID allId = UUID.fromString(allBody.path("fileId").asText());
            for (var server : List.of(a,b,c)) {
                var metadata = server.fileStore().open(allId).metadata();
                assertEquals(List.of("A","B","C"),metadata.recipients());
                assertTrue(server.fileStore().verify(allId,metadata));
            }
            // Each recipient owns its copy: deleting locally must not contact the others.
            var deleted = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                    + "/api/files/" + allId)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, deleted.statusCode());
            assertThrows(FileStore.StorageException.class, () -> b.fileStore().open(allId));
            assertTrue(a.fileStore().verify(allId, a.fileStore().open(allId).metadata()));
            assertTrue(c.fileStore().verify(allId, c.fileStore().open(allId).metadata()));
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                    + "/api/files/" + selectedId)).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(selectedId, c.fileStore().open(selectedId).metadata().fileId());
            int before = b.inventory().size();
            assertEquals(400,post(client,url+"UNKNOWN").statusCode());
            assertEquals(before,b.inventory().size());
            a.close(); // Stale ALIVE membership: report actual delivery failure, never claim all delivered.
            var partial = post(client,url+"all");
            assertEquals(207,partial.statusCode());
            assertTrue(json.readTree(partial.body()).path("failedPeers").has("A"));
            assertEquals("INCOMPLETE",json.readTree(partial.body()).path("shareStatus").asText());
        }
    }
    private HttpResponse<String> post(HttpClient client,String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("X-File-Name","share.txt")
                .POST(HttpRequest.BodyPublishers.ofString("recipient sharing test")).build(),HttpResponse.BodyHandlers.ofString());
    }
}
