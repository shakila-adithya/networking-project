package storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PeerClientTimeoutTest {
    @Test
    void verifyFileDeadlineClosesAConnectionThatStopsAfterMetadata() throws Exception {
        UUID id = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata(id, "stalled.bin", 1, "00".repeat(32),
                Instant.now().toString());
        try (ServerSocket server = new ServerSocket(0)) {
            Thread stalledPeer = Thread.ofPlatform().daemon().start(() -> {
                try (Socket socket = server.accept()) {
                    Protocol.readLine(socket.getInputStream());
                    byte[] json = MetadataJson.encode(metadata);
                    Protocol.writeLine(socket.getOutputStream(), "FILE " + json.length);
                    socket.getOutputStream().write(json);
                    socket.getOutputStream().flush();
                    Thread.sleep(2_000);
                } catch (Exception ignored) {
                    // The client is expected to close the stalled connection at its deadline.
                }
            });

            long started = System.nanoTime();
            assertThrows(IOException.class,
                    () -> PeerClient.verifyFile("127.0.0.1", server.getLocalPort(), metadata, 100));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMillis < 1_500,
                    "deadline should close the stalled verification promptly: " + elapsedMillis + "ms");
            stalledPeer.join(2_500);
        }
    }
}
