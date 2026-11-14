package storage.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Persists the {@link DeviceProfile} as a small JSON file. A missing or damaged file means the defaults. */
public final class DeviceProfileStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;

    public DeviceProfileStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Never throws: an unreadable file is reported on stderr and replaced by the defaults. */
    public synchronized DeviceProfile load() {
        if (!Files.isRegularFile(file)) {
            return DeviceProfile.DEFAULT;
        }
        try {
            return JSON.readValue(Files.readAllBytes(file), DeviceProfile.class);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read " + file + " (" + e.getMessage() + "); using the default device settings.");
            return DeviceProfile.DEFAULT;
        }
    }

    /** Writes the profile atomically, so a crash cannot leave a half-written file. */
    public synchronized void save(DeviceProfile profile) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".device-profile-", ".tmp");
        try {
            Files.write(temporary, JSON.writeValueAsString(profile).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
