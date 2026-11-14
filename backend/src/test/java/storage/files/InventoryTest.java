package storage.files;

import storage.transport.PeerClient;
import storage.transport.PeerServer;
import storage.transport.Protocol;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emptyLocalInventoryIsEmpty() throws IOException {
        FileStore store = new FileStore(temporaryDirectory.resolve("empty"));
        assertTrue(store.inventory().isEmpty());
    }

    @Test
    void localInventoryContainsMultipleStoredFiles() throws Exception {
        Path storePath = temporaryDirectory.resolve("multiple");
        FileStore store = new FileStore(storePath);
        storeFile(store, "one.txt", new byte[] {1, 2, 3});
        storeFile(store, "two.txt", new byte[] {4, 5});

        List<FileMetadata> inventory = store.inventory();
        assertEquals(2, inventory.size());
        assertEquals(List.of("one.txt", "two.txt"), inventory.stream()
                .map(FileMetadata::fileName).sorted().toList());
    }

    @Test
    void listFilesReturnsExistingMetadata() throws Exception {
        int port = freePort();
        try (PeerServer server = new PeerServer("127.0.0.1", port, temporaryDirectory.resolve("peer"))) {
            server.start();
            Path source = temporaryDirectory.resolve("report.txt");
            Files.writeString(source, "inventory test");
            TransferResult stored = PeerClient.upload("127.0.0.1", port, source);

            List<FileMetadata> inventory = PeerClient.listFiles("127.0.0.1", port);
            assertEquals(1, inventory.size());
            assertEquals(stored.fileId(), inventory.getFirst().fileId());
            assertEquals(Files.size(source), inventory.getFirst().size());
            assertEquals(HashUtils.sha256(source), inventory.getFirst().sha256());
        }
    }

    @Test
    void matchingUuidAcrossTwoPeersIsRecognizedAsCopies() throws Exception {
        int firstPort = freePort();
        int secondPort = freePort();
        Path source = temporaryDirectory.resolve("shared.txt");
        Files.writeString(source, "same shared-file metadata");
        UUID fileId = UUID.randomUUID();
        try (PeerServer first = new PeerServer("127.0.0.1", firstPort,
                    temporaryDirectory.resolve("first"));
             PeerServer second = new PeerServer("127.0.0.1", secondPort,
                    temporaryDirectory.resolve("second"))) {
            first.start();
            second.start();
            assertTrue(PeerClient.upload("127.0.0.1", firstPort, source, fileId).success());
            FileMetadata sharedMetadata = PeerClient.listFiles("127.0.0.1", firstPort).getFirst();
            assertTrue(PeerClient.deliver("127.0.0.1", secondPort, sharedMetadata, source).success());

            List<InventoryComparator.FileCopies> copies = InventoryComparator.compare(List.of(
                    InventoryComparator.SourceInventory.available("A",
                            PeerClient.listFiles("127.0.0.1", firstPort)),
                    InventoryComparator.SourceInventory.available("B",
                            PeerClient.listFiles("127.0.0.1", secondPort))));
            assertEquals(1, copies.size());
            assertEquals(2, copies.getFirst().copies().size());
            assertTrue(copies.getFirst().metadataAgrees());
        }
    }

    @Test
    void conflictingMetadataIsDetectedByUuid() {
        UUID id = UUID.randomUUID();
        FileMetadata first = metadata(id, "a.txt", 1, "0".repeat(64));
        FileMetadata second = metadata(id, "b.txt", 2, "1".repeat(64));

        List<InventoryComparator.FileCopies> result = InventoryComparator.compare(List.of(
                InventoryComparator.SourceInventory.available("A", List.of(first)),
                InventoryComparator.SourceInventory.available("B", List.of(second))));
        assertEquals(1, result.size());
        assertFalse(result.getFirst().metadataAgrees());
    }

    @Test
    void unavailableInventoryIsDistinctFromEmptyInventory() {
        var unavailable = InventoryComparator.SourceInventory.unavailable("B", "connection refused");
        assertFalse(unavailable.available());
        assertTrue(unavailable.files().isEmpty());
        assertEquals("connection refused", unavailable.reason());
    }

    @Test
    void malformedOrOversizedInventoryResponsesAreRejected() throws Exception {
        assertThrows(IOException.class, () -> PeerClient.listFiles("127.0.0.1", oneResponsePort(
                "FILES 5\nnope!")));
        assertThrows(IOException.class, () -> PeerClient.listFiles("127.0.0.1", oneResponsePort(
                "FILES " + (Protocol.MAX_INVENTORY_SIZE + 1) + "\n")));
    }

    private int oneResponsePort(String response) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (server; Socket socket = server.accept();
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                Protocol.readLine(input);
                output.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException ignored) {
            }
        });
        return server.getLocalPort();
    }

    private static void storeFile(FileStore store, String name, byte[] bytes) throws Exception {
        Path source = Files.createTempFile("inventory-source-", ".bin");
        Files.write(source, bytes);
        UUID id = UUID.randomUUID();
        FileMetadata metadata = metadata(id, name, bytes.length, HashUtils.sha256(source));
        try (FileStore.UploadSession session = store.reserveUpload(metadata)) {
            try (var input = Files.newInputStream(source)) {
                session.receive(input);
            }
        }
    }

    private static FileMetadata metadata(UUID id, String name, long size, String sha256) {
        return new FileMetadata(id, name, size, sha256, Instant.parse("2026-01-01T00:00:00Z").toString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
