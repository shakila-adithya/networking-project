package storage.app;

import storage.config.PeerConfiguration;
import storage.config.SetupConfigStore;
import storage.web.SetupAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Three agents on one host: not a substitute for physical LAN/firewall testing. */
class SetupRuntimeIntegrationTest {
    @TempDir Path temporary;
    @Test void savedAgentsDiscoverTransferAndRestartThroughStableUiHost() throws Exception {
        var loopback = NetworkInterface.getByInetAddress(InetAddress.getByName("127.0.0.1"));
        Assumptions.assumeTrue(loopback != null && loopback.isUp() && loopback.supportsMulticast(), "Loopback multicast unavailable");
        String cluster = "setup-test-" + UUID.randomUUID();
        int udp;
        try (DatagramSocket socket = new DatagramSocket(0)) { udp = socket.getLocalPort(); }
        List<SetupAgent> agents = new ArrayList<>();
        var json = new ObjectMapper();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String id : List.of("A","B","C")) {
                var store = new SetupConfigStore(temporary.resolve(id + ".json"));
                int peer, rest;
                try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0)) {
                    peer = a.getLocalPort(); rest = b.getLocalPort();
                }
                store.save(new SetupConfigStore.Saved(new PeerConfiguration(id,"0.0.0.0","127.0.0.1",peer,
                        temporary.resolve("storage-"+id).toString(), List.of(new PeerConfiguration.PeerDefinition(id,"127.0.0.1",peer)),
                        cluster,true,"239.255.42.98",udp,"isolated-test-secret-123456"),rest));
                var agent = new SetupAgent(0,store,temporary,() -> List.of());
                agents.add(agent); agent.start();
            }
            for (SetupAgent agent : agents) awaitConnected(agent);
            SetupAgent c = agents.get(2);
            String base = "http://127.0.0.1:" + c.port();
            byte[] data = new byte[] {0, 2, -1, 100, 7};
            var upload = client.send(HttpRequest.newBuilder(URI.create(base+"/api/files"))
                    .header("X-File-Name","test.bin").POST(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, upload.statusCode());
            var uploaded = json.readTree(upload.body());
            assertFalse(uploaded.has("replicationStatus"));
            assertFalse(uploaded.has("replicaCount"));
            String fileId = uploaded.path("fileId").asText();
            var download = client.send(HttpRequest.newBuilder(URI.create(base+"/api/files/"+fileId+"/download")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(data,download.body());
            var restart = client.send(HttpRequest.newBuilder(URI.create(base+"/api/setup/restart"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,restart.statusCode());
            awaitConnected(c);
            var inventory = client.send(HttpRequest.newBuilder(URI.create(base+"/api/files")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,inventory.statusCode());
            assertTrue(inventory.body().contains(fileId));
        } finally { for (var agent : agents) agent.close(); }
    }
    private void awaitConnected(SetupAgent agent) throws Exception {
        long deadline = System.currentTimeMillis()+20_000;
        while (System.currentTimeMillis()<deadline) {
            var state = agent.status();
            if ("CONNECTED".equals(state.get("phase")) && ((List<?>)state.get("peers")).size() == 2) return;
            if ("ERROR".equals(state.get("phase"))) fail("Agent startup failed: " + state.get("error"));
            Thread.sleep(50);
        }
        fail("Three local agents did not discover and heartbeat before deadline: " + agent.status());
    }
}
