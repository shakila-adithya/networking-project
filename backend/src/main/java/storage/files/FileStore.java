package storage.files;

import storage.transport.Protocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FileStore {
    private final Path root;
    private final Path filesDirectory;
    private final Path metadataDirectory;
    private final Path temporaryDirectory;
    private final Set<UUID> uploadsInProgress = ConcurrentHashMap.newKeySet();

    public FileStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.filesDirectory = this.root.resolve("files");
        this.metadataDirectory = this.root.resolve("metadata");
        this.temporaryDirectory = this.root.resolve("tmp");
        Files.createDirectories(filesDirectory);
        Files.createDirectories(metadataDirectory);
        Files.createDirectories(temporaryDirectory);
    }

    public UploadSession reserveUpload(FileMetadata metadata) throws IOException, StorageException {
        return reserveUpload(metadata, false);
    }

    /** Reserves a replica upload, allowing replacement only for hash-invalid matching metadata. */
    public UploadSession reserveReplicaUpload(FileMetadata metadata) throws IOException, StorageException {
        return reserveUpload(metadata, true);
    }

    private UploadSession reserveUpload(FileMetadata metadata, boolean allowCorruptReplacement)
            throws IOException, StorageException {
        Path content = contentPath(metadata.fileId());
        Path metadataPath = metadataPath(metadata.fileId());
        if (!uploadsInProgress.add(metadata.fileId())) {
            throw new StorageException("DUPLICATE_FILE_ID", "an upload with this file ID is already in progress");
        }

        try {
            if (Files.exists(content) || Files.exists(metadataPath)) {
                if (!allowCorruptReplacement || !isCorruptMatchingReplica(metadata, content, metadataPath)) {
                    throw new StorageException("DUPLICATE_FILE_ID", "file ID is already stored");
                }
                Path temporaryContent = Files.createTempFile(temporaryDirectory,
                        metadata.fileId() + "-", ".part");
                return new UploadSession(metadata, temporaryContent, content, metadataPath, true);
            }
            Path temporaryContent = Files.createTempFile(temporaryDirectory,
                    metadata.fileId() + "-", ".part");
            return new UploadSession(metadata, temporaryContent, content, metadataPath, false);
        } catch (IOException | StorageException e) {
            uploadsInProgress.remove(metadata.fileId());
            throw e;
        }
    }

    private boolean isCorruptMatchingReplica(FileMetadata expected, Path content, Path metadataPath)
            throws IOException, StorageException {
        if (!Files.isRegularFile(content) || !Files.isRegularFile(metadataPath)
                || Files.size(metadataPath) > Protocol.MAX_METADATA_SIZE) {
            return false;
        }
        final FileMetadata stored;
        try {
            stored = MetadataJson.decode(Files.readAllBytes(metadataPath));
        } catch (Protocol.ProtocolException e) {
            throw new StorageException("CORRUPT_METADATA", e.getMessage());
        }
        if (!expected.equals(stored)) return false;
        return Files.size(content) != expected.size()
                || !expected.sha256().equals(HashUtils.sha256(content));
    }

    public StoredFile open(UUID fileId) throws IOException, StorageException {
        Path content = contentPath(fileId);
        Path metadataPath = metadataPath(fileId);
        if (!Files.isRegularFile(content) || !Files.isRegularFile(metadataPath)) {
            throw new StorageException("NOT_FOUND", "file ID is not stored on this peer");
        }

        if (Files.size(metadataPath) > Protocol.MAX_METADATA_SIZE) {
            throw new StorageException("CORRUPT_METADATA", "stored metadata is too large");
        }
        byte[] json = Files.readAllBytes(metadataPath);
        FileMetadata metadata;
        try {
            metadata = MetadataJson.decode(json);
        } catch (Protocol.ProtocolException e) {
            throw new StorageException("CORRUPT_METADATA", e.getMessage());
        }
        if (!metadata.fileId().equals(fileId) || Files.size(content) != metadata.size()) {
            throw new StorageException("CORRUPT_FILE", "stored content does not match its metadata");
        }
        return new StoredFile(metadata, content, json);
    }

    /** Returns a stable, read-only snapshot of complete files currently published in this store. */
    public List<FileMetadata> inventory() throws IOException {
        List<FileMetadata> snapshot = new ArrayList<>();
        try (var paths = Files.list(metadataDirectory)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                if (snapshot.size() >= Protocol.MAX_INVENTORY_ENTRIES) {
                    throw new IOException("inventory contains more than "
                            + Protocol.MAX_INVENTORY_ENTRIES + " complete files");
                }
                Path metadataPath = iterator.next();
                String name = metadataPath.getFileName().toString();
                if (!name.endsWith(".json") || !Files.isRegularFile(metadataPath)) {
                    continue;
                }
                UUID fileId;
                try {
                    fileId = UUID.fromString(name.substring(0, name.length() - 5));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (uploadsInProgress.contains(fileId)) {
                    continue;
                }
                try {
                    snapshot.add(open(fileId).metadata());
                } catch (StorageException e) {
                    // A file is only inventory-visible when both final artifacts are complete and consistent.
                    if (!"NOT_FOUND".equals(e.code()) && !"CORRUPT_FILE".equals(e.code())) {
                        throw new IOException("could not inventory " + fileId + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        return List.copyOf(snapshot);
    }

    public Path root() {
        return root;
    }

    public boolean verify(UUID fileId, FileMetadata expected) throws IOException, StorageException {
        StoredFile stored = open(fileId);
        return expected.equals(stored.metadata())
                && expected.sha256().equals(HashUtils.sha256(stored.contentPath()));
    }

    /** Deletes a complete local file pair. Missing files are treated as already deleted. */
    public void delete(UUID fileId) throws IOException {
        Files.deleteIfExists(contentPath(fileId));
        Files.deleteIfExists(metadataPath(fileId));
    }

    private Path contentPath(UUID fileId) {
        return filesDirectory.resolve(fileId + ".bin");
    }

    private Path metadataPath(UUID fileId) {
        return metadataDirectory.resolve(fileId + ".json");
    }

    private static void moveWithoutOverwrite(Path source, Path destination) throws IOException {
        boolean reserved = false;
        boolean moved = false;
        try {
            Files.createFile(destination);
            reserved = true;
            try {
                Files.move(source, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (reserved && !moved) {
                Files.deleteIfExists(destination);
            }
        }
    }

    public record StoredFile(FileMetadata metadata, Path contentPath, byte[] metadataJson) {
    }

    public final class UploadSession implements AutoCloseable {
        private final FileMetadata metadata;
        private final Path temporaryContent;
        private final Path finalContent;
        private final Path finalMetadata;
        private final boolean replaceExisting;
        private boolean committed;
        private boolean closed;

        private UploadSession(FileMetadata metadata, Path temporaryContent,
                              Path finalContent, Path finalMetadata, boolean replaceExisting) {
            this.metadata = metadata;
            this.temporaryContent = temporaryContent;
            this.finalContent = finalContent;
            this.finalMetadata = finalMetadata;
            this.replaceExisting = replaceExisting;
        }

        public void receive(InputStream input) throws IOException, StorageException {
            if (closed) {
                throw new IllegalStateException("upload session is closed");
            }
            MessageDigest digest = HashUtils.sha256Digest();
            try (OutputStream output = Files.newOutputStream(temporaryContent)) {
                Protocol.copyExactly(input, output, metadata.size(), digest);
            }

            String actualHash = HashUtils.toHex(digest.digest());
            if (!actualHash.equals(metadata.sha256())) {
                throw new StorageException("HASH_MISMATCH", "received content failed SHA-256 verification");
            }

            byte[] json = MetadataJson.encode(metadata);
            if (json.length > Protocol.MAX_METADATA_SIZE) {
                throw new StorageException("INVALID_METADATA", "encoded metadata is too large");
            }
            Path temporaryMetadata = Files.createTempFile(temporaryDirectory,
                    metadata.fileId() + "-", ".json.part");
            boolean contentMoved = false;
            try {
                Files.write(temporaryMetadata, json);
                if (replaceExisting) {
                    replaceExisting(temporaryContent, temporaryMetadata);
                } else {
                    moveWithoutOverwrite(temporaryContent, finalContent);
                    contentMoved = true;
                    moveWithoutOverwrite(temporaryMetadata, finalMetadata);
                }
                committed = true;
            } catch (FileAlreadyExistsException e) {
                throw new StorageException("DUPLICATE_FILE_ID", "file ID appeared while upload was in progress");
            } finally {
                Files.deleteIfExists(temporaryMetadata);
                if (!committed && contentMoved) {
                    Files.deleteIfExists(finalContent);
                }
            }
        }

        private void replaceExisting(Path newContent, Path newMetadata) throws IOException {
            Path oldContent = Files.createTempFile(temporaryDirectory, metadata.fileId() + "-", ".old-content");
            Path oldMetadata = Files.createTempFile(temporaryDirectory, metadata.fileId() + "-", ".old-metadata");
            Files.deleteIfExists(oldContent);
            Files.deleteIfExists(oldMetadata);
            boolean contentBackedUp = false;
            boolean metadataBackedUp = false;
            boolean newContentMoved = false;
            boolean newMetadataMoved = false;
            try {
                moveExisting(finalContent, oldContent);
                contentBackedUp = true;
                moveExisting(finalMetadata, oldMetadata);
                metadataBackedUp = true;
                moveWithoutOverwrite(newContent, finalContent);
                newContentMoved = true;
                moveWithoutOverwrite(newMetadata, finalMetadata);
                newMetadataMoved = true;
            } catch (IOException failure) {
                if (newContentMoved) Files.deleteIfExists(finalContent);
                if (newMetadataMoved) Files.deleteIfExists(finalMetadata);
                if (contentBackedUp) Files.move(oldContent, finalContent, StandardCopyOption.REPLACE_EXISTING);
                if (metadataBackedUp) Files.move(oldMetadata, finalMetadata, StandardCopyOption.REPLACE_EXISTING);
                throw failure;
            } finally {
                Files.deleteIfExists(oldContent);
                Files.deleteIfExists(oldMetadata);
            }
        }

        private void moveExisting(Path source, Path destination) throws IOException {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, destination);
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                uploadsInProgress.remove(metadata.fileId());
                if (!committed) {
                    Files.deleteIfExists(temporaryContent);
                }
            }
        }
    }

    public static final class StorageException extends Exception {
        private final String code;

        public StorageException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
