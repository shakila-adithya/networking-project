package storage.transport;

import storage.cluster.ReplicationManager;
import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.files.InventoryJson;
import storage.files.MetadataJson;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PeerServer implements AutoCloseable {
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 30_000;
    private final String bindAddress;
    private final int port;
    private final FileStore fileStore;
    private final ReplicationManager replicationManager;
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public PeerServer(String bindAddress, int port) throws IOException {
        this(bindAddress, port, Path.of("storage-data", "default"));
    }

    public PeerServer(String peerName, String bindAddress, int port) throws IOException {
        this(bindAddress, port, peerDirectory(peerName),
                ReplicationManager.initialConfiguration(peerName));
    }

    public PeerServer(String bindAddress, int port, Path storageDirectory) throws IOException {
        this(bindAddress, port, storageDirectory, null);
    }

    public PeerServer(String bindAddress, int port, Path storageDirectory,
                      ReplicationManager replicationManager) throws IOException {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.fileStore = new FileStore(Objects.requireNonNull(storageDirectory, "storageDirectory"));
        this.replicationManager = replicationManager;
    }

    private static Path peerDirectory(String peerName) {
        Objects.requireNonNull(peerName, "peerName");
        if (!peerName.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("peer name must contain only letters, numbers, '_' or '-'");
        }
        return Path.of("storage-data", peerName);
    }

    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("server is already running");
        }
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName(bindAddress));
        running = true;
        acceptThread = new Thread(new AcceptLoop(this), "peer-accept-" + port);
        acceptThread.setDaemon(true); // close() stops it; daemon so a missed close cannot keep the JVM alive
        acceptThread.start();
    }

    public int port() {
        return port;
    }

    public Path storageDirectory() {
        return fileStore.root();
    }

    public FileStore fileStore() {
        return fileStore;
    }

    /** Runs on the accept thread: one blocking accept() per connection, then hand off to a ClientHandler. */
    void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    System.err.println("Could not accept peer connection: " + e.getMessage());
                }
                continue;
            }
            dispatch(client);
        }
    }

    private void dispatch(Socket client) {
        activeClients.add(client);
        try {
            Thread worker = new Thread(new ClientHandler(this, client), "peer-client-" + client.getPort());
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | Error failure) {
            // Ownership did not transfer to a worker, so this server must release the socket.
            activeClients.remove(client);
            try {
                client.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            System.err.println("Could not start handler for " + client.getRemoteSocketAddress() + ": " + failure);
        }
    }

    /** Serves one connection; the calling ClientHandler owns the socket and it is closed here. */
    void handleClient(Socket client) {
        try (client;
             InputStream input = new BufferedInputStream(client.getInputStream());
             OutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            client.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
            String request = Protocol.readLine(input);
            if (request == null) {
                return;
            }
            if (request.equals("PING")) {
                Protocol.writeLine(output, "PONG");
            } else if (request.equals("LIST_FILES")) {
                handleListFiles(output);
            } else if (request.startsWith("PUT ")) {
                handlePut(request, input, output, "PUT", true);
            } else if (request.startsWith("REPLICA_PUT ")) {
                handlePut(request, input, output, "REPLICA_PUT", false);
            } else if (request.startsWith("GET ")) {
                handleGet(request, output);
            } else if (request.startsWith("DELETE ")) {
                handleDelete(request, output);
            } else {
                Protocol.writeLine(output, "ERROR unsupported request");
            }
        } catch (IOException e) {
            System.err.println("Peer connection ended: " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("Peer handler failed: " + e);
        } finally {
            activeClients.remove(client);
        }
    }

    private void handleListFiles(OutputStream output) throws IOException {
        try {
            byte[] json = InventoryJson.encode(fileStore.inventory());
            Protocol.writeLine(output, "FILES " + json.length);
            output.write(json);
            output.flush();
        } catch (Protocol.ProtocolException e) {
            sendError(output, e.code(), e.getMessage());
        }
    }

    public java.util.List<FileMetadata> inventory() throws IOException {
        return fileStore.inventory();
    }

    private void handlePut(String request, InputStream input, OutputStream output,
                           String expectedCommand, boolean replicate) throws IOException {
        FileStore.UploadSession session = null;
        try {
            String[] parts = request.split(" ", -1);
            if (parts.length != 2 || !parts[0].equals(expectedCommand)) {
                throw new Protocol.ProtocolException("INVALID_PUT",
                        "expected " + expectedCommand + " <metadata-length>");
            }
            int metadataLength = parseMetadataLength(parts[1]);
            FileMetadata metadata = MetadataJson.decode(Protocol.readExactly(input, metadataLength));
            if (metadata.recipients() != null && (replicationManager == null
                    || !metadata.recipients().contains(replicationManager.localPeerName()))) {
                throw new Protocol.ProtocolException("NOT_A_RECIPIENT", "This peer is not a selected recipient");
            }
            session = "REPLICA_PUT".equals(expectedCommand)
                    ? fileStore.reserveReplicaUpload(metadata)
                    : fileStore.reserveUpload(metadata);
            Protocol.writeLine(output, "READY");
            session.receive(input);
            session.close();
            session = null;

            ReplicationManager.ReplicationResult replication = null;
            if (replicate && replicationManager != null && metadata.recipients() == null) {
                try {
                    replication = replicationManager.replicate(fileStore.open(metadata.fileId()));
                } catch (FileStore.StorageException | IOException e) {
                    replication = ReplicationManager.ReplicationResult.failure(
                            "REPLICATION_ERROR", e.getMessage(), null);
                }
            }

            if (replication != null && !replication.success()) {
                String reason = replication.code() + ": " + Protocol.safeMessage(replication.message());
                System.err.printf("Replication failed for %s: %s%n", metadata.fileId(), reason);
                Protocol.writeLine(output,
                        "OK STORED " + metadata.fileId() + " REPLICATION_FAILED " + reason);
            } else {
                Protocol.writeLine(output, "OK STORED " + metadata.fileId());
            }
        } catch (Protocol.ProtocolException e) {
            sendError(output, e.code(), e.getMessage());
        } catch (FileStore.StorageException e) {
            sendError(output, e.code(), e.getMessage());
        } catch (EOFException e) {
            sendError(output, "INCOMPLETE_TRANSFER", e.getMessage());
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private void handleGet(String request, OutputStream output) throws IOException {
        try {
            String[] parts = request.split(" ", -1);
            if (parts.length != 2) {
                throw new Protocol.ProtocolException("INVALID_GET", "expected GET <file-id>");
            }
            UUID fileId;
            try {
                fileId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                throw new Protocol.ProtocolException("INVALID_FILE_ID", "file ID must be a UUID");
            }
            FileStore.StoredFile stored = fileStore.open(fileId);
            Protocol.writeLine(output, "FILE " + stored.metadataJson().length);
            output.write(stored.metadataJson());
            try (InputStream fileInput = Files.newInputStream(stored.contentPath())) {
                Protocol.copyExactly(fileInput, output, stored.metadata().size(), null);
            }
            output.flush();
        } catch (Protocol.ProtocolException e) {
            sendError(output, e.code(), e.getMessage());
        } catch (FileStore.StorageException e) {
            sendError(output, e.code(), e.getMessage());
        }
    }

    private void handleDelete(String request, OutputStream output) throws IOException {
        try {
            String[] parts = request.split(" ", -1);
            if (parts.length != 2) {
                throw new Protocol.ProtocolException("INVALID_DELETE", "expected DELETE <file-id>");
            }
            UUID fileId;
            try {
                fileId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                throw new Protocol.ProtocolException("INVALID_FILE_ID", "file ID must be a UUID");
            }
            fileStore.open(fileId);
            fileStore.delete(fileId);
            Protocol.writeLine(output, "OK DELETED " + fileId);
        } catch (Protocol.ProtocolException e) {
            sendError(output, e.code(), e.getMessage());
        } catch (FileStore.StorageException e) {
            sendError(output, e.code(), e.getMessage());
        }
    }

    private static int parseMetadataLength(String value) throws Protocol.ProtocolException {
        try {
            int length = Integer.parseInt(value);
            if (length < 1 || length > Protocol.MAX_METADATA_SIZE) {
                throw new Protocol.ProtocolException("INVALID_METADATA_LENGTH",
                        "metadata length must be between 1 and " + Protocol.MAX_METADATA_SIZE);
            }
            return length;
        } catch (NumberFormatException e) {
            throw new Protocol.ProtocolException("INVALID_METADATA_LENGTH", "metadata length must be a number");
        }
    }

    private static void sendError(OutputStream output, String code, String message) throws IOException {
        Protocol.writeLine(output, "ERROR " + code + " " + Protocol.safeMessage(message));
    }

    @Override
    public synchronized void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("Could not close peer server: " + e.getMessage());
            }
        }
        for (Socket client : activeClients) {
            try {
                client.close();
            } catch (IOException e) {
                System.err.println("Could not close client connection: " + e.getMessage());
            }
        }
        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try {
                acceptThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
