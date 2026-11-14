package storage.files;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
/** Atomic durable metadata journals; no network payload is ever read with Scanner. */
public final class AtomicJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AtomicJson() { }
    public static void save(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".journal-", ".tmp");
        try {
            try (var out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(value)); while (bytes.hasRemaining()) out.write(bytes); out.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
                try (var parent = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { parent.force(true); }
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static <T> T read(Path path, Class<T> type) throws IOException {
        if (Files.size(path) > 131072) throw new IOException("Transfer journal is too large");
        return JSON.readValue(Files.readAllBytes(path), type);
    }
}
