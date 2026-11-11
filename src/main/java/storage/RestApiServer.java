package storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Small browser-facing HTTP bridge. The peer TCP protocol remains unchanged. */
public final class RestApiServer implements AutoCloseable {
    public static final int DEFAULT_PORT_OFFSET = 1_000;
    private static final long MAX_UPLOAD_SIZE = Protocol.MAX_FILE_SIZE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final FileStore fileStore;
    private final String localPeerId;
    private final String advertisedAddress;
    private final int peerPort;
    private final HeartbeatManager heartbeatManager;
    private final ReplicationManager replicationManager;

    public RestApiServer(String bindAddress, int port, String localPeerId,
                         String advertisedAddress, int peerPort,
                         FileStore fileStore, HeartbeatManager heartbeatManager,
                         ReplicationManager replicationManager) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.fileStore = fileStore;
        this.localPeerId = localPeerId;
        this.advertisedAddress = advertisedAddress;
        this.peerPort = peerPort;
        this.heartbeatManager = heartbeatManager;
        this.replicationManager = replicationManager;
        server.createContext("/api/health", this::health);
        server.createContext("/api/dashboard", this::dashboard);
        server.createContext("/api/files", this::files);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() { server.start(); }

    public int port() { return server.getAddress().getPort(); }

    private void health(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        List<Map<String, Object>> peerData = new ArrayList<>();
        peerData.add(peer(localPeerId, advertisedAddress, peerPort, "ALIVE", 0, "local"));
        for (HeartbeatManager.PeerStatus status : heartbeatManager.statuses()) {
            peerData.add(peer(status.peer().name(), status.peer().host(), status.peer().port(),
                    status.state().name(), status.consecutiveFailures(), "configured"));
        }
        long suspected = peerData.stream().filter(item -> "SUSPECTED".equals(item.get("status"))).count();
        long dead = peerData.stream().filter(item -> "DEAD".equals(item.get("status"))).count();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("healthy", dead == 0 && suspected == 0);
        response.put("status", dead > 0 ? "CRITICAL" : suspected > 0 ? "DEGRADED" : "HEALTHY");
        response.put("peers", peerData);
        sendJson(exchange, 200, response);
    }

    private void dashboard(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        List<FileMetadata> inventory = fileStore.inventory();
        long usedBytes = 0;
        for (FileMetadata metadata : inventory) usedBytes += metadata.size();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("connectedPeers", heartbeatManager.statuses().size() + 1);
        response.put("onlinePeers", heartbeatManager.statuses().stream()
                .filter(status -> status.state() == HeartbeatManager.PeerState.ALIVE).count() + 1);
        response.put("filesStored", inventory.size());
        response.put("usedBytes", usedBytes);
        boolean hasDead = heartbeatManager.statuses().stream()
                .anyMatch(status -> status.state() == HeartbeatManager.PeerState.DEAD);
        boolean hasSuspected = heartbeatManager.statuses().stream()
                .anyMatch(status -> status.state() == HeartbeatManager.PeerState.SUSPECTED);
        response.put("clusterHealth", hasDead ? "CRITICAL" : hasSuspected ? "DEGRADED" : "HEALTHY");
        sendJson(exchange, 200, response);
    }

