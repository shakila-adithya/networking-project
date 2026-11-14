package storage.web;

import storage.app.ManagedPeer;
import storage.config.LocalNetworks;
import storage.config.PeerConfiguration;
import storage.config.SetupConfigStore;
import storage.link.HeartbeatManager;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Stable UI/API host that is reachable before cluster configuration exists. */
public final class SetupAgent implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final HttpServer http;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    // Daemon thread: a stuck node start can never keep the JVM alive after the agent is closed.
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "setup-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final SetupConfigStore store;
    private final Path ui;
    private final Supplier<List<LocalNetworks.Candidate>> networks;
    private volatile SetupConfigStore.Saved saved;
    private volatile SetupConfigStore.Saved active;
    private volatile ManagedPeer node;
    private volatile String phase = "UNCONFIGURED";
    private volatile String error = "";
    private boolean restarting;
    private boolean closed;

    public record Join(String peerId, String clusterId, String secret, String address,
                       int peerPort, int restPort, String discoveryGroup, int discoveryPort) {
        @Override public String toString() { return "Join[credentials redacted]"; }
    }

    public SetupAgent(int port, SetupConfigStore store, Path ui) throws IOException {
        this(port, store, ui, () -> {
            try { return LocalNetworks.detect(); }
            catch (IOException e) { System.err.println("Could not detect local networks: " + e.getMessage()); return List.of(); }
        });
    }
    public SetupAgent(int port, SetupConfigStore store, Path ui, Supplier<List<LocalNetworks.Candidate>> networks) throws IOException {
        this.http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.store = store;
        this.ui = ui.toAbsolutePath().normalize();
        this.networks = networks;
        http.createContext("/api/setup", this::setup);
        http.createContext("/api/", this::proxy);
        http.createContext("/", this::staticFile);
        http.setExecutor(requests);
    }
    public int port() { return http.getAddress().getPort(); }
    public void start() {
        http.start();
        if (store.exists()) {
            try { saved = store.load(); restart(); }
            catch (Exception e) { phase = "ERROR"; error = "Cannot load saved configuration. Apply valid settings to recover."; }
        }
    }
    private void setup(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                if (path.equals("/api/setup")) { json(exchange, 200, status()); return; }
                if (path.equals("/api/setup/networks")) {
                    json(exchange, 200, Map.of("networks", networks.get(), "ports", suggestPorts())); return;
                }
            }
            if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, Map.of("message", "Unsupported setup operation")); return; }
            if (!localManagement(exchange)) {
                json(exchange, 403, Map.of("message", "Open setup on this laptop using http://localhost:" + port()
                        + ". Applying configuration is allowed only from the local laptop.")); return;
            }
            if (path.equals("/api/setup/restart")) {
                restart(); json(exchange, 202, Map.of("message", "Node restart started")); return;
            }
            if (!path.equals("/api/setup/validate") && !path.equals("/api/setup/apply")) {
                json(exchange, 404, Map.of("message", "Unknown setup operation")); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(16_385);
            if (body.length > 16_384) { json(exchange, 413, Map.of("message", "Setup request is too large")); return; }
            Join join;
            try { join = JSON.readValue(body, Join.class); }
            catch (Exception e) { json(exchange, 400, Map.of("message", "Invalid setup form. Check all fields.")); return; }
            synchronized (this) {
                if (restarting) throw new IllegalArgumentException("Wait for the current node restart to finish.");
                SetupConfigStore.Saved candidate = validate(join);
                if (path.endsWith("/apply")) {
                    store.save(candidate);
                    saved = candidate;
                    phase = "RESTART_REQUIRED";
                    error = "";
                }
                json(exchange, 200, Map.of("valid", true, "restartRequired", path.endsWith("/apply"),
                        "message", path.endsWith("/apply") ? "Configuration saved. Restart the local node to join."
                                : "Local settings are valid. Cluster membership is confirmed only after discovery and heartbeat."));
            }
        } catch (IllegalArgumentException e) {
            // Only our explicit validation errors reach this branch, never Jackson exception text.
            json(exchange, 400, Map.of("message", e.getMessage()));
        } catch (Exception e) {
            json(exchange, 500, Map.of("message", "Setup failed. Check local file permissions and available ports, then retry."));
        }
    }
    private boolean localManagement(HttpExchange exchange) {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        try {
            String hostname = URI.create("http://" + host).getHost();
            if (!Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(hostname)) return false;
            return origin == null || origin.equals("http://" + host) || origin.equals("https://" + host);
        } catch (Exception e) { return false; }
    }
    SetupConfigStore.Saved validate(Join join) throws IOException {
        if (join == null) throw new IllegalArgumentException("Enter the device settings.");
        if (networks.get().stream().noneMatch(n -> n.address().equals(join.address())))
            throw new IllegalArgumentException("Choose an active LAN address from the detected networks. Refresh networks if Wi-Fi changed.");
        if (node != null && node.registry.members().stream().anyMatch(m -> m.endpoint().name().equals(join.peerId())
                && !m.endpoint().name().equals(node.configuration.localPeerId())))
            throw new IllegalArgumentException("That peer ID is already in use. Choose a unique ID.");
        PeerConfiguration config;
        try {
            config = new PeerConfiguration(join.peerId(), "0.0.0.0", join.address(), join.peerPort(),
                    "storage-data/" + join.peerId(), List.of(new PeerConfiguration.PeerDefinition(join.peerId(), join.address(), join.peerPort())),
                    join.clusterId(), true, join.discoveryGroup(), join.discoveryPort(), join.secret());
        } catch (Exception e) {
            throw new IllegalArgumentException("Check peer ID (1–40 letters, numbers, _ or -), cluster ID (1–80 letters, numbers, . _ -), secret (16–256 characters), multicast group and ports (1–65535).");
        }
        if (join.clusterId() == null || join.clusterId().isBlank()) throw new IllegalArgumentException("Cluster ID is required.");
        var candidate = new SetupConfigStore.Saved(config, join.restPort());
        checkPort(join.peerPort());
        checkPort(join.restPort());
        return candidate;
    }
    private void checkPort(int port) throws IOException {
        if (port == port()) throw new IllegalArgumentException("This port is used by the setup agent. Choose another port.");
        if (active != null && (port == active.peer().port() || port == active.restPort())) return;
        try (ServerSocket probe = new ServerSocket()) { probe.bind(new InetSocketAddress("0.0.0.0", port)); }
        catch (IOException e) { throw new IllegalArgumentException("TCP port " + port + " is in use. Choose another port or stop the other node."); }
    }
    private Map<String, Integer> suggestPorts() throws IOException {
        if (saved != null) return Map.of("peerPort", saved.peer().port(), "restPort", saved.restPort());
        try (ServerSocket peer = new ServerSocket(0); ServerSocket rest = new ServerSocket(0)) {
            return Map.of("peerPort", peer.getLocalPort(), "restPort", rest.getLocalPort());
        }
    }
    private synchronized void restart() {
        if (closed || restarting) throw new IllegalArgumentException("Node restart is already in progress.");
        if (saved == null) throw new IllegalArgumentException("Apply a configuration first.");
        restarting = true;
        phase = "STARTING";
        error = "";
        SetupConfigStore.Saved next = saved;
        lifecycle.submit(() -> {
            try {
                ManagedPeer old = node;
                node = null;
                if (old != null) old.close();
                active = null;
                ManagedPeer started = new ManagedPeer(next);
                synchronized (this) {
                    if (closed) { started.close(); return; }
                    node = started;
                    active = next;
                    phase = "DISCOVERING";
                }
            } catch (Exception e) {
                phase = "ERROR";
                error = "Node could not start. Check that the selected network is still connected, multicast is supported, and TCP ports are free. Stop any old Java node and retry Restart.";
            } finally { synchronized (this) { restarting = false; } }
        });
    }
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        ManagedPeer current = node;
        String state = phase;
        if (current != null && state.equals("DISCOVERING") && current.heartbeat.statuses().stream()
                .anyMatch(p -> p.state() == HeartbeatManager.PeerState.ALIVE && p.lastSuccessfulAt() != null)) state = "CONNECTED";
        if (current != null && current.discovery != null && current.discovery.status().identityConflicts() > 0) state = "PEER_ID_CONFLICT";
        result.put("phase", state);
        result.put("error", error);
        result.put("configured", saved != null);
        if (saved != null) {
            var p = saved.peer();
            result.put("settings", Map.of("peerId", p.localPeerId(), "clusterId", p.clusterId(), "address", p.advertisedAddress(),
                    "peerPort", p.port(), "restPort", saved.restPort(), "discoveryGroup", p.discoveryGroup(), "discoveryPort", p.discoveryPort()));
        }
        result.put("peers", current == null ? List.of() : current.registry.members().stream()
                .filter(m -> !m.endpoint().name().equals(current.configuration.localPeerId()))
                .map(m -> {
                    var heartbeat = current.heartbeat.statuses().stream().filter(h -> h.peer().name().equals(m.endpoint().name())).findFirst();
                    return Map.of("id", m.endpoint().name(), "address", m.endpoint().host() + ":" + m.endpoint().port(),
                            "status", heartbeat.map(h -> h.state().name()).orElse("CHECKING"),
                            "verifiedHeartbeat", heartbeat.map(h -> h.lastSuccessfulAt() != null).orElse(false));
                }).toList());
        if (current != null && current.discovery != null) result.put("discovery", current.discovery.status());
        result.put("guidance", "If no peers appear: check the exact cluster ID and shared credential, discovery group/UDP port, device clocks, and selected Wi-Fi. Allow Java on the Private network; allow the displayed peer TCP port and discovery UDP port from your trusted subnet. Some Wi-Fi networks block multicast or isolate devices. No firewall settings are changed automatically.");
        return result;
    }
    private void proxy(HttpExchange exchange) throws IOException {
        // The node's REST API sees every proxied request as coming from 127.0.0.1, so its own loopback check
        // cannot protect these endpoints. Enforce "this laptop only" here, where the real caller is known.
        String requestPath = exchange.getRequestURI().getPath();
        boolean changesThisDevice = requestPath.equals("/api/mesh/connect") || requestPath.equals("/api/bluetooth/scan")
                || (requestPath.equals("/api/device") && exchange.getRequestMethod().equals("POST"));
        if (changesThisDevice && !localManagement(exchange)) {
            json(exchange, 403, Map.of("message", "This can only be changed from this laptop.")); return;
        }
        var target = active;
        if (target == null || node == null) { json(exchange, 503, Map.of("message", "Local node is not running. Open Settings to join a cluster.")); return; }
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + target.restPort()
                + exchange.getRequestURI().toASCIIString()).toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(0);
        connection.setRequestMethod(exchange.getRequestMethod());
        for (String header : List.of("Content-Type", "X-File-Name")) {
            String value = exchange.getRequestHeaders().getFirst(header);
            if (value != null) connection.setRequestProperty(header, value);
        }
        try {
            if (exchange.getRequestMethod().equals("POST")) {
                connection.setDoOutput(true);
                connection.setChunkedStreamingMode(65536);
                try (OutputStream out = connection.getOutputStream()) { exchange.getRequestBody().transferTo(out); }
            }
            int code = connection.getResponseCode();
            for (String header : List.of("Content-Type", "Content-Disposition", "X-Content-Type-Options")) {
                String value = connection.getHeaderField(header);
                if (value != null) exchange.getResponseHeaders().set(header, value);
            }
            long length = connection.getContentLengthLong();
            if (length == 0) exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(code, length == 0 ? -1 : length < 0 ? 0 : length);
            try (InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                 OutputStream out = exchange.getResponseBody()) { if (in != null) in.transferTo(out); }
        } catch (IOException e) { exchange.close(); }
        finally { connection.disconnect(); }
    }
    private void staticFile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean head = exchange.getRequestMethod().equals("HEAD");
        if (!head && !exchange.getRequestMethod().equals("GET")) { json(exchange, 405, Map.of("message", "GET or HEAD only")); return; }
        Path file = ui.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (file.startsWith(ui) && Files.isDirectory(file)) file = file.resolve("index.html");
        if (!file.startsWith(ui) || !Files.isRegularFile(file)) {
            json(exchange, 404, Map.of("message", "UI unavailable. Run npm install and npm run build in frontend, then open this address again.")); return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".woff2") ? "font/woff2"
                : name.endsWith(".woff") ? "font/woff"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                : name.endsWith(".webp") ? "image/webp"
                : name.endsWith(".ico") ? "image/x-icon"
                : name.endsWith(".map") ? "application/json"
                : name.endsWith(".webmanifest") ? "application/manifest+json"
                : name.endsWith(".txt") ? "text/plain; charset=utf-8"
                : name.endsWith(".json") ? "application/json"
                : "text/html; charset=utf-8";
        exchange.getResponseHeaders().set("Content-Type", type);
        if (head) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(Files.size(file)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) { Files.copy(file, out); }
    }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        http.stop(0);
        lifecycle.shutdown();
        try {
            // A node start/stop in progress gets a short grace period, then is interrupted.
            if (!lifecycle.awaitTermination(5, TimeUnit.SECONDS)) lifecycle.shutdownNow();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); lifecycle.shutdownNow(); }
        if (node != null) node.close();
        requests.shutdownNow();
    }
}
