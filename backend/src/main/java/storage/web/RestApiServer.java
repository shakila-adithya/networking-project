package storage.web;

import storage.cluster.PeerEndpoint;
import storage.cluster.PeerRegistry;
import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.files.HashUtils;
import storage.link.HeartbeatManager;
import storage.messaging.ChatService;
import storage.messaging.MeshNode;
import storage.routing.RoutingTable;
import storage.transfer.MeshFileService;
import storage.transfer.TransferStatus;
import storage.transport.PeerClient;
import storage.transport.Protocol;
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
import java.util.function.Supplier;
import java.util.concurrent.Executors;

/** Small browser-facing HTTP bridge. The peer TCP protocol remains unchanged. */
public final class RestApiServer implements AutoCloseable {
    public static final int DEFAULT_PORT_OFFSET = 1_000;
    private static final long MAX_UPLOAD_SIZE = Protocol.MAX_FILE_SIZE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private storage.security.MeshSecurity security;
    public void attachSecurity(storage.security.MeshSecurity security) { this.security = security; }
    private final HttpServer server;
    private final FileStore fileStore;
    private final String localPeerId;
    private final String advertisedAddress;
    private final int peerPort;
    private final HeartbeatManager heartbeatManager;
    private final PeerRegistry registry;
    private volatile MeshNode mesh;
    private volatile ChatService chat;
    private volatile String meshTransport = "none";
    private volatile Supplier<List<RoutingTable.Route>> meshRoutes = List::of;
    private volatile MeshFileService meshFiles;
    private volatile Supplier<Map<String, Object>> bluetoothStatus = () -> null;
    private volatile DeviceApi deviceApi;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RestApiServer(String bindAddress, int port, String localPeerId,
                         String advertisedAddress, int peerPort,
                         FileStore fileStore, HeartbeatManager heartbeatManager,
                         PeerRegistry registry) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.fileStore = fileStore;
        this.localPeerId = localPeerId;
        this.advertisedAddress = advertisedAddress;
        this.peerPort = peerPort;
        this.heartbeatManager = heartbeatManager;
        this.registry = registry;
        server.createContext("/api/health", this::health);
        server.createContext("/api/dashboard", this::dashboard);
        server.createContext("/api/files", this::files);
        server.createContext("/api/mesh", this::meshApi);
        server.createContext("/api/device", this::deviceApiHandler);
        server.createContext("/api/nearby", this::deviceApiHandler);
        server.createContext("/api/bluetooth", this::deviceApiHandler);
        server.setExecutor(executor);
    }

    public void start() { server.start(); }

    /** Exposes mesh status and chat. {@code transportKind} is shown to the user, e.g. "tcp" (IP link). */
    public void attachMesh(MeshNode mesh, ChatService chat, String transportKind,
                           Supplier<List<RoutingTable.Route>> routes, MeshFileService files) {
        this.meshFiles = files;
        this.meshRoutes = routes;
        this.mesh = mesh;
        this.chat = chat;
        this.meshTransport = transportKind;
    }

    /** Exposes Bluetooth adapter state (null supplier result = Bluetooth not in use). */
    public void attachBluetooth(Supplier<Map<String, Object>> status) {
        this.bluetoothStatus = status;
    }

    /** Exposes this device's name/visibility, the nearby-devices list, scan and connect. */
    public void attachDevice(DeviceApi api) {
        this.deviceApi = api;
    }

    private void deviceApiHandler(HttpExchange exchange) throws IOException {
        if (!LocalUserGuard.allowed(exchange)) {
            sendError(exchange, 403, "LOCAL_ONLY", "Open Relay on this laptop using localhost."); return;
        }
        addCors(exchange);
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        DeviceApi api = deviceApi;
        if (api == null) {
            sendError(exchange, 503, "DEVICE_UNAVAILABLE", "the device service is not running");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/api/device") && "GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 200, api.deviceStatus());
            } else if (path.equals("/api/device") && "POST".equalsIgnoreCase(method)) {
                Map<?, ?> request = JSON.readValue(exchange.getRequestBody().readNBytes(4 * 1024), Map.class);
                Object name = request.get("displayName");
                Object visible = request.get("visible");
                if ((name != null && !(name instanceof String)) || (visible != null && !(visible instanceof Boolean))) {
                    sendError(exchange, 400, "INVALID_DEVICE", "displayName must be text and visible must be true or false");
                    return;
                }
                try {
                    sendJson(exchange, 200, api.updateDevice((String) name, (Boolean) visible));
                } catch (IllegalArgumentException e) {
                    sendError(exchange, 400, "INVALID_NAME", e.getMessage());
                }
            } else if (path.equals("/api/nearby") && "GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 200, api.nearby());
            } else if (path.equals("/api/bluetooth/scan") && "POST".equalsIgnoreCase(method)) {
                if (!api.scanNow()) {
                    sendError(exchange, 409, "BLUETOOTH_OFF", "Bluetooth is not in use on this device");
                    return;
                }
                sendJson(exchange, 202, Map.of("scanning", true));
            } else {
                sendError(exchange, 404, "NOT_FOUND", "unknown endpoint");
            }
        } catch (IOException e) {
            sendError(exchange, 500, "DEVICE_ERROR", String.valueOf(e.getMessage()));
        }
    }

    /** A device the mesh can reach: a neighbour or a destination in the routing table. */
    private boolean meshKnows(String deviceId) {
        MeshNode node = mesh;
        if (node == null || meshFiles == null || deviceId.equals(localPeerId)
                || !deviceId.matches("[A-Za-z0-9_-]{1,40}")) {
            return false;
        }
        return node.neighbourIds().contains(deviceId)
                || meshRoutes.get().stream().anyMatch(route -> route.destination().equals(deviceId));
    }

    private void meshApi(HttpExchange exchange) throws IOException {
        if (!LocalUserGuard.allowed(exchange)) {
            sendError(exchange, 403, "LOCAL_ONLY", "Open Relay on this laptop using localhost."); return;
        }
        addCors(exchange);
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        MeshNode node = mesh;
        ChatService service = chat;
        if (node == null || service == null) {
            sendError(exchange, 503, "MESH_UNAVAILABLE", "mesh is not running on this device");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/api/mesh") && "GET".equalsIgnoreCase(method)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("deviceId", node.localId());
                body.put("deviceName", node.displayName().isEmpty() ? node.localId() : node.displayName());
                body.put("names", node.names());
                body.put("links", node.neighbourKinds());
                body.put("linkAddresses", node.neighbourAddresses());
                body.put("transport", meshTransport);
                body.put("neighbours", new java.util.TreeSet<>(node.neighbourIds()));
                body.put("routes", meshRoutes.get());
                Map<String, Object> bluetooth = bluetoothStatus.get();
                if (bluetooth != null) body.put("bluetooth", bluetooth);
                body.put("inbox", service.inbox());
                sendJson(exchange, 200, body);
            } else if (path.equals("/api/mesh/chat") && "POST".equalsIgnoreCase(method)) {
                Map<?, ?> request = JSON.readValue(exchange.getRequestBody().readNBytes(16 * 1024), Map.class);
                Object to = request.get("to");
                Object text = request.get("text");
                if (!(to instanceof String target) || !(text instanceof String message)) {
                    sendError(exchange, 400, "INVALID_CHAT", "to and text are required strings");
                    return;
                }
                UUID id = service.send(target, message);
                sendJson(exchange, 202, Map.of("chatId", id, "status", service.status(id)));
            } else if (path.startsWith("/api/mesh/chat/") && "GET".equalsIgnoreCase(method)) {
                UUID id = UUID.fromString(path.substring("/api/mesh/chat/".length()));
                sendJson(exchange, 200, Map.of("chatId", id, "status", service.status(id)));
            } else if (path.equals("/api/mesh/transfers") && "GET".equalsIgnoreCase(method)) {
                MeshFileService files = meshFiles;
                sendJson(exchange, 200, Map.of("transfers", files == null ? List.of() : files.transfers(),
                        "incoming", files == null ? List.of() : files.incomingTransfers()));
            } else if (path.startsWith("/api/mesh/transfers/") && meshFiles != null) {
                String rest = path.substring("/api/mesh/transfers/".length());
                boolean resume = rest.endsWith("/resume");
                UUID id = UUID.fromString(resume ? rest.substring(0, rest.length() - "/resume".length()) : rest);
                if (resume && "POST".equalsIgnoreCase(method)) {
                    if (!meshFiles.resume(id)) {
                        sendError(exchange, 409, "CANNOT_RESUME", "This transfer cannot be resumed");
                        return;
                    }
                    sendJson(exchange, 202, meshFiles.status(id).orElseThrow());
                } else if (!resume && "GET".equalsIgnoreCase(method)) {
                    var status = meshFiles.status(id);
                    if (status.isEmpty()) {
                        sendError(exchange, 404, "NOT_FOUND", "unknown transfer");
                        return;
                    }
                    sendJson(exchange, 200, status.get());
                } else {
                    sendError(exchange, 405, "METHOD_NOT_ALLOWED", "unsupported method");
                }
            } else if (path.equals("/api/mesh/connect") && "POST".equalsIgnoreCase(method)) {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    sendError(exchange, 403, "LOCAL_ONLY", "connect is only allowed from this device");
                    return;
                }
                Map<?, ?> request = JSON.readValue(exchange.getRequestBody().readNBytes(4 * 1024), Map.class);
                DeviceApi api = deviceApi;
                if (request.get("deviceId") instanceof String deviceId && api != null) {
                    // A device found by a scan or by LAN discovery: its address is looked up on our side.
                    try {
                        Object selectedAddress = request.get("candidateAddress");
                        if (selectedAddress != null && !(selectedAddress instanceof String)) {
                            sendError(exchange, 400, "INVALID_ADDRESS", "candidateAddress must be text");
                            return;
                        }
                        api.connectDevice(deviceId, (String) selectedAddress);
                    } catch (IOException e) {
                        sendError(exchange, 502, "CONNECT_FAILED", e.getMessage());
                        return;
                    }
                    sendJson(exchange, 202, Map.of("connecting", deviceId));
                    return;
                }
                if (!(request.get("address") instanceof String address)) {
                    sendError(exchange, 400, "INVALID_ADDRESS", "address or deviceId is required");
                    return;
                }
                try {
                    node.connectTo(address);
                } catch (IOException e) {
                    sendError(exchange, 502, "CONNECT_FAILED", e.getMessage());
                    return;
                }
                sendJson(exchange, 202, Map.of("connecting", address));
            } else {
                sendError(exchange, 404, "NOT_FOUND", "unknown mesh endpoint");
            }
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "INVALID_REQUEST", String.valueOf(e.getMessage()));
        }
    }

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
        if (!LocalUserGuard.allowed(exchange)) {
            sendError(exchange, 403, "LOCAL_ONLY", "Open Relay on this laptop using localhost."); return;
        }
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
        if (!LocalUserGuard.allowed(exchange)) {
            sendError(exchange, 403, "LOCAL_ONLY", "Open Relay on this laptop using localhost."); return;
        }
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
                item.put("locations", List.of(localPeerId));
                item.put("recipients", metadata.recipients() == null ? List.of() : metadata.recipients());
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
        try {
            fileStore.open(fileId);
            fileStore.delete(fileId);
        } catch (FileStore.StorageException e) {
            sendError(exchange, 404, e.code(), e.getMessage());
            return;
        }
        sendJson(exchange, 200, Map.of("fileId", fileId, "deleted", true));
    }

    private void upload(HttpExchange exchange) throws IOException {
        String recipient = exchange.getRequestURI().getRawQuery();
        if (recipient != null) {
            if (!recipient.startsWith("recipient=") || recipient.contains("&")) {
                sendError(exchange, 400, "INVALID_RECIPIENT", "Choose a peer or all connected peers"); return;
            }
            try { recipient = URLDecoder.decode(recipient.substring(10), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { sendError(exchange, 400, "INVALID_RECIPIENT", "Invalid recipient"); return; }
        }
        List<PeerEndpoint> destinations = null;
        List<String> meshTargets = new ArrayList<>();
        if (recipient != null) {
            final String selected = recipient;
            List<PeerEndpoint> known = registry == null ? List.of() : registry.endpoints();
            destinations = known.stream().filter(p -> selected.equals("all")
                    ? p.name().equals(localPeerId) || (heartbeatManager != null && heartbeatManager.statuses().stream()
                        .anyMatch(h -> h.peer().name().equals(p.name()) && h.state() == HeartbeatManager.PeerState.ALIVE))
                    : p.name().equals(selected)).toList();
            if (destinations.isEmpty()) {
                // Not a directly registered peer: deliver over the mesh if the mesh can reach that device.
                if (!selected.equals("all") && meshKnows(selected)) meshTargets.add(selected);
                else { sendError(exchange, 400, "INVALID_RECIPIENT", "Selected peer is not registered. Refresh the peer list."); return; }
            }
        }
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
            List<String> recipientNames = new ArrayList<>();
            if (destinations != null) destinations.forEach(d -> recipientNames.add(d.name()));
            recipientNames.addAll(meshTargets);
            FileMetadata metadata = new FileMetadata(UUID.randomUUID(), fileName, size,
                    HashUtils.sha256(temporary), Instant.now().toString(), recipient == null ? null : recipientNames);
            if (recipient != null) {
                List<String> delivered = new ArrayList<>();
                Map<String, String> failed = new LinkedHashMap<>();
                Map<String, UUID> transfers = new LinkedHashMap<>();
                for (var target : destinations) {
                    try {
                        if (target.name().equals(localPeerId)) {
                            try (var session = fileStore.reserveUpload(metadata); var input = Files.newInputStream(temporary)) {
                                session.receive(input);
                            }
                        } else {
                            try {
                                var transfer = security == null ? PeerClient.deliver(target.host(), target.port(), metadata, temporary)
                                        : PeerClient.authenticated(security, () -> PeerClient.deliver(target.host(), target.port(), metadata, temporary));
                                if (!transfer.success()) throw new IOException(transfer.code() + ": " + transfer.message());
                            } catch (IOException direct) {
                                // The direct TCP path failed: route the file over the mesh instead, if possible.
                                if (meshFiles == null || !startMeshTransfer(target.name(), metadata, temporary, transfers)) throw direct;
                                continue;
                            }
                        }
                        delivered.add(target.name());
                    } catch (IOException | FileStore.StorageException e) { failed.put(target.name(), Protocol.safeMessage(e.getMessage())); }
                }
                for (String target : meshTargets) {
                    if (!startMeshTransfer(target, metadata, temporary, transfers)) failed.put(target, "Could not start the mesh transfer");
                }
                boolean started = !transfers.isEmpty();
                String shareStatus = !failed.isEmpty() ? "INCOMPLETE" : started ? "TRANSFERRING" : "COMPLETE";
                int code = delivered.isEmpty() && !started ? 502 : !failed.isEmpty() ? 207 : started ? 202 : 201;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("fileId", metadata.fileId());
                body.put("deliveredTo", delivered);
                body.put("failedPeers", failed);
                body.put("transfers", transfers.entrySet().stream()
                        .map(e -> Map.of("peer", e.getKey(), "transferId", e.getValue())).toList());
                body.put("shareStatus", shareStatus);
                body.put("message", delivered.isEmpty() && !started ? "No recipient accepted this file. Ensure all devices run the updated application."
                        : started ? "Sending over the mesh" : "Share processed");
                sendJson(exchange, code, body);
                return;
            }
            try (FileStore.UploadSession session = fileStore.reserveUpload(metadata);
                 InputStream input = Files.newInputStream(temporary)) {
                session.receive(input);
            }
            sendJson(exchange, 201, Map.of("fileId", metadata.fileId(), "fileName", metadata.fileName(),
                    "size", metadata.size(), "sha256", metadata.sha256()));
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "INVALID_FILE_NAME", e.getMessage());
        } catch (FileStore.StorageException e) {
            sendError(exchange, 409, e.code(), e.getMessage());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Starts a routed transfer of the uploaded file. The service copies the file, so the caller may delete it. */
    private boolean startMeshTransfer(String target, FileMetadata metadata, Path source, Map<String, UUID> transfers) {
        MeshFileService files = meshFiles;
        if (files == null) return false;
        try {
            TransferStatus status = files.send(target, metadata, source);
            transfers.put(target, status.transferId());
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not start mesh transfer to " + target + ": " + e.getMessage());
            return false;
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
        exchange.getResponseHeaders().set("Vary", "Origin");
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

    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
