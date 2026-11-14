package storage.cluster;

import storage.files.FileMetadata;
import storage.files.HashUtils;
import storage.files.MetadataJson;
import storage.files.TransferResult;
import storage.transport.PeerClient;
import storage.transport.PeerServer;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicationManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalPutCreatesMatchingPrimaryAndReplicaOnDifferentPeers() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = peers(portA, portB, portC);
        Path storeA = temporaryDirectory.resolve("A");
        Path storeB = temporaryDirectory.resolve("B");
        Path storeC = temporaryDirectory.resolve("C");
        byte[] content = new byte[128 * 1024 + 19];
        new Random(31).nextBytes(content);
        Path source = temporaryDirectory.resolve("replicated.bin");
        Files.write(source, content);

        try (PeerServer serverA = server("A", portA, storeA, peers);
             PeerServer serverB = server("B", portB, storeB, peers);
             PeerServer serverC = server("C", portC, storeC, peers)) {
            serverA.start();
            serverB.start();
            serverC.start();

            TransferResult result = PeerClient.upload("127.0.0.1", portB, source);
            assertTrue(result.success(), result.message());
            assertEquals("OK", result.code());

            Path primaryContent = contentPath(storeB, result.fileId());
            Path replicaContent = contentPath(storeC, result.fileId());
            FileMetadata primaryMetadata = metadata(storeB, result.fileId());
            FileMetadata replicaMetadata = metadata(storeC, result.fileId());

            assertEquals(primaryMetadata, replicaMetadata);
            assertEquals(result.fileId(), primaryMetadata.fileId());
            assertEquals("replicated.bin", primaryMetadata.fileName());
            assertArrayEquals(content, Files.readAllBytes(primaryContent));
            assertArrayEquals(content, Files.readAllBytes(replicaContent));
            assertEquals(primaryMetadata.sha256(), HashUtils.sha256(primaryContent));
            assertEquals(replicaMetadata.sha256(), HashUtils.sha256(replicaContent));
            assertDirectoryEmpty(storeA.resolve("files"));
            assertDirectoryEmpty(storeA.resolve("metadata"));
        }
    }

    @Test
    void replicaPutDoesNotTriggerAnotherReplication() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = peers(portA, portB, portC);
        Path storeA = temporaryDirectory.resolve("loop-A");
        Path storeC = temporaryDirectory.resolve("loop-C");
        Path source = temporaryDirectory.resolve("replica-source.bin");
        byte[] content = new byte[] {10, 20, 30, 40, 50};
        Files.write(source, content);
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata(fileId, "replica-source.bin", content.length,
                HashUtils.sha256(source), Instant.now().toString());

        try (PeerServer serverA = server("A", portA, storeA, peers);
             PeerServer serverC = server("C", portC, storeC, peers)) {
            serverA.start();
            serverC.start();

            TransferResult result = PeerClient.uploadReplica(
                    "127.0.0.1", portC, metadata, source);
            assertTrue(result.success(), result.message());
            assertTrue(Files.isRegularFile(contentPath(storeC, fileId)));
            assertDirectoryEmpty(storeA.resolve("files"));
            assertDirectoryEmpty(storeA.resolve("metadata"));
        }
    }

    @Test
    void unavailableReplicaIsReportedWhilePrimaryIsRetained() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int unavailablePortC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = peers(portA, portB, unavailablePortC);
        Path storeB = temporaryDirectory.resolve("unavailable-B");
        Path source = temporaryDirectory.resolve("primary-only.bin");
        byte[] content = new byte[] {1, 3, 5, 7, 9};
        Files.write(source, content);

        try (PeerServer serverB = server("B", portB, storeB, peers)) {
            serverB.start();
            TransferResult result = PeerClient.upload("127.0.0.1", portB, source);

            assertTrue(result.success());
            assertEquals("REPLICATION_FAILED", result.code());
            assertTrue(result.message().contains("replication failed"));
            assertArrayEquals(content, Files.readAllBytes(contentPath(storeB, result.fileId())));
            assertTrue(Files.isRegularFile(metadataPath(storeB, result.fileId())));
        }
    }

    @Test
    void duplicateReplicaUuidDoesNotOverwriteEitherCopy() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        List<ReplicationManager.PeerEndpoint> peers = peers(portA, portB, portC);
        Path storeB = temporaryDirectory.resolve("duplicate-B");
        Path storeC = temporaryDirectory.resolve("duplicate-C");
        UUID fileId = UUID.randomUUID();
        Path existingSource = temporaryDirectory.resolve("existing-replica.bin");
        Path newPrimarySource = temporaryDirectory.resolve("new-primary.bin");
        byte[] existingContent = new byte[] {8, 8, 8};
        byte[] newContent = new byte[] {9, 9, 9, 9};
        Files.write(existingSource, existingContent);
        Files.write(newPrimarySource, newContent);
        FileMetadata existingMetadata = new FileMetadata(fileId, "existing-replica.bin",
                existingContent.length, HashUtils.sha256(existingSource), Instant.now().toString());

        try (PeerServer serverB = server("B", portB, storeB, peers);
             PeerServer serverC = new PeerServer("127.0.0.1", portC, storeC)) {
            serverB.start();
            serverC.start();
            TransferResult initialReplica = PeerClient.uploadReplica(
                    "127.0.0.1", portC, existingMetadata, existingSource);
            assertTrue(initialReplica.success());

            TransferResult primary = PeerClient.upload(
                    "127.0.0.1", portB, newPrimarySource, fileId);
            assertTrue(primary.success());
            assertEquals("REPLICATION_FAILED", primary.code());
            assertTrue(primary.message().contains("DUPLICATE_FILE_ID"));
            assertArrayEquals(newContent, Files.readAllBytes(contentPath(storeB, fileId)));
            assertArrayEquals(existingContent, Files.readAllBytes(contentPath(storeC, fileId)));
            assertEquals(existingMetadata, metadata(storeC, fileId));
        }
    }

    private static List<ReplicationManager.PeerEndpoint> peers(int portA, int portB, int portC) {
        return List.of(
                new ReplicationManager.PeerEndpoint("A", "127.0.0.1", portA),
                new ReplicationManager.PeerEndpoint("B", "127.0.0.1", portB),
                new ReplicationManager.PeerEndpoint("C", "127.0.0.1", portC));
    }

    private static PeerServer server(String name, int port, Path storage,
                                     List<ReplicationManager.PeerEndpoint> peers) throws IOException {
        return new PeerServer("127.0.0.1", port, storage,
                new ReplicationManager(name, peers));
    }

    private static FileMetadata metadata(Path store, UUID fileId) throws Exception {
        return MetadataJson.decode(Files.readAllBytes(metadataPath(store, fileId)));
    }

    private static Path contentPath(Path store, UUID fileId) {
        return store.resolve("files").resolve(fileId + ".bin");
    }

    private static Path metadataPath(Path store, UUID fileId) {
        return store.resolve("metadata").resolve(fileId + ".json");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory));
        try (var entries = Files.list(directory)) {
            assertFalse(entries.findAny().isPresent(), "expected empty directory: " + directory);
        }
    }
}
