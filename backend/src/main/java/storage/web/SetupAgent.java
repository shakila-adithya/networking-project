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
    private final storage.bluetooth.BluetoothMeshDirectory bluetoothDirectory;
    private final storage.platform.NetworkManagerService nativeNetwork;
    final storage.discovery.MeshDirectory directory;
    private final SetupConfigStore store;
    private final Path ui;
    private final String startupDeviceId;
    private final storage.device.DeviceProfileStore localProfile;
    private volatile String visibilityError = "";
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
        localProfile = new storage.device.DeviceProfileStore(store.directory().resolve("device-profile.json"));
        Path identityFile = store.directory().resolve("device-id.txt");
        Files.createDirectories(store.directory());
        String identity = "";
        if (store.exists()) { try { identity = store.load().peer().localPeerId(); } catch (IOException invalid) { /* start() reports damaged managed configuration */ } }
        if (identity.isEmpty() && Files.exists(identityFile)) identity = Files.readString(identityFile).strip();
        if (!identity.matches("[A-Za-z0-9_-]{1,40}")) identity = "device_" + UUID.randomUUID().toString().substring(0, 12);
        startupDeviceId = identity;
        if (!Files.exists(identityFile)) Files.writeString(identityFile, identity, StandardOpenOption.CREATE_NEW);
        bluetoothDirectory = new storage.bluetooth.BluetoothMeshDirectory(store.directory().resolve("bluetooth-restore.properties"));
        nativeNetwork = new storage.platform.NetworkManagerService();
        this.ui = ui.toAbsolutePath().normalize();
        this.networks = networks;
        directory = new storage.discovery.MeshDirectory(() -> active == null ? null : active.peer(), endpoint -> {
            ManagedPeer current = node;
            if (current != null) current.offerCandidate(endpoint);
        });
        directory.identity(this::publicDevice);
        http.createContext("/api/relay-storage", this::relayStorage);
        http.createContext("/api/visibility", this::visibility);
        http.createContext("/api/discovery", this::discovery);
        http.createContext("/api/radio", this::radio);
        http.createContext("/api/membership", this::membership);
        http.createContext("/api/setup", this::setup);
        http.createContext("/api/", this::proxy);
        http.createContext("/", this::staticFile);
        http.setExecutor(requests);
    }
    public int port() { return http.getAddress().getPort(); }
    public void start() {
        http.start();
        directory.start();
        applyIdleVisibility();
        if (store.exists()) {
            try { saved = store.load(); if (store.autoJoin()) restart(); else phase = "LEFT"; }
            catch (Exception e) { phase = "ERROR"; error = "Cannot load saved configuration. Apply valid settings to recover."; }
        }
    }
    private storage.discovery.MeshDirectory.DeviceAdvertisement publicDevice() {
        var profile = localProfile.load();
        var config = active;
        String id = saved == null ? startupDeviceId : saved.peer().localPeerId();
        return new storage.discovery.MeshDirectory.DeviceAdvertisement("Relay-device-v3", id, profile.displayName(),
                config == null ? "" : config.peer().clusterId(), config == null ? 0 : config.peer().port(), profile.visible());
    }
    private void applyIdleVisibility() {
        if (node != null) return;
        var adapter = bluetoothDirectory.adapter();
        if (adapter == null) return;
        try {
            var d = publicDevice();
            if (d.visible()) adapter.advertise(d.deviceId(), d.displayName(), null);
            else adapter.stopAdvertising();
            visibilityError = "";
        } catch (Exception e) { visibilityError = "Bluetooth visibility failed: " + e.getMessage(); }
    }
    private Map<String, Object> visibilityStatus() {
        var d = publicDevice();
        return Map.of("deviceId", d.deviceId(), "visible", d.visible(), "displayName", d.displayName(),
                "phase", phase, "bluetoothAvailable", bluetoothDirectory.adapter() != null,
                "bluetoothReason", bluetoothDirectory.reason(), "error", !visibilityError.isBlank() || bluetoothDirectory.adapter() == null
                        ? visibilityError : bluetoothDirectory.adapter().discoveryLimitation());
    }
    private void visibility(HttpExchange exchange) throws IOException {
        if (!localManagement(exchange)) { json(exchange, 403, Map.of("message", "Manage visibility on this device using localhost.")); return; }
        if (exchange.getRequestMethod().equals("GET")) { json(exchange, 200, visibilityStatus()); return; }
        if (!exchange.getRequestMethod().equals("POST")) { json(exchange, 405, Map.of("message", "Use GET or POST")); return; }
        try {
            byte[] bytes = exchange.getRequestBody().readNBytes(1025);
            if (bytes.length > 1024) { json(exchange, 413, Map.of("message", "Visibility request too large")); return; }
            JsonNode input = JSON.readTree(bytes);
            if (input == null || !input.isObject() || input.size() != 1 || !input.path("visible").isBoolean())
                throw new IllegalArgumentException("Choose whether this device is visible.");
            boolean visible = input.path("visible").asBoolean();
            setVisibility(visible);
            json(exchange, 200, visibilityStatus());
        } catch (IllegalArgumentException e) { json(exchange, 400, Map.of("message", e.getMessage())); }
        catch (IOException e) { json(exchange, 409, Map.of("message", "Could not save visibility. Check local file permissions.")); }
    }
    private synchronized void setVisibility(boolean visible) throws IOException {
                if (closed || restarting) throw new IllegalArgumentException("Wait for Relay to finish reconnecting.");
                var previous = localProfile.load();
                localProfile.save(previous.withVisible(visible));
                if (!visible) {
                    try { store.setAutoJoin(false); }
                    catch (IOException e) { localProfile.save(previous); throw e; }
                    var withdrawal = publicDevice();
                    directory.announce(withdrawal);
                    ManagedPeer old = node; node = null; active = null;
                    if (old != null) old.close();
                    phase = "LEFT";
                } else if (node != null) node.updateVisibility(true);
                applyIdleVisibility(); directory.scan();
    }
    private void relayStorage(HttpExchange exchange) throws IOException {
        if (!localManagement(exchange)) { json(exchange, 403, Map.of("message", "Manage relay storage on this device using localhost.")); return; }
        try {
            var policy = storage.custody.StoragePolicy.load(store.directory());
            if (exchange.getRequestMethod().equals("POST")) {
                byte[] bytes = exchange.getRequestBody().readNBytes(1025);
                if (bytes.length > 1024) { json(exchange, 413, Map.of("message", "Storage settings request too large")); return; }
                var next = JSON.readValue(bytes, storage.custody.StoragePolicy.class);
                synchronized (this) {
                    if (closed || restarting) throw new IllegalArgumentException("Wait for Relay to finish reconnecting.");
                    next.save(store.directory());
                    try { if (node != null) node.configureRelayStorage(next); }
                    catch (IOException | RuntimeException failure) { policy.save(store.directory()); throw failure; }
                }
                policy = next;
            } else if (!exchange.getRequestMethod().equals("GET")) { json(exchange, 405, Map.of("message", "Use GET or POST")); return; }
            var current = node;
            json(exchange, 200, Map.of("policy", policy, "status", current == null
                    ? Map.of("available", false, "error", "Join a mesh to enable encrypted store-and-forward. Pending data remains on disk.") : current.relayStorageStatus()));
        } catch (IllegalArgumentException e) { json(exchange, 400, Map.of("message", e.getMessage())); }
        catch (IOException e) { json(exchange, 409, Map.of("message", "Relay storage settings could not be loaded or saved. Existing data was kept.")); }
    }
    private void discovery(HttpExchange exchange) throws IOException {
        if (!localManagement(exchange)) { json(exchange, 403, Map.of("message", "Open Relay on this device using localhost.")); return; }
        if (!exchange.getRequestMethod().equals("GET")) { json(exchange, 405, Map.of("message", "Use GET")); return; }
        List<Map<String, Object>> devices = new ArrayList<>();
        for (var d : directory.devices()) devices.add(Map.of("deviceId", d.deviceId(), "name", d.displayName().isEmpty() ? d.deviceId() : d.displayName(),
                "meshId", d.meshId(), "address", d.address(), "kind", "lan", "verified", false));
        ManagedPeer current = node;
        for (var d : current == null ? bluetoothDirectory.nearby() : current.bluetoothNearby()) if (d.relayDeviceId().isPresent())
            devices.add(Map.of("deviceId", d.relayDeviceId().orElseThrow(), "name", d.friendlyName().orElse(d.relayDeviceId().orElseThrow()),
                    "meshId", d.meshId().orElse(""), "address", storage.bluetooth.BluetoothAddress.withPrefix(d.address()), "kind", "bluetooth", "verified", false));
        json(exchange, 200, Map.of("devices", devices, "meshes", meshAdvertisements(), "visibility", visibilityStatus(), "error", directory.error()));
    }
    private void radio(HttpExchange exchange) throws IOException {
        if (!LocalUserGuard.allowed(exchange)) { json(exchange, 403, Map.of("message", "Open Relay on this laptop using localhost.")); return; }
        try {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET") && path.equals("/api/radio")) {
                json(exchange, 200, nativeNetwork.status()); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { json(exchange, 405, Map.of("message", "Use POST for radio operations")); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(4097);
            if (bytes.length > 4096) { json(exchange, 413, Map.of("message", "Request too large")); return; }
            JsonNode form = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
            switch (path) {
                case "/api/radio/scan" -> nativeNetwork.scan();
                case "/api/radio/hotspot" -> nativeNetwork.startHotspot(form.path("devicePath").asText(),
                        form.path("ssid").asText(), form.path("password").asText(), form.path("confirmed").asBoolean(false));
                case "/api/radio/connect" -> nativeNetwork.connectP2P(form.path("devicePath").asText(),
                        form.path("address").asText(), form.path("confirmed").asBoolean(false));
                case "/api/radio/stop" -> nativeNetwork.stop(form.path("confirmed").asBoolean(false));
                default -> { json(exchange, 404, Map.of("message", "Unknown radio operation")); return; }
            }
            json(exchange, 202, nativeNetwork.status());
        } catch (IllegalArgumentException e) { json(exchange, 400, Map.of("message", e.getMessage())); }
        catch (IOException e) { json(exchange, 409, Map.of("message", e.getMessage())); }
    }
    /** Mesh membership is owned by this local agent, independently of the browser session. */
    private void membership(HttpExchange exchange) throws IOException {
        if (!LocalUserGuard.allowed(exchange)) {
            json(exchange, 403, Map.of("message", "Open Relay on this laptop using localhost.")); return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/api/membership")) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("meshId", active == null ? "" : active.peer().clusterId());
                response.put("phase", active != null && node != null && node.connected() ? "CONNECTED" : phase);
                response.put("nearby", meshAdvertisements());
                response.put("bluetoothReason", bluetoothDirectory.reason());
                response.put("bluetoothAvailable", bluetoothDirectory.adapter() != null);
                response.put("scanning", bluetoothDirectory.scanning());
                response.put("discoveryError", directory.error());
                response.put("error", error);
                json(exchange, 200, response); return;
            }
            if (method.equals("GET") && path.equals("/api/membership/password")) {
                // Loopback-only (LocalUserGuard above): lets the owner share the password with devices joining later.
                var current = active;
                if (current == null) { json(exchange, 404, Map.of("message", "This device is not in a mesh.")); return; }
                json(exchange, 200, Map.of("meshId", current.peer().clusterId(), "password", current.peer().discoverySecret()));
                return;
            }
            if (!method.equals("POST")) { json(exchange, 405, Map.of("message", "Use POST for mesh operations")); return; }
            if (path.equals("/api/membership/scan")) {
                directory.scan();
                ManagedPeer current = node;
                if (current == null || !current.scanBluetooth()) bluetoothDirectory.scan();
                json(exchange, 202, Map.of("message", "Listening for nearby LAN and Bluetooth meshes")); return;
            }
            if (path.equals("/api/membership/leave")) {
                synchronized (this) {
                    if (restarting) throw new IllegalArgumentException("Wait for the current operation to finish.");
                    store.setAutoJoin(false); // If persistence fails, leave the current connection intact.
                    ManagedPeer previous = node;
                    // Remove membership before stopping the node, so announcements immediately cease.
                    node = null; active = null;
                    if (previous != null) previous.close();
                    // Retain device/storage settings; startup must not silently rejoin a mesh we left.
                    phase = "LEFT";
                }
                json(exchange, 200, Map.of("message", "Left mesh. Received files are preserved.")); return;
            }
            if (!Set.of("/api/membership/create", "/api/membership/join").contains(path)) {
                json(exchange, 404, Map.of("message", "Unknown mesh operation")); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            if (body.length > 4096) { json(exchange, 413, Map.of("message", "Request too large")); return; }
            JsonNode form = JSON.readTree(body);
            String meshId = form.path("meshId").asText("");
            String password = form.path("password").asText("");
            var security = new storage.security.MeshSecurity(meshId, password);
            synchronized (this) {
                if (restarting) throw new IllegalArgumentException("Wait for the current operation to finish.");
                if (active != null) throw new IllegalArgumentException("Leave the current mesh before joining another mesh.");
                if (path.endsWith("/join")) {
                    String address = form.path("address").asText("");
                    String peerId = form.path("peerId").asText("");
                    var selected = meshAdvertisements().stream().filter(item -> item.get("meshId").equals(meshId)
                            && item.get("address").equals(address) && item.get("peerId").equals(peerId)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("This mesh is no longer nearby. Scan again."));
                    // Authenticate the selected advertisement before writing any settings or reporting membership.
                    storage.transport.Transport probe;
                    String target;
                    if ("bluetooth".equals(selected.get("kind"))) {
                        var adapter = bluetoothDirectory.adapter();
                        if (adapter == null) throw new IOException(bluetoothDirectory.reason());
                        probe = new storage.bluetooth.BluetoothTransport(storage.bluetooth.BluetoothPlatform.rfcomm(), adapter.address(),
                                Integer.getInteger("mesh.bluetooth.channel", storage.bluetooth.BluetoothTransport.DEFAULT_CHANNEL));
                        target = address;
                    } else {
                        probe = new storage.transport.TcpTransport("127.0.0.1", 0);
                        target = address + ":" + (((Number)selected.get("peerPort")).intValue() + 2000);
                    }
                    try (probe; var raw = probe.connect(target);
                         var authenticated = security.authenticate(raw, true, "mesh")) { }
                }
                SetupConfigStore.Saved previous = saved;
                String id = previous == null ? startupDeviceId : previous.peer().localPeerId();
                String address = form.path("localAddress").asText(previous == null ? "" : previous.peer().advertisedAddress());
                var detected = networks.get();
                if (address.isBlank() && detected.size() == 1) address = detected.getFirst().address();
                if (detected.isEmpty()) address = "127.0.0.1";
                String chosen = address;
                if (!detected.isEmpty() && detected.stream().noneMatch(item -> item.address().equals(chosen)))
                    throw new IllegalArgumentException("Choose an active local network address.");
                int peerPort = previous == null ? availablePeerPort() : previous.peer().port();
                int restPort = previous == null ? suggestPorts().get("restPort") : previous.restPort();
                String storage = previous == null ? "storage-data/" + id : previous.peer().storageDirectory();
                var peer = new PeerConfiguration(id, "0.0.0.0", address, peerPort, storage,
                        List.of(new PeerConfiguration.PeerDefinition(id, address, peerPort)), meshId, true,
                        PeerConfiguration.DEFAULT_DISCOVERY_GROUP, PeerConfiguration.DEFAULT_DISCOVERY_PORT, password);
                var next = new SetupConfigStore.Saved(peer, restPort);
                store.save(next); store.setAutoJoin(true); saved = next;
                new storage.device.DeviceProfileService(new storage.device.DeviceProfileStore(store.directory().resolve("device-profile.json")))
                        .update(null, true);
                restart();
            }
            json(exchange, 202, Map.of("message", "Mesh authenticated/configured. Starting local connections."));
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("message", e.getMessage()));
        } catch (IOException e) {
            json(exchange, 409, Map.of("message", "Mesh operation failed: " + e.getMessage()));
        }
    }
    private List<Map<String, Object>> meshAdvertisements() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var item : directory.nearby()) result.add(Map.of("meshId", item.meshId(), "peerId", item.peerId(),
                "address", item.address(), "peerPort", item.peerPort(), "kind", "lan", "verified", false));
        ManagedPeer current = node;
        List<storage.bluetooth.Nearby> radio = current == null ? bluetoothDirectory.nearby() : current.bluetoothNearby();
        for (var item : radio) if (item.meshId().isPresent() && item.relayDeviceId().isPresent()) {
            result.add(Map.of("meshId", item.meshId().orElseThrow(), "peerId", item.relayDeviceId().orElseThrow(),
                    "address", storage.bluetooth.BluetoothAddress.withPrefix(item.address()), "peerPort", 0,
                    "kind", "bluetooth", "verified", false));
        }
        return result;
    }
    private int availablePeerPort() throws IOException {
        for (int attempt = 0; attempt < 30; attempt++) {
            try (ServerSocket peer = new ServerSocket(0)) {
                int port = peer.getLocalPort();
                if (port > 63535) continue;
                try (ServerSocket mesh = new ServerSocket(port + 2000)) { return port; }
                catch (IOException busy) { /* Try another ephemeral port. */ }
            }
        }
        throw new IOException("No free peer and mesh port pair found");
    }
    private void setup(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                if (path.equals("/api/setup")) { json(exchange, 200, status()); return; }
                if (path.equals("/api/setup/device")) {
                    if (!localManagement(exchange)) { json(exchange, 403, Map.of("message", "Open Settings on this device using localhost.")); return; }
                    json(exchange, 200, localDeviceName()); return;
                }
                if (path.equals("/api/setup/networks")) {
                    json(exchange, 200, Map.of("networks", networks.get(), "ports", suggestPorts())); return;
                }
            }
            if (!"POST".equals(exchange.getRequestMethod())) { json(exchange, 405, Map.of("message", "Unsupported setup operation")); return; }
            if (!localManagement(exchange)) {
                json(exchange, 403, Map.of("message", "Open setup on this laptop using http://localhost:" + port()
                        + ". Applying configuration is allowed only from the local laptop.")); return;
            }
            if (path.equals("/api/setup/device")) {
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) { json(exchange, 413, Map.of("message", "The device name request is too large.")); return; }
                JsonNode form;
                try { form = JSON.readTree(body); }
                catch (IOException e) { json(exchange, 400, Map.of("message", "Enter a device name.")); return; }
                if (form == null || !form.isObject() || form.size() != 1 || !form.path("displayName").isTextual()) {
                    json(exchange, 400, Map.of("message", "Only a device name can be changed here.")); return;
                }
                synchronized (this) {
                    if (closed || restarting) throw new IllegalArgumentException("Relay is reconnecting. Wait a moment before changing its name.");
                    if (node != null) node.updateDeviceName(form.path("displayName").asText());
                    else new storage.device.DeviceProfileService(new storage.device.DeviceProfileStore(store.directory().resolve("device-profile.json")))
                            .update(form.path("displayName").asText(), null);
                    json(exchange, 200, localDeviceName());
                }
                return;
            }
            if (path.equals("/api/setup/network")) {
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) { json(exchange, 413, Map.of("message", "The network request is too large.")); return; }
                JsonNode form;
                try { form = JSON.readTree(body); }
                catch (IOException e) { json(exchange, 400, Map.of("message", "Choose a current local network.")); return; }
                if (form == null || !form.isObject() || form.size() != 1 || !form.path("address").isTextual()) {
                    json(exchange, 400, Map.of("message", "Only the local network can be changed here.")); return;
                }
                synchronized (this) {
                    if (closed || restarting) throw new IllegalArgumentException("Relay is reconnecting. Wait for it to finish.");
                    if (saved == null) throw new IllegalArgumentException("Create or join a mesh on the Network map first.");
                    if (!store.autoJoin() || phase.equals("LEFT")) throw new IllegalArgumentException("You left the mesh. Join again from the Network map first.");
                    var old = saved.peer();
                    if (old.discoverySecret() == null || old.discoverySecret().length() < 16)
                        throw new IllegalArgumentException("This saved mesh needs a password. Use Advanced settings or join from the Network map.");
                    String address = form.path("address").asText();
                    List<LocalNetworks.Candidate> detected = networks.get();
                    boolean bluetoothWithoutLan = address.equals("127.0.0.1") && (detected.isEmpty() || old.advertisedAddress().equals(address));
                    if (!bluetoothWithoutLan && detected.stream().noneMatch(item -> item.address().equals(address)))
                        throw new IllegalArgumentException("That network is no longer available. Refresh networks and choose again.");
                    var peers = old.peers().stream().map(peer -> peer.id().equals(old.localPeerId())
                            ? new PeerConfiguration.PeerDefinition(peer.id(), address, old.port()) : peer).toList();
                    var updated = new PeerConfiguration(old.localPeerId(), old.bindAddress(), address, old.port(), old.storageDirectory(),
                            peers, old.clusterId(), old.discoveryEnabled(), old.discoveryGroup(), old.discoveryPort(), old.discoverySecret());
                    var next = new SetupConfigStore.Saved(updated, saved.restPort());
                    store.save(next); saved = next;
                    restart();
                }
                json(exchange, 202, Map.of("message", "Network saved. Relay is reconnecting. Your mesh password and files are kept.")); return;
            }
            if (path.equals("/api/setup/restart")) {
                restart(); json(exchange, 202, Map.of("message", "Relay is reconnecting.")); return;
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
                    store.setAutoJoin(true);
                    saved = candidate;
                    phase = "RESTART_REQUIRED";
                    error = "";
                }
                json(exchange, 200, Map.of("valid", true, "restartRequired", path.endsWith("/apply"),
                        "message", path.endsWith("/apply") ? "Settings saved. Restart Relay to use them."
                                : "Local settings are valid. Cluster membership is confirmed only after discovery and heartbeat."));
            }
        } catch (IllegalArgumentException e) {
            // Only our explicit validation errors reach this branch, never Jackson exception text.
            json(exchange, 400, Map.of("message", e.getMessage()));
        } catch (Exception e) {
            json(exchange, 500, Map.of("message", "Setup failed. Check local file permissions and available ports, then retry."));
        }
    }
    /** Device preferences belong to this local app, even when its mesh service is stopped. */
    private Map<String, Object> localDeviceName() {
        ManagedPeer current = node;
        if (current != null) {
            var status = current.deviceStatus();
            return Map.of("deviceId", status.get("deviceId"), "customName", status.get("customName"), "displayName", status.get("displayName"));
        }
        var profile = new storage.device.DeviceProfileStore(store.directory().resolve("device-profile.json")).load();
        String id = saved == null ? "" : saved.peer().localPeerId();
        return Map.of("deviceId", id, "customName", profile.displayName(), "displayName", profile.effectiveName(id.isEmpty() ? "This device" : id));
    }
    private boolean localManagement(HttpExchange exchange) { return LocalUserGuard.allowed(exchange); }
    SetupConfigStore.Saved validate(Join join) throws IOException {
        if (join == null) throw new IllegalArgumentException("Enter the device settings.");
        var detected = networks.get();
        boolean bluetoothWithoutLan = "127.0.0.1".equals(join.address()) && (detected.isEmpty()
                || saved != null && saved.peer().advertisedAddress().equals(join.address()));
        if (!bluetoothWithoutLan && detected.stream().noneMatch(n -> n.address().equals(join.address())))
            throw new IllegalArgumentException("Choose a current Wi-Fi or Ethernet network. Refresh networks if your connection changed.");
        if (node != null && node.registry.members().stream().anyMatch(m -> m.endpoint().name().equals(join.peerId())
                && !m.endpoint().name().equals(node.configuration.localPeerId())))
            throw new IllegalArgumentException("That peer ID is already in use. Choose a unique ID.");
        if (join.peerPort() > 63535 && System.getProperty("mesh.port") == null)
            throw new IllegalArgumentException("Choose a device port below 63536 so Relay also has room for its mesh port.");
        List<PeerConfiguration.PeerDefinition> peers = new ArrayList<>();
        if (saved != null) for (var peer : saved.peer().peers()) {
            if (peer.id().equals(saved.peer().localPeerId())) continue;
            if (peer.id().equals(join.peerId())) throw new IllegalArgumentException("That device ID is already saved for another device. Choose a different ID.");
            peers.add(peer);
        }
        peers.add(new PeerConfiguration.PeerDefinition(join.peerId(), join.address(), join.peerPort()));
        PeerConfiguration config;
        try {
            config = new PeerConfiguration(join.peerId(), "0.0.0.0", join.address(), join.peerPort(),
                    saved == null ? "storage-data/" + join.peerId() : saved.peer().storageDirectory(), peers,
                    join.clusterId(), true, join.discoveryGroup(), join.discoveryPort(), join.secret());
        } catch (Exception e) {
            throw new IllegalArgumentException("Check your Device ID (1–40 letters, numbers, _ or -), mesh name (1–80 letters, numbers, . _ -), password (16–256 characters), and port numbers.");
        }
        if (join.clusterId() == null || join.clusterId().isBlank()) throw new IllegalArgumentException("Enter a mesh name.");
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
                ManagedPeer started = new ManagedPeer(next, bluetoothDirectory.adapter(), store.directory().resolve("device-profile.json"));
                synchronized (this) {
                    if (closed) { started.close(); return; }
                    node = started;
                    active = next;
                    phase = "DISCOVERING";
                    directory.scan();
                    directory.nearby().stream().filter(item -> item.meshId().equals(next.peer().clusterId())).forEach(item ->
                            started.offerCandidate(new storage.cluster.PeerEndpoint(item.peerId(), item.address(), item.peerPort())));
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
        if (current != null && state.equals("DISCOVERING") && current.connected()) state = "CONNECTED";

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
        result.put("discovery", Map.of("nearby", directory.nearby().size(), "error", directory.error()));
        result.put("guidance", "If no peers appear: check the exact mesh ID and password, selected local network, and Bluetooth visibility. Upgrade every peer to Relay v2; public discovery uses UDP 45556. Allow Java on the Private network; allow the displayed peer TCP port and discovery UDP port from your trusted subnet. Some Wi-Fi networks block multicast or isolate devices. No firewall settings are changed automatically.");
        return result;
    }
    private void proxy(HttpExchange exchange) throws IOException {
        // The node's REST API sees every proxied request as coming from 127.0.0.1, so its own loopback check
        // cannot protect these endpoints. Enforce "this laptop only" here, where the real caller is known.
        String requestPath = exchange.getRequestURI().getPath();
        if (!localManagement(exchange)) {
            json(exchange, 403, Map.of("message", "Open Relay on this laptop using localhost.")); return;
        }
        var target = active;
        if (target == null || node == null) {
            String code = phase.equals("ERROR") ? "SERVICE_UNAVAILABLE" : phase.equals("STARTING") ? "MESH_STARTING" : "MESH_NOT_JOINED";
            String message = phase.equals("ERROR") ? "Relay could not start. Check the connection details in Settings."
                    : phase.equals("STARTING") ? "Relay is starting your mesh connections."
                    : "Relay isn't connected to a mesh yet. Join one on the Network map, or check your connection in Settings.";
            json(exchange, 503, Map.of("code", code, "message", message)); return;
        }
        byte[] deviceBody = null;
        if (requestPath.equals("/api/device") && exchange.getRequestMethod().equals("POST")) {
            deviceBody = exchange.getRequestBody().readNBytes(4097);
            if (deviceBody.length > 4096) { json(exchange, 413, Map.of("message", "Device request too large")); return; }
            JsonNode patch;
            try { patch = JSON.readTree(deviceBody); } catch (IOException e) { json(exchange, 400, Map.of("message", "Invalid device settings")); return; }
            if (patch != null && patch.has("visible")) {
                if (!patch.path("visible").isBoolean() || patch.size() != 1) { json(exchange, 400, Map.of("message", "Change visibility separately from your device name.")); return; }
                try { setVisibility(patch.path("visible").asBoolean()); json(exchange, 200, visibilityStatus()); }
                catch (IllegalArgumentException e) { json(exchange, 400, Map.of("message", e.getMessage())); }
                return;
            }
        }
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
                try (OutputStream out = connection.getOutputStream()) { if (deviceBody != null) out.write(deviceBody); else exchange.getRequestBody().transferTo(out); }
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
        directory.close();
        nativeNetwork.close();
        lifecycle.shutdown();
        try {
            // A node start/stop in progress gets a short grace period, then is interrupted.
            if (!lifecycle.awaitTermination(5, TimeUnit.SECONDS)) lifecycle.shutdownNow();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); lifecycle.shutdownNow(); }
        if (node != null) node.close();
        bluetoothDirectory.close();
        requests.shutdownNow();
    }
}