    private void files(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            addCors(exchange);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/files/") && path.endsWith("/download")) {
                download(exchange);
                return;
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (FileMetadata metadata : fileStore.inventory()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileId", metadata.fileId());
                item.put("fileName", metadata.fileName());
                item.put("size", metadata.size());
                item.put("createdAt", metadata.createdAt());
                item.put("sha256", metadata.sha256());
                item.put("replicaCount", 1);
                item.put("locations", List.of(localPeerId));
                result.add(item);
            }
            sendJson(exchange, 200, Map.of("files", result));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            upload(exchange);
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            delete(exchange);
            return;
        }
        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Only GET, POST, and DELETE are supported");
    }

    private void download(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        UUID fileId;
        try {
            fileId = UUID.fromString(path.substring("/api/files/".length(),
                    path.length() - "/download".length()));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "INVALID_FILE_ID", "file ID must be a UUID");
            return;
        }
        FileStore.StoredFile stored;
        try {
            stored = fileStore.open(fileId);
        } catch (FileStore.StorageException e) {
            sendError(exchange, "NOT_FOUND".equals(e.code()) ? 404 : 409, e.code(), e.getMessage());
            return;
        }
        addCors(exchange);
        String filename = java.net.URLEncoder.encode(stored.metadata().fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"download\"; filename*=UTF-8''" + filename);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (stored.metadata().size() == 0) {
            exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        try (InputStream input = Files.newInputStream(stored.contentPath())) {
            exchange.sendResponseHeaders(200, stored.metadata().size());
            try (OutputStream output = exchange.getResponseBody()) {
                Protocol.copyExactly(input, output, stored.metadata().size(), null);
            }
        }
    }

    private void delete(HttpExchange exchange) throws IOException {
        String prefix = "/api/files/";
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            sendError(exchange, 400, "INVALID_FILE_ID", "file ID must be in the URL");
            return;
        }
        UUID fileId;
        try {
            fileId = UUID.fromString(path.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "INVALID_FILE_ID", "file ID must be a UUID");
            return;
        }
        List<String> failedPeers = new ArrayList<>();
        for (ReplicationManager.PeerEndpoint peer : replicationManager.peers()) {
            if (peer.name().equals(localPeerId)) continue;
            try { PeerClient.delete(peer.host(), peer.port(), fileId); }
            catch (IOException e) { failedPeers.add(peer.name()); }
        }
        try {
            fileStore.open(fileId);
            fileStore.delete(fileId);
        } catch (FileStore.StorageException e) {
            sendError(exchange, 404, e.code(), e.getMessage());
            return;
        }
        sendJson(exchange, failedPeers.isEmpty() ? 200 : 207,
                Map.of("fileId", fileId, "deleted", true, "failedPeers", failedPeers));
    }

    private void upload(HttpExchange exchange) throws IOException {
        String encodedName = exchange.getRequestHeaders().getFirst("X-File-Name");
        if (encodedName == null || encodedName.isBlank()) {
            sendError(exchange, 400, "INVALID_FILE_NAME", "X-File-Name is required");
            return;
        }
        String fileName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(fileStore.root(), "rest-upload-", ".part");
        long size = 0;
        try {
            try (InputStream input = exchange.getRequestBody(); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[Protocol.TRANSFER_BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_UPLOAD_SIZE) {
                        sendError(exchange, 413, "FILE_TOO_LARGE", "file exceeds 100 MB limit");
                        return;
                    }
                    output.write(buffer, 0, count);
                }
            }
            FileMetadata metadata = new FileMetadata(UUID.randomUUID(), fileName, size,
                    HashUtils.sha256(temporary), Instant.now().toString());
            try (FileStore.UploadSession session = fileStore.reserveUpload(metadata);
                 InputStream input = Files.newInputStream(temporary)) {
                session.receive(input);
            }
            ReplicationManager.ReplicationResult replication = replicationManager == null
                    ? ReplicationManager.ReplicationResult.failure("NO_REPLICA_TARGET", "replication is not configured", null)
                    : replicationManager.replicate(fileStore.open(metadata.fileId()));
            sendJson(exchange, 201, Map.of("fileId", metadata.fileId(), "fileName", metadata.fileName(),
                    "size", metadata.size(), "sha256", metadata.sha256(),
                    "replicaCount", replication.success() ? 2 : 1,
                    "replicationStatus", replication.success() ? "HEALTHY" : "REPLICATION_FAILED"));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "INVALID_FILE_NAME", e.getMessage());
        } catch (FileStore.StorageException e) {
            sendError(exchange, 409, e.code(), e.getMessage());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Object> peer(String id, String address, int port, String status,
                                     int failures, String source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("peerId", id);
        item.put("address", address + ":" + port);
        item.put("status", status);
        item.put("failures", failures);
        item.put("source", source);
        return item;
    }

    private boolean allow(HttpExchange exchange, String method) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "unsupported method");
        return false;
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-File-Name");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        addCors(exchange);
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", code, "message", Protocol.safeMessage(message)));
    }

    @Override public void close() { server.stop(0); }
}
