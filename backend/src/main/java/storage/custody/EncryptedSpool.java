package storage.custody;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Atomic authenticated records. Admission never evicts accepted, unexpired custody. */
public final class EncryptedSpool {
    private final Path directory;
    private final SecretKey key;
    private long quota;
    private long bytesUsed;
    private int recordCount;
    private static final SecureRandom RANDOM = new SecureRandom();
    public EncryptedSpool(Path directory, SecretKey key, long quota) throws IOException {
        if (quota < 1024) throw new IllegalArgumentException("Storage quota is too small");
        this.directory = directory; this.key = key; this.quota = quota; Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (Path p : files.toList()) {
                if (p.getFileName().toString().endsWith(".tmp")) Files.deleteIfExists(p);
                else if (p.getFileName().toString().endsWith(".sealed")) { bytesUsed += Files.size(p); recordCount++; }
            }
        }
    }
    public synchronized List<UUID> ids() throws IOException {
        try (var files = Files.list(directory)) { return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sealed"))
                .map(n -> UUID.fromString(n.substring(0, n.length() - 7))).limit(100001).toList(); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid pending-data record name; data was kept", e); }
    }
    public synchronized long used() throws IOException {
        return bytesUsed;
    }
    public synchronized void quota(long bytes) throws IOException {
        if (bytes < 1024 || bytes < used()) throw new IllegalArgumentException("Quota must cover the pending data already stored"); quota = bytes;
    }
    public synchronized long quota() { return quota; }
    public synchronized void put(UUID id, byte[] plain) throws IOException {
        if (plain.length > 65536) throw new IOException("Pending-data record is too large");
        Path target = directory.resolve(id + ".sealed");
        long old = Files.exists(target) ? Files.size(target) : 0;
        if (used() - old + plain.length + 32 > quota || old == 0 && recordCount >= 100000) throw new IOException("Pending-data storage is full; existing data was kept");
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD((directory.toAbsolutePath().normalize() + "/" + id).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            Path temporary = Files.createTempFile(directory, ".record-", ".tmp");
            try {
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer bytes = ByteBuffer.allocate(4 + nonce.length + encrypted.length).putInt(1).put(nonce).put(encrypted); bytes.flip();
                    while (bytes.hasRemaining()) output.write(bytes); output.force(true);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                bytesUsed = bytesUsed - old + Files.size(target); if (old == 0) recordCount++;
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                    try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
                }
            } finally { Files.deleteIfExists(temporary); }
        } catch (GeneralSecurityException e) { throw new IOException("Could not encrypt pending data", e); }
    }
    public synchronized byte[] get(UUID id) throws IOException {
        Path file = directory.resolve(id + ".sealed");
        if (Files.size(file) > 65568) throw new IOException("Pending-data record exceeds its bound");
        byte[] record = Files.readAllBytes(file);
        if (record.length < 32 || ByteBuffer.wrap(record).getInt() != 1) throw new IOException("Unsupported pending-data record version");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, record, 4, 12)); cipher.updateAAD((directory.toAbsolutePath().normalize() + "/" + id).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(record, 16, record.length - 16);
        } catch (GeneralSecurityException e) { throw new IOException("Pending-data integrity check failed; record was kept", e); }
    }
    public synchronized boolean contains(UUID id) { return Files.exists(directory.resolve(id + ".sealed")); }
    public synchronized void remove(UUID id) throws IOException { Path p = directory.resolve(id + ".sealed"); if (Files.exists(p)) { long size = Files.size(p); Files.delete(p); bytesUsed -= size; recordCount--; } }
}
