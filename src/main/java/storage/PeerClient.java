package storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.List;

public final class PeerClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final long CONTROL_OPERATION_TIMEOUT_MILLIS = 60_000L;
    private static final long TRANSFER_BASE_TIMEOUT_MILLIS = 60_000L;
    private static final long TRANSFER_MILLIS_PER_MB = 2_000L;
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1,
            runnable -> Thread.ofPlatform().daemon().name("peer-client-timeouts").unstarted(runnable));

    static {
        TIMEOUTS.setRemoveOnCancelPolicy(true);
    }

    private PeerClient() {
    }

    public static String ping(String host, int port) throws IOException {
        return ping(host, port, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
    }

    public static List<FileMetadata> listFiles(String host, int port) throws IOException {
        try (Socket socket = connect(host, port);
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            try (SocketDeadline ignored = SocketDeadline.after(CONTROL_OPERATION_TIMEOUT_MILLIS, socket)) {
            Protocol.writeLine(output, "LIST_FILES");
            String response = Protocol.readLine(input);
            if (response != null && response.startsWith("ERROR ")) {
                throw new IOException(response);
            }
            if (response == null || !response.startsWith("FILES ")) {
                throw new IOException("unexpected inventory response: " + response);
            }
            int length = parseInventoryLength(response.substring(6));
            try {
                return InventoryJson.decode(Protocol.readExactly(input, length));
            } catch (Protocol.ProtocolException e) {
                throw new IOException(e.getMessage(), e);
            }
            }
        }
    }

    static void delete(String host, int port, UUID fileId) throws IOException {
        try (Socket socket = connect(host, port);
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            Protocol.writeLine(output, "DELETE " + fileId);
            String response = Protocol.readLine(input);
            TransferResult rejected = parseError(response, fileId);
            if (rejected != null) throw new IOException(rejected.code() + ": " + rejected.message());
            if (!("OK DELETED " + fileId).equals(response)) {
                throw new IOException("unexpected delete response: " + response);
            }
        }
    }

    static boolean verifyFile(String host, int port, FileMetadata expected) throws IOException {
        return verifyFile(host, port, expected, transferTimeoutMillis(expected.size()));
    }

    static boolean verifyFile(String host, int port, FileMetadata expected,
                              long operationTimeoutMillis) throws IOException {
        try (Socket socket = connect(host, port);
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            try (SocketDeadline ignored = SocketDeadline.after(operationTimeoutMillis, socket)) {
            Protocol.writeLine(output, "GET " + expected.fileId());
            String response = Protocol.readLine(input);
            TransferResult rejected = parseError(response, expected.fileId());
            if (rejected != null) return false;
            if (response == null || !response.startsWith("FILE ")) {
                throw new IOException("unexpected verification response: " + response);
            }
            int metadataLength = parseMetadataLength(response.substring(5));
            FileMetadata actual;
            try {
                actual = MetadataJson.decode(Protocol.readExactly(input, metadataLength));
            } catch (Protocol.ProtocolException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (!expected.equals(actual)) return false;
            MessageDigest digest = HashUtils.sha256Digest();
            Protocol.copyExactly(input, OutputStream.nullOutputStream(), expected.size(), digest);
            return expected.sha256().equals(HashUtils.toHex(digest.digest()));
            }
        }
    }

    static String ping(String host, int port, int connectTimeoutMillis, int readTimeoutMillis)
            throws IOException {
        try (Socket socket = connect(host, port, connectTimeoutMillis, readTimeoutMillis);
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            Protocol.writeLine(output, "PING");
            return Protocol.readLine(input);
        }
    }

    public static TransferResult upload(String host, int port, Path source) throws IOException {
        return upload(host, port, source, UUID.randomUUID());
    }

    public static TransferResult upload(String host, int port, Path source, UUID fileId) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new IOException("source is not a regular file: " + source);
        }
        long size = Files.size(normalizedSource);
        if (size > Protocol.MAX_FILE_SIZE) {
            throw new IOException("file exceeds the 100 MB limit");
        }
        String fileName = normalizedSource.getFileName().toString();
        FileMetadata metadata = new FileMetadata(fileId, fileName, size,
                HashUtils.sha256(normalizedSource), Instant.now().toString());
        return sendUpload(host, port, normalizedSource, metadata, "PUT", true);
    }

    static TransferResult uploadReplica(String host, int port, FileMetadata metadata, Path source)
            throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new IOException("replica source is not a regular file: " + source);
        }
        if (Files.size(normalizedSource) != metadata.size()) {
            throw new IOException("replica source size does not match metadata");
        }
        return sendUpload(host, port, normalizedSource, metadata, "REPLICA_PUT", false);
    }

    static TransferResult replicateFromPeer(ReplicationManager.PeerEndpoint source,
                                            ReplicationManager.PeerEndpoint destination,
                                            FileMetadata expected) throws IOException {
        try (Socket sourceSocket = connect(source.host(), source.port());
             InputStream sourceInput = new BufferedInputStream(sourceSocket.getInputStream());
             Socket destinationSocket = connect(destination.host(), destination.port());
             InputStream destinationInput = new BufferedInputStream(destinationSocket.getInputStream());
             OutputStream destinationOutput = new BufferedOutputStream(destinationSocket.getOutputStream())) {
            try (SocketDeadline ignored = SocketDeadline.after(transferTimeoutMillis(expected.size()),
                    sourceSocket, destinationSocket)) {
            OutputStream sourceOutput = new BufferedOutputStream(sourceSocket.getOutputStream());
            Protocol.writeLine(sourceOutput, "GET " + expected.fileId());
            String sourceResponse = Protocol.readLine(sourceInput);
            TransferResult sourceError = parseError(sourceResponse, expected.fileId());
            if (sourceError != null) {
                return sourceError;
            }
            if (sourceResponse == null || !sourceResponse.startsWith("FILE ")) {
                throw new IOException("unexpected source response: " + sourceResponse);
            }
            int metadataLength = parseMetadataLength(sourceResponse.substring(5));
            FileMetadata actual;
            try {
                actual = MetadataJson.decode(Protocol.readExactly(sourceInput, metadataLength));
            } catch (Protocol.ProtocolException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (!expected.equals(actual)) {
                return TransferResult.failure("METADATA_CONFLICT",
                        "source metadata does not match the inventory snapshot", expected.fileId());
            }

            byte[] metadataJson = MetadataJson.encode(expected);
            Protocol.writeLine(destinationOutput, "REPLICA_PUT " + metadataJson.length);
            destinationOutput.write(metadataJson);
            destinationOutput.flush();
            String readiness = Protocol.readLine(destinationInput);
            TransferResult rejected = parseError(readiness, expected.fileId());
            if (rejected != null) {
                return rejected;
            }
            if (!"READY".equals(readiness)) {
                throw new IOException("unexpected replica readiness response: " + readiness);
            }

            MessageDigest digest = HashUtils.sha256Digest();
            Protocol.copyExactly(sourceInput, destinationOutput, expected.size(), digest);
            destinationOutput.flush();
            if (!expected.sha256().equals(HashUtils.toHex(digest.digest()))) {
                return TransferResult.failure("HASH_MISMATCH",
                        "source content failed SHA-256 verification", expected.fileId());
            }
            String response = Protocol.readLine(destinationInput);
            rejected = parseError(response, expected.fileId());
            if (rejected != null) {
                return rejected;
            }
            String expectedResponse = "OK STORED " + expected.fileId();
            if (expectedResponse.equals(response)) {
                return TransferResult.success("replica stored successfully", expected.fileId());
            }
            throw new IOException("unexpected replica response: " + response);
            }
        }
    }

    private static long transferTimeoutMillis(long bytes) {
        long megabytes = Math.max(1L, (bytes + (1024L * 1024L) - 1L) / (1024L * 1024L));
        try {
            return Math.addExact(TRANSFER_BASE_TIMEOUT_MILLIS,
                    Math.multiplyExact(megabytes, TRANSFER_MILLIS_PER_MB));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Closes sockets at the operation deadline; this also unblocks a read that is receiving trickle data. */
    private static final class SocketDeadline implements AutoCloseable {
        private final ScheduledFuture<?> task;

        private SocketDeadline(ScheduledFuture<?> task) {
            this.task = task;
        }

        static SocketDeadline after(long timeoutMillis, Socket... sockets) {
            if (timeoutMillis < 1) throw new IllegalArgumentException("operation timeout must be positive");
            Socket[] captured = sockets.clone();
            ScheduledFuture<?> task = TIMEOUTS.schedule(() -> {
                for (Socket socket : captured) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            return new SocketDeadline(task);
        }

        @Override public void close() {
            task.cancel(false);
        }
    }

    private static TransferResult sendUpload(String host, int port, Path source,
                                             FileMetadata metadata, String command,
                                             boolean acceptReplicationWarning) throws IOException {
        byte[] metadataJson = MetadataJson.encode(metadata);
        if (metadataJson.length > Protocol.MAX_METADATA_SIZE) {
            throw new IOException("metadata exceeds protocol limit");
        }

        try (Socket socket = connect(host, port);
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            Protocol.writeLine(output, command + " " + metadataJson.length);
            output.write(metadataJson);
            output.flush();

            String readiness = Protocol.readLine(input);
            TransferResult rejected = parseError(readiness, metadata.fileId());
            if (rejected != null) {
                return rejected;
            }
            if (!"READY".equals(readiness)) {
                throw new IOException("unexpected upload response: " + readiness);
            }

            try (InputStream fileInput = Files.newInputStream(source)) {
                Protocol.copyExactly(fileInput, output, metadata.size(), null);
            }
            output.flush();

            String response = Protocol.readLine(input);
            rejected = parseError(response, metadata.fileId());
            if (rejected != null) {
                return rejected;
            }
            String expected = "OK STORED " + metadata.fileId();
            if (expected.equals(response)) {
                return TransferResult.success("stored successfully", metadata.fileId());
            }
            String warningPrefix = expected + " REPLICATION_FAILED ";
            if (acceptReplicationWarning && response.startsWith(warningPrefix)) {
                return TransferResult.storedWithWarning("REPLICATION_FAILED",
                        "primary stored; replication failed: " + response.substring(warningPrefix.length()),
                        metadata.fileId());
            }
            throw new IOException("unexpected upload response: " + response);
        }
    }

    public static TransferResult download(String host, int port, UUID fileId, Path destination)
            throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        if (Files.exists(normalizedDestination)) {
            return TransferResult.failure("DESTINATION_EXISTS",
                    "destination already exists; overwrite refused", fileId);
        }
        Path parent = normalizedDestination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("destination parent directory does not exist");
        }

        try (Socket socket = connect(host, port);
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            Protocol.writeLine(output, "GET " + fileId);
            String response = Protocol.readLine(input);
            TransferResult rejected = parseError(response, fileId);
            if (rejected != null) {
                return rejected;
            }
            if (response == null || !response.startsWith("FILE ")) {
                throw new IOException("unexpected download response: " + response);
            }
            int metadataLength = parseMetadataLength(response.substring(5));
            FileMetadata metadata;
            try {
                metadata = MetadataJson.decode(Protocol.readExactly(input, metadataLength));
            } catch (Protocol.ProtocolException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (!metadata.fileId().equals(fileId)) {
                throw new IOException("server returned metadata for a different file ID");
            }

            Path temporary = Files.createTempFile(parent, ".download-", ".part");
            boolean moved = false;
            try {
                MessageDigest digest = HashUtils.sha256Digest();
                try (OutputStream fileOutput = Files.newOutputStream(temporary)) {
                    Protocol.copyExactly(input, fileOutput, metadata.size(), digest);
                }
                if (!HashUtils.toHex(digest.digest()).equals(metadata.sha256())) {
                    return TransferResult.failure("HASH_MISMATCH",
                            "downloaded content failed SHA-256 verification", fileId);
                }
                moveWithoutOverwrite(temporary, normalizedDestination);
                moved = true;
                return TransferResult.success("downloaded to " + normalizedDestination, fileId);
            } catch (FileAlreadyExistsException e) {
                return TransferResult.failure("DESTINATION_EXISTS",
                        "destination appeared during download; overwrite refused", fileId);
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private static Socket connect(String host, int port) throws IOException {
        return connect(host, port, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
    }

    private static Socket connect(String host, int port,
                                  int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static TransferResult parseError(String response, UUID fileId) throws IOException {
        if (response == null) {
            throw new IOException("peer closed the connection without a response");
        }
        if (!response.startsWith("ERROR ")) {
            return null;
        }
        String[] parts = response.split(" ", 3);
        String code = parts.length > 1 ? parts[1] : "UNKNOWN";
        String message = parts.length > 2 ? parts[2] : "peer reported an error";
        return TransferResult.failure(code, message, fileId);
    }

    private static int parseMetadataLength(String value) throws IOException {
        try {
            int length = Integer.parseInt(value);
            if (length < 1 || length > Protocol.MAX_METADATA_SIZE) {
                throw new IOException("invalid metadata length: " + length);
            }
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("invalid metadata length: " + value, e);
        }
    }

    private static int parseInventoryLength(String value) throws IOException {
        try {
            int length = Integer.parseInt(value);
            if (length < 2 || length > Protocol.MAX_INVENTORY_SIZE) {
                throw new IOException("invalid inventory length: " + length);
            }
            return length;
        } catch (NumberFormatException e) {
            throw new IOException("invalid inventory length: " + value, e);
        }
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
}
