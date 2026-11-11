package storage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record FileMetadata(UUID fileId, String fileName, long size, String sha256, String createdAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern UNSAFE_FILE_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");

    public FileMetadata {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(createdAt, "createdAt");

        validateFileName(fileName);
        if (size < 0 || size > Protocol.MAX_FILE_SIZE) {
            throw new IllegalArgumentException("file size must be between 0 and " + Protocol.MAX_FILE_SIZE);
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must contain exactly 64 hexadecimal characters");
        }
        try {
            Instant.parse(createdAt);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("createdAt must be an ISO-8601 instant", e);
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
    }

    public static void validateFileName(String fileName) {
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("filename cannot be blank, '.' or '..'");
        }
        if (fileName.length() > 255) {
            throw new IllegalArgumentException("filename exceeds 255 characters");
        }
        if (UNSAFE_FILE_CHARACTERS.matcher(fileName).find()
                || fileName.endsWith(".") || fileName.endsWith(" ")) {
            throw new IllegalArgumentException("filename contains unsafe characters");
        }
    }
}
