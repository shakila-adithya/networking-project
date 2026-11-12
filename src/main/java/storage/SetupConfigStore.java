package storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Separate managed configuration, never overwrites a CLI configuration. */
public final class SetupConfigStore {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public record Saved(PeerConfiguration peer, int restPort) {
        public Saved {
            Objects.requireNonNull(peer);
            if (restPort < 1 || restPort > 65535 || restPort == peer.port())
                throw new IllegalArgumentException("REST port must be valid and different from peer TCP port");
        }
        @Override public String toString() { return "Saved[configuration redacted]"; }
    }
    private final Path path;
    public SetupConfigStore(Path path) { this.path = path.toAbsolutePath().normalize(); }
    public boolean exists() { return Files.exists(path); }
    public Saved load() throws IOException {
        try { return JSON.readValue(Files.readAllBytes(path), Saved.class); }
        catch (Exception e) { throw new IOException("Saved setup configuration is invalid; apply a valid configuration in Settings."); }
    }
    public void save(Saved config) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".setup-", ".tmp");
        try {
            protect(temporary);
            Files.write(temporary, JSON.writeValueAsBytes(config));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // Require atomic replacement: a failed save keeps the previous configuration intact.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void protect(Path file) throws IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(file)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
