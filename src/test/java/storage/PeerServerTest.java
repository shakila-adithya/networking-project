package storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void respondsToPing() throws IOException {
        int port = freePort();
        try (PeerServer server = server(port, "ping-peer")) {
            server.start();
            assertEquals("PONG", PeerClient.ping("127.0.0.1", port));
        }
    }

    @Test
    void rejectsUnsupportedRequest() throws IOException {
        int port = freePort();
        try (PeerServer server = server(port, "unsupported-peer")) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port);
                 var output = new BufferedOutputStream(socket.getOutputStream());
                 var input = new BufferedInputStream(socket.getInputStream())) {
                Protocol.writeLine(output, "HELLO");
                assertEquals("ERROR unsupported request", Protocol.readLine(input));
            }
        }
    }

    @Test
    void uploadsAndDownloadsBinaryFileWithoutChangingBytes() throws IOException {
        int port = freePort();
        Path store = temporaryDirectory.resolve("binary-peer");
        byte[] original = new byte[256 * 1024 + 37];
        new Random(42).nextBytes(original);
        Path source = temporaryDirectory.resolve("binary-source.dat");
        Files.write(source, original);

        try (PeerServer server = new PeerServer("127.0.0.1", port, store)) {
            server.start();
            TransferResult upload = PeerClient.upload("127.0.0.1", port, source);
            assertTrue(upload.success(), upload.message());
            assertTrue(Files.isRegularFile(store.resolve("files").resolve(upload.fileId() + ".bin")));
            assertTrue(Files.isRegularFile(store.resolve("metadata").resolve(upload.fileId() + ".json")));

            Path destination = temporaryDirectory.resolve("downloaded.dat");
            TransferResult download = PeerClient.download(
                    "127.0.0.1", port, upload.fileId(), destination);
            assertTrue(download.success(), download.message());
            assertArrayEquals(original, Files.readAllBytes(destination));
            assertEquals(HashUtils.sha256(source), HashUtils.sha256(destination));
        }
    }

    @Test
    void rejectsDuplicateFileIdWithoutOverwritingOriginal() throws IOException {
        int port = freePort();
        Path store = temporaryDirectory.resolve("duplicate-peer");
        Path source = temporaryDirectory.resolve("original.bin");
        Files.write(source, new byte[] {0, 1, 2, 3, 4});
        UUID fileId = UUID.randomUUID();

        try (PeerServer server = new PeerServer("127.0.0.1", port, store)) {
            server.start();
            TransferResult first = PeerClient.upload("127.0.0.1", port, source, fileId);
            TransferResult duplicate = PeerClient.upload("127.0.0.1", port, source, fileId);
            assertTrue(first.success());
            assertFalse(duplicate.success());
            assertEquals("DUPLICATE_FILE_ID", duplicate.code());
            assertArrayEquals(new byte[] {0, 1, 2, 3, 4},
                    Files.readAllBytes(store.resolve("files").resolve(fileId + ".bin")));
        }
    }

    @Test
    void interruptedUploadLeavesNoStoredOrTemporaryFile() throws Exception {
        int port = freePort();
        Path store = temporaryDirectory.resolve("interrupted-peer");
        byte[] completeContent = new byte[32 * 1024];
        new Random(7).nextBytes(completeContent);
        Path hashSource = temporaryDirectory.resolve("hash-source.bin");
        Files.write(hashSource, completeContent);
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata(fileId, "interrupted.bin", completeContent.length,
                HashUtils.sha256(hashSource), Instant.now().toString());
        byte[] json = MetadataJson.encode(metadata);

        try (PeerServer server = new PeerServer("127.0.0.1", port, store)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port);
                 var output = new BufferedOutputStream(socket.getOutputStream());
                 var input = new BufferedInputStream(socket.getInputStream())) {
                Protocol.writeLine(output, "PUT " + json.length);
                output.write(json);
                output.flush();
                assertEquals("READY", Protocol.readLine(input));
                output.write(completeContent, 0, 1_000);
                output.flush();
            }

            awaitDirectoryEmpty(store.resolve("tmp"));
            assertDirectoryEmpty(store.resolve("files"));
            assertDirectoryEmpty(store.resolve("metadata"));
        }
    }

    @Test
    void rejectsPathTraversalFilenameBeforeReady() throws IOException {
        int port = freePort();
        Path store = temporaryDirectory.resolve("unsafe-name-peer");
        UUID fileId = UUID.randomUUID();
        String jsonText = "{\"fileId\":\"" + fileId + "\",\"fileName\":\"../escape.bin\"," 
                + "\"size\":0,\"sha256\":\"" + "0".repeat(64) + "\"," 
                + "\"createdAt\":\"" + Instant.now() + "\"}";
        byte[] json = jsonText.getBytes(StandardCharsets.UTF_8);

        try (PeerServer server = new PeerServer("127.0.0.1", port, store)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port);
                 var output = new BufferedOutputStream(socket.getOutputStream());
                 var input = new BufferedInputStream(socket.getInputStream())) {
                Protocol.writeLine(output, "PUT " + json.length);
                output.write(json);
                output.flush();
                assertTrue(Protocol.readLine(input).startsWith("ERROR INVALID_METADATA "));
            }
        }
    }

    private PeerServer server(int port, String directory) throws IOException {
        return new PeerServer("127.0.0.1", port, temporaryDirectory.resolve(directory));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitDirectoryEmpty(Path directory) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (isDirectoryEmpty(directory)) {
                return;
            }
            Thread.sleep(20);
        }
        assertDirectoryEmpty(directory);
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        assertTrue(isDirectoryEmpty(directory), "expected empty directory: " + directory);
    }

    private static boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }
}
