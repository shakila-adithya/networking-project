package storage.web;

import storage.cluster.ReplicationManager;
import storage.cluster.SelfHealingManager;
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
        List<ReplicationManager.PeerEndpoint> peers;
        try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0); ServerSocket c = new ServerSocket(0)) {
            peers = List.of(new ReplicationManager.PeerEndpoint("A","127.0.0.1",a.getLocalPort()),
                    new ReplicationManager.PeerEndpoint("B","127.0.0.1",b.getLocalPort()),
                    new ReplicationManager.PeerEndpoint("C","127.0.0.1",c.getLocalPort()));
        }
        var replicationB = new ReplicationManager("B",peers);
        try (var a = new PeerServer("127.0.0.1",peers.get(0).port(),root.resolve("A"),new ReplicationManager("A",peers));
             var b = new PeerServer("127.0.0.1",peers.get(1).port(),root.resolve("B"),replicationB);
             var c = new PeerServer("127.0.0.1",peers.get(2).port(),root.resolve("C"),new ReplicationManager("C",peers));
             var heartbeat = new HeartbeatManager("B",peers);
             var api = new RestApiServer("127.0.0.1",0,"B","127.0.0.1",peers.get(1).port(),b.fileStore(),heartbeat,replicationB);
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
            // Policy survives disk reload and prevents repair from creating an unselected replica.
            assertEquals(List.of("C"),new FileStore(root.resolve("C")).open(selectedId).metadata().recipients());
            try (var hc = new HeartbeatManager("C",peers);
                 var healing = new SelfHealingManager("C",c.fileStore(),new ReplicationManager("C",peers),hc)) {
                hc.checkNow(); healing.checkNow();
                assertTrue(healing.status().activeRepairs().isEmpty());
                assertTrue(a.inventory().isEmpty()); assertTrue(b.inventory().isEmpty());
            }
            var stored = c.fileStore().open(selectedId);
            var refused = PeerClient.uploadReplica("127.0.0.1",a.port(),stored.metadata(),stored.contentPath());
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
