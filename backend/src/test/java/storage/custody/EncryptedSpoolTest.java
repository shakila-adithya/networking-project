package storage.custody;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.KeyGenerator;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EncryptedSpoolTest {
    @TempDir Path root;
    @Test void recordsSurviveRestartAreEncryptedAndRejectTamperingAndWrongContext() throws Exception {
        var key = KeyGenerator.getInstance("AES").generateKey(); UUID id = UUID.randomUUID();
        byte[] content = "private pending message, never plaintext on a relay".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var spool = new EncryptedSpool(root.resolve("one"), key, 4096); spool.put(id, content);
        Path file = root.resolve("one/" + id + ".sealed"); byte[] first = Files.readAllBytes(file);
        assertFalse(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("private pending"));
        assertArrayEquals(content, new EncryptedSpool(root.resolve("one"), key, 4096).get(id));
        spool.put(id, content); assertFalse(Arrays.equals(first, Files.readAllBytes(file)), "Fresh AEAD nonce per write");
        Files.createDirectories(root.resolve("other")); Files.copy(file, root.resolve("other/" + id + ".sealed"));
        assertThrows(IOException.class, () -> new EncryptedSpool(root.resolve("other"), key, 4096).get(id));
        byte[] damaged = Files.readAllBytes(file); damaged[damaged.length - 1] ^= 1; Files.write(file, damaged);
        assertThrows(IOException.class, () -> spool.get(id)); assertTrue(Files.exists(file));
    }
    @Test void quotaRefusesNewCustodyWithoutEvictingAcceptedData() throws Exception {
        var spool = new EncryptedSpool(root, KeyGenerator.getInstance("AES").generateKey(), 1024);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(); spool.put(first, new byte[800]);
        assertThrows(IOException.class, () -> spool.put(second, new byte[300]));
        assertArrayEquals(new byte[800], spool.get(first)); assertFalse(spool.contains(second));
        assertThrows(IllegalArgumentException.class, () -> spool.quota(500));
        spool.remove(first); assertEquals(0, spool.used());
    }
}
