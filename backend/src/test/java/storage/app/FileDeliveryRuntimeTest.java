package storage.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import storage.config.PeerConfiguration;
import storage.config.SetupConfigStore;
import storage.files.FileStore;
import storage.transport.PeerClient;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real running peer services must never redistribute user files in the background. */
@Timeout(45)
class FileDeliveryRuntimeTest {
    @TempDir Path root;

    @Test void filesStayOnTheReceivingPeerAcrossDisconnectAndRestart() throws Exception {
        String previous = System.getProperty("mesh.transport");
        System.setProperty("mesh.transport", "tcp");
        List<ManagedPeer> running = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            List<SetupConfigStore.Saved> configurations = configurations(3);
            for (var saved : configurations) running.add(new ManagedPeer(saved));
            Path source = root.resolve("notes with spaces.txt");
            Files.writeString(source, "These notes belong to the recipient only.\n");
            var b = configurations.get(1);
            var upload = PeerClient.authenticated(security(), () -> PeerClient.upload("127.0.0.1", b.peer().port(), source));
            assertTrue(upload.success(), upload.message());
            assertOnlyBHas(configurations, upload.fileId());

            // No-recipient REST uploads are local saves, with no replication response fields.
            String base = "http://127.0.0.1:" + b.restPort() + "/api/files";
            var response = client.send(HttpRequest.newBuilder(URI.create(base)).header("X-File-Name", "local.txt")
                    .POST(HttpRequest.BodyPublishers.ofString("local save")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            var body = new ObjectMapper().readTree(response.body());
            assertFalse(body.has("replicaCount"));
            assertFalse(body.has("replicationStatus"));
            UUID localId = UUID.fromString(body.path("fileId").asText());
            assertOnlyBHas(configurations, localId);

            running.get(1).close();
            // Observe beyond the former 5s repair and 10s reconciliation intervals.
            // This is an observation window, not a readiness or synchronization mechanism.
            Thread.sleep(11_000);
            for (int i : List.of(0, 2)) {
                assertTrue(new FileStore(configurations.get(i).peer().storagePath()).inventory().isEmpty());
            }
            for (var peer : running) peer.close();
            running.clear();
            for (var saved : configurations) running.add(new ManagedPeer(saved));
            assertOnlyBHas(configurations, upload.fileId());
            assertOnlyBHas(configurations, localId);
            Path downloaded = root.resolve("download.txt");
            assertTrue(PeerClient.authenticated(security(), () -> PeerClient.download("127.0.0.1", b.peer().port(), upload.fileId(), downloaded)).success());
            assertEquals(-1, Files.mismatch(source, downloaded));
            var inventory = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, inventory.statusCode());
            assertFalse(inventory.body().contains("replicaCount"));
        } finally {
            for (var peer : running) peer.close();
            if (previous == null) System.clearProperty("mesh.transport");
            else System.setProperty("mesh.transport", previous);
        }
    }

    @Test void singleStaticPeerStartsAndAcceptsLocalFiles() throws Exception {
        String previous = System.getProperty("mesh.transport");
        System.setProperty("mesh.transport", "tcp");
        try {
            var saved = configurations(1).getFirst();
            try (var peer = new ManagedPeer(saved)) {
                Path source = root.resolve("standalone.txt");
                Files.writeString(source, "single peer");
                assertTrue(PeerClient.authenticated(security(), () -> PeerClient.upload("127.0.0.1", saved.peer().port(), source)).success());
            }
        } finally {
            if (previous == null) System.clearProperty("mesh.transport");
            else System.setProperty("mesh.transport", previous);
        }
    }

    private storage.security.MeshSecurity security() {
        return new storage.security.MeshSecurity("delivery-test", "test-only-delivery-password");
    }

    private void assertOnlyBHas(List<SetupConfigStore.Saved> configurations, UUID id) throws Exception {
        for (int i = 0; i < configurations.size(); i++) {
            var store = new FileStore(configurations.get(i).peer().storagePath());
            if (i == 1) {
                var metadata = store.open(id).metadata();
                assertNull(metadata.recipients(), "legacy metadata without recipients stays readable");
                assertTrue(store.verify(id, metadata));
            } else assertTrue(store.inventory().isEmpty(), "unselected peer must receive no copies");
        }
    }

    private List<SetupConfigStore.Saved> configurations(int count) throws Exception {
        List<ServerSocket> reservations = new ArrayList<>();
        try {
            List<PeerConfiguration.PeerDefinition> peers = new ArrayList<>();
            List<Integer> restPorts = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                while (true) {
                    ServerSocket peer = new ServerSocket(0);
                    if (peer.getLocalPort() > 63_535) { peer.close(); continue; }
                    try {
                        ServerSocket mesh = new ServerSocket(peer.getLocalPort() + 2000);
                        reservations.add(peer); reservations.add(mesh);
                        peers.add(new PeerConfiguration.PeerDefinition("P" + i, "127.0.0.1", peer.getLocalPort()));
                        break;
                    } catch (java.io.IOException occupied) { peer.close(); }
                }
                ServerSocket rest = new ServerSocket(0);
                reservations.add(rest); restPorts.add(rest.getLocalPort());
            }
            List<SetupConfigStore.Saved> saved = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var peer = peers.get(i);
                saved.add(new SetupConfigStore.Saved(new PeerConfiguration(peer.id(), "127.0.0.1", "127.0.0.1", peer.port(),
                        root.resolve(peer.id()).toString(), peers, "delivery-test", false, "239.255.42.99", 45555, "test-only-delivery-password"), restPorts.get(i)));
            }
            return saved;
        } finally {
            for (var socket : reservations) socket.close();
        }
    }
}
