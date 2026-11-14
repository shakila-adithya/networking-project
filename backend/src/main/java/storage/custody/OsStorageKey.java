package storage.custody;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A device key protected by Secret Service on Linux or CurrentUser DPAPI on Windows. No plaintext disk key. */
public final class OsStorageKey {
    private OsStorageKey() { }
    public static SecretKey open(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path reference = directory.resolve("key-reference");
        String id;
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(directory.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IOException(e); }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        byte[] key;
        if (os.contains("linux")) {
            String saved = run(List.of("secret-tool", "lookup", "application", "relay-mesh", "storage-id", id), null, true);
            if (saved.isBlank()) {
                if (Files.exists(reference)) throw new IOException("Relay's Secret Service key is locked or missing. Unlock the desktop keyring; existing data was kept.");
                key = randomKey();
                run(List.of("secret-tool", "store", "--label=Relay encrypted pending data", "application", "relay-mesh", "storage-id", id), Base64.getEncoder().encodeToString(key), false);
                String checked = run(List.of("secret-tool", "lookup", "application", "relay-mesh", "storage-id", id), null, false);
                if (!checked.strip().equals(Base64.getEncoder().encodeToString(key))) throw new IOException("Secret Service did not retain Relay's key");
                Files.writeString(reference, id, StandardOpenOption.CREATE_NEW);
            } else {
                try { key = Base64.getDecoder().decode(saved.strip()); }
                catch (IllegalArgumentException e) { throw new IOException("Relay's stored key is invalid; encrypted data was kept", e); }
            }
        } else if (os.contains("windows")) {
            String action = Files.exists(reference) ? "Unprotect" : "Protect";
            String input = Files.exists(reference) ? Files.readString(reference) : Base64.getEncoder().encodeToString(randomKey());
            String script = "Add-Type -AssemblyName System.Security; $b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::" + action + "($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))";
            String result = run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), input, false).strip();
            try {
                if (action.equals("Protect")) { Files.writeString(reference, result, StandardOpenOption.CREATE_NEW); key = Base64.getDecoder().decode(input); }
                else key = Base64.getDecoder().decode(result);
            } catch (IllegalArgumentException e) { throw new IOException("Windows could not unlock Relay's storage key", e); }
        } else throw new IOException("Secure pending-data storage requires Linux Secret Service or Windows DPAPI");
        if (key.length != 32) throw new IOException("Invalid protected storage key length");
        return new SecretKeySpec(key, "AES");
    }
    private static byte[] randomKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
    private static String run(List<String> command, String input, boolean missingAllowed) throws IOException {
        Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).start(); }
        catch (IOException e) { throw new IOException("OS credential store unavailable (Linux requires secret-tool and an unlocked Secret Service)", e); }
        try {
            try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("OS credential store timed out; unlock the keyring and retry"); }
            byte[] result = process.getInputStream().readNBytes(4097);
            if (result.length > 4096) throw new IOException("Invalid credential-store response");
            if (process.exitValue() != 0) {
                if (missingAllowed && process.exitValue() == 1 && result.length == 0) return "";
                throw new IOException("OS credential store refused access; unlock the keyring or check permissions");
            }
            return new String(result, StandardCharsets.UTF_8);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Credential lookup interrupted", e); }
        finally { process.destroyForcibly(); }
    }
}
