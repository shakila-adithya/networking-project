package storage.transport;

import storage.files.FileMetadata;
import storage.files.HashUtils;
import storage.files.MetadataJson;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LegacyUploadTest {
    @TempDir Path root;

    @Test void legacyAliasStoresOnceAndNeverOverwritesEvenCorruptContent() throws Exception {
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
        byte[] content = "original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var metadata = new FileMetadata(UUID.randomUUID(), "notes.txt", content.length,
                HashUtils.toHex(HashUtils.sha256Digest().digest(content)), Instant.now().toString(), List.of("B"));
        try (PeerServer server = new PeerServer("127.0.0.1", port, root, "B")) {
            server.start();
            assertEquals("OK STORED " + metadata.fileId(), upload(port, "REPLICA_PUT", metadata, content));
            assertTrue(server.fileStore().verify(metadata.fileId(), metadata));
            assertTrue(upload(port, "PUT", metadata, content).startsWith("ERROR DUPLICATE_FILE_ID"));
            Path stored = server.fileStore().open(metadata.fileId()).contentPath();
            byte[] corrupt = content.clone(); corrupt[0] ^= 1;
            Files.write(stored, corrupt);
            assertTrue(upload(port, "REPLICA_PUT", metadata, content).startsWith("ERROR DUPLICATE_FILE_ID"));
            assertArrayEquals(corrupt, Files.readAllBytes(stored));
            var wrongRecipient = new FileMetadata(UUID.randomUUID(), metadata.fileName(), content.length,
                    metadata.sha256(), metadata.createdAt(), List.of("C"));
            assertTrue(upload(port, "REPLICA_PUT", wrongRecipient, content).startsWith("ERROR NOT_A_RECIPIENT"));
            assertEquals(1, server.inventory().size());
        }
    }

    private String upload(int port, String command, FileMetadata metadata, byte[] bytes) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             var input = new BufferedInputStream(socket.getInputStream());
             var output = new BufferedOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(2000);
            byte[] json = MetadataJson.encode(metadata);
            Protocol.writeLine(output, command + " " + json.length);
            output.write(json); output.flush();
            String response = Protocol.readLine(input);
            if (!"READY".equals(response)) return response;
            output.write(bytes); output.flush();
            return Protocol.readLine(input);
        }
    }
}
