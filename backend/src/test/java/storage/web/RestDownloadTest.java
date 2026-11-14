package storage.web;

import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.files.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RestDownloadTest {
    @TempDir Path temporary;

    @Test void downloadsExactContentAndOriginalFilenameIncludingEmptyFiles() throws Exception {
        FileStore store = new FileStore(temporary);
        try (RestApiServer api = new RestApiServer("127.0.0.1", 0, "A", "127.0.0.1", 5000,
                store, null, null)) {
            api.start();
            for (byte[] bytes : new byte[][] {{0, 1, 2, -1, 10}, {}}) {
                UUID id = UUID.randomUUID();
                String hash = HashUtils.toHex(HashUtils.sha256Digest().digest(bytes));
                FileMetadata metadata = new FileMetadata(id, "my vidéo.bin", bytes.length, hash, Instant.now().toString());
                try (FileStore.UploadSession upload = store.reserveUpload(metadata)) {
                    upload.receive(new ByteArrayInputStream(bytes));
                }
                var response = get(api.port(), id.toString());
                assertEquals(200, response.statusCode());
                assertArrayEquals(bytes, response.body());
                assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow()
                        .contains("filename*=UTF-8''my%20vid%C3%A9o.bin"));
                assertEquals(Integer.toString(bytes.length), response.headers().firstValue("Content-Length").orElseThrow());
            }
        }
    }

    @Test void rejectsInvalidAndMissingIds() throws Exception {
        try (RestApiServer api = new RestApiServer("127.0.0.1", 0, "A", "127.0.0.1", 5000,
                new FileStore(temporary), null, null)) {
            api.start();
            assertEquals(400, get(api.port(), "invalid").statusCode());
            assertEquals(404, get(api.port(), UUID.randomUUID().toString()).statusCode());
        }
    }

    private HttpResponse<byte[]> get(int port, String id) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/files/" + id + "/download")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
}
