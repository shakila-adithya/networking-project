package storage.custody;
import storage.files.AtomicJson;
import java.io.IOException;
import java.nio.file.*;
public record StoragePolicy(long quotaBytes, long retentionHours) {
    public static final StoragePolicy DEFAULT = new StoragePolicy(CustodyService.DEFAULT_QUOTA, 24);
    public StoragePolicy {
        if (quotaBytes < 1_048_576 || quotaBytes > 16L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Choose a temporary storage limit between 1 MB and 16 GB");
        if (retentionHours < 1 || retentionHours > 168) throw new IllegalArgumentException("Choose an expiry between 1 and 168 hours");
    }
    public static StoragePolicy load(Path directory) throws IOException {
        Path file = directory.resolve("relay-storage.json"); return Files.exists(file) ? AtomicJson.read(file, StoragePolicy.class) : DEFAULT;
    }
    public void save(Path directory) throws IOException { AtomicJson.save(directory.resolve("relay-storage.json"), this); }
}
