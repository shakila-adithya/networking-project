package storage.app;

import storage.cluster.ReplicationManager;
import storage.bluetooth.BlueZAdapter;
import storage.bluetooth.BluetoothAddress;
import storage.bluetooth.BluetoothScanner;
import storage.bluetooth.BluetoothTransport;
import storage.bluetooth.NativeRfcomm;
import storage.bluetooth.Nearby;
import storage.config.PeerConfiguration;
import storage.device.DeviceProfile;
import storage.device.DeviceProfileService;
import storage.discovery.Candidate;
import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.link.TopologyController;
import storage.link.TopologyPolicy;
import storage.messaging.ChatService;
import storage.messaging.MeshNode;
import storage.routing.AodvRouter;
import storage.routing.LinkCost;
import storage.routing.RoutingTable;
import storage.transfer.MeshFileService;
import storage.transfer.TransferConfig;
import storage.transport.CompositeTransport;
import storage.transport.TcpTransport;
import storage.transport.Transport;
import storage.web.DeviceApi;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The mesh part of a running device: transport(s), the mesh node on top, chat, file transfer and topology control.
 *
 * <p>The carrier is chosen with {@code -Dmesh.transport=tcp|bluetooth|both} (default {@code both}):
 * <ul>
 *   <li>{@code tcp}: IP link over Wi-Fi/LAN ({@link TcpTransport});</li>
 *   <li>{@code bluetooth}: real Bluetooth Classic RFCOMM ({@link BluetoothTransport}, Linux/BlueZ). The device
 *       scans for other Relay devices and connects to them. The device advertises itself ("<name> [relay:<id>]") only
 *       while the user has made it visible;</li>
 *   <li>{@code both}: the two together; falls back to TCP only if Bluetooth is unavailable.</li>
 * </ul>
 * Bluetooth tuning: {@code -Dmesh.bluetooth.channel} (RFCOMM channel, default 22),
 * {@code -Dmesh.bluetooth.scanSeconds} (8), {@code -Dmesh.bluetooth.intervalSeconds} (30).
 */
public final class MeshRuntime implements AutoCloseable, DeviceApi {
    /** Mesh TCP port = peer port + this offset (REST uses +1000). */
    public static final int DEFAULT_PORT_OFFSET = 2_000;

    private final Transport transport;
    private final BlueZAdapter bluetoothAdapter;
    private BluetoothScanner bluetoothScanner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MeshNode node;
    private final ChatService chat;
    private final AodvRouter router;
    private final TopologyController topology;
    private final MeshFileService files;
    private final DeviceProfileService profile;
    private volatile String bluetoothUnavailableReason = "";
    private volatile String advertiseError = "";

    public MeshRuntime(PeerConfiguration configuration, FileStore store, DeviceProfileService profile) throws IOException {
        this.profile = profile;
        String mode = System.getProperty("mesh.transport", "both").trim().toLowerCase();
        if (!List.of("tcp", "bluetooth", "both").contains(mode)) {
            throw new IOException("mesh.transport must be tcp, bluetooth or both (was \"" + mode + "\")");
        }
        List<Transport> carriers = new ArrayList<>();
        BlueZAdapter adapter = null;
        if (!mode.equals("bluetooth")) {
            int port = Integer.getInteger("mesh.port", configuration.port() + DEFAULT_PORT_OFFSET);
            carriers.add(new TcpTransport(configuration.bindAddress(), port));
        }
        if (!mode.equals("tcp")) {
            try {
                if (!NativeRfcomm.isSupported()) {
                    throw new IOException("RFCOMM Bluetooth sockets are not available on this machine "
                            + "(Linux with the 'rfcomm' kernel module is required)");
                }
                adapter = BlueZAdapter.open(Path.of("config", "bluetooth-restore.properties"));
                NativeRfcomm rfcomm = new NativeRfcomm();
                int channel = Integer.getInteger("mesh.bluetooth.channel", BluetoothTransport.DEFAULT_CHANNEL);
                try {
                    rfcomm.listen(channel).close(); // is the channel free? (another Relay on this computer may hold it)
                } catch (IOException e) {
                    throw new IOException("RFCOMM channel " + channel + " cannot be used: " + e.getMessage()
                            + " (is another Relay running on this computer?)", e);
                }
                carriers.add(new BluetoothTransport(rfcomm, adapter.address(), channel));
            } catch (IOException e) {
                if (adapter != null) {
                    adapter.close();
                    adapter = null;
                }
                if (mode.equals("bluetooth")) {
                    throw new IOException("Bluetooth is not available: " + e.getMessage(), e);
                }
                bluetoothUnavailableReason = String.valueOf(e.getMessage());
                System.err.println("Bluetooth is not available, continuing with TCP only: " + e.getMessage());
            }
        }
        bluetoothAdapter = adapter;
        transport = carriers.size() == 1 ? carriers.get(0) : new CompositeTransport(carriers);
        // Hop count until measured link quality (RTT/loss/signal strength) is available per link.
        router = new AodvRouter(LinkCost.HOP_COUNT, Duration.ofSeconds(2), 3, Duration.ofSeconds(60));
        node = new MeshNode(configuration.localPeerId(), transport, router);
        chat = new ChatService(node, 4, Duration.ofSeconds(2));
        // Tuning for slow radios and for testing: -Dmesh.chunkIntervalMillis=<pause between chunks>,
        // -Dmesh.stallSeconds=<give up (resumable) after this long without any acknowledgement>.
        TransferConfig defaults = TransferConfig.defaults();
        files = new MeshFileService(node, store, new TransferConfig(defaults.chunkSize(), defaults.window(),
                defaults.chunkTimeout(), Duration.ofSeconds(Integer.getInteger("mesh.stallSeconds",
                        (int) defaults.stallTimeout().toSeconds())),
                Duration.ofMillis(Integer.getInteger("mesh.chunkIntervalMillis", 0)), defaults.offerRetry()));
        topology = new TopologyController(node, TopologyPolicy.defaults(), this::reachableIds);
        try {
            node.start();
            topology.start();
            startBluetoothScanner(configuration.localPeerId());
            profile.onChange(this::applyProfile);
            applyProfile(profile.profile());
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Starts looking for other Relay devices. This device itself stays hidden until the profile says visible. */
    private void startBluetoothScanner(String localDeviceId) {
        if (bluetoothAdapter == null) {
            return;
        }
        bluetoothScanner = new BluetoothScanner(bluetoothAdapter, topology, localDeviceId,
                Duration.ofSeconds(Integer.getInteger("mesh.bluetooth.scanSeconds", 8)),
                Duration.ofSeconds(Integer.getInteger("mesh.bluetooth.intervalSeconds", 30)));
        bluetoothScanner.start();
    }

    /** Applies the user's name and visibility: tell the mesh the name, and (un)advertise over Bluetooth. */
    private void applyProfile(DeviceProfile settings) {
        node.setDisplayName(settings.displayName());
        if (bluetoothAdapter == null) {
            return;
        }
        try {
            if (settings.visible()) {
                bluetoothAdapter.advertise(node.localId(), settings.displayName());
            } else {
                bluetoothAdapter.stopAdvertising();
            }
            advertiseError = "";
        } catch (IOException | RuntimeException e) {
            // Scanning and connecting still work; only being found by others failed.
            advertiseError = String.valueOf(e.getMessage());
            System.err.println("Bluetooth: could not change visibility: " + e.getMessage());
        }
    }

    /** Adapter state and the devices seen in the last scan, or null when Bluetooth is not in use. */
    public Map<String, Object> bluetoothStatus() {
        BluetoothScanner scanner = bluetoothScanner;
        if (bluetoothAdapter == null || scanner == null) {
            return null;
        }
        List<Map<String, Object>> nearby = new ArrayList<>();
        for (Nearby device : scanner.lastScan()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("address", device.address());
            item.put("name", device.name());
            item.put("rssi", device.rssi());
            item.put("relayDevice", device.relayDeviceId().orElse(null));
            nearby.add(item);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("adapter", bluetoothAdapter.address());
        status.put("advertisedAs", bluetoothAdapter.name());
        status.put("advertising", bluetoothAdapter.isAdvertising());
        status.put("lastError", scanner.lastError());
        status.put("scanning", scanner.isScanning());
        status.put("lastScanAt", scanner.lastScanAt());
        status.put("nearby", nearby);
        return status;
    }

    public MeshNode node() {
        return node;
    }

    public MeshFileService files() {
        return files;
    }

    /** Starts a routed file transfer; used as the fallback when a direct connection to a peer fails. */
    public boolean sendFile(String peerName, FileMetadata metadata, Path content) {
        try {
            files.send(peerName, metadata, content);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not start mesh transfer to " + peerName + ": " + e.getMessage());
            return false;
        }
    }

    public ChatService chat() {
        return chat;
    }

    private Set<String> reachableIds() {
        return router.routes().stream().filter(RoutingTable.Route::valid)
                .map(RoutingTable.Route::destination).collect(Collectors.toSet());
    }

    /**
     * Tells the topology controller about a device seen nearby. Its mesh port is assumed to follow the
     * same offset rule as ours. {@code permanent} is for configured peers, which are always candidates.
     */
    public void offerPeer(ReplicationManager.PeerEndpoint endpoint, boolean permanent) {
        if (endpoint.name().equals(node.localId())) {
            return;
        }
        Candidate candidate = new Candidate(endpoint.name(), endpoint.host() + ":" + (endpoint.port() + DEFAULT_PORT_OFFSET), 50);
        if (permanent) {
            topology.offerPermanent(candidate);
        } else {
            topology.offer(candidate);
        }
    }

    public List<TopologyController.CandidateStatus> topologyStatus() {
        return topology.status();
    }

    public List<RoutingTable.Route> routes() {
        return router.routes();
    }

    public String kind() {
        return transport.kind();
    }

    // ================= DeviceApi =================

    @Override
    public Map<String, Object> deviceStatus() {
        DeviceProfile settings = profile.profile();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("deviceId", node.localId());
        status.put("displayName", settings.effectiveName(node.localId()));
        status.put("customName", settings.displayName());
        status.put("visible", settings.visible());
        status.put("transport", transport.kind());
        Map<String, Object> bluetooth = new LinkedHashMap<>();
        bluetooth.put("available", bluetoothAdapter != null);
        if (bluetoothAdapter != null) {
            bluetooth.put("adapter", bluetoothAdapter.address());
            bluetooth.put("advertising", bluetoothAdapter.isAdvertising());
            bluetooth.put("advertisedAs", bluetoothAdapter.name());
            bluetooth.put("error", advertiseError);
        } else {
            bluetooth.put("reason", bluetoothUnavailableReason.isEmpty()
                    ? "Bluetooth is turned off for this run (mesh.transport=tcp)" : bluetoothUnavailableReason);
        }
        status.put("bluetooth", bluetooth);
        return status;
    }

    @Override
    public Map<String, Object> updateDevice(String displayName, Boolean visible) throws IOException {
        profile.update(displayName, visible);
        return deviceStatus();
    }

    @Override
    public Map<String, Object> nearby() {
        Set<String> neighbours = node.neighbourIds();
        Map<String, String> names = node.names();
        Map<String, RoutingTable.Route> routes = new java.util.HashMap<>();
        for (RoutingTable.Route route : router.routes()) {
            if (route.valid()) {
                routes.put(route.destination(), route);
            }
        }
        Map<String, TopologyController.CandidateStatus> candidates = new java.util.HashMap<>();
        for (TopologyController.CandidateStatus status : topology.status()) {
            candidates.put(status.candidate().deviceId(), status);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        BluetoothScanner scanner = bluetoothScanner;
        if (scanner != null) {
            for (Nearby device : scanner.lastScan()) {
                String id = device.relayDeviceId().orElse(null);
                boolean connected = id != null && neighbours.contains(id);
                TopologyController.CandidateStatus candidate = id == null ? null : candidates.get(id);
                String state = connected ? "CONNECTED" : id == null ? "OTHER"
                        : candidate != null && candidate.failures() > 0 ? "FAILED" : "AVAILABLE";
                String name = device.friendlyName().orElse(id != null && names.containsKey(id) ? names.get(id)
                        : device.name() == null ? device.address() : device.name());
                items.add(nearbyItem("bluetooth", id, name, BluetoothAddress.withPrefix(device.address()),
                        device.signal(), device.rssi(), id != null, state,
                        candidate == null ? "" : candidate.lastError(), hops(id, connected, routes)));
            }
        }
        for (TopologyController.CandidateStatus status : topology.status()) {
            String address = status.candidate().address();
            if (address.startsWith(BluetoothAddress.PREFIX)) {
                continue; // Bluetooth devices are listed from the scan above
            }
            String id = status.candidate().deviceId();
            boolean connected = neighbours.contains(id);
            items.add(nearbyItem("lan", id, names.getOrDefault(id, id), address, null, null, true,
                    connected ? "CONNECTED" : status.failures() > 0 ? "FAILED" : "AVAILABLE",
                    status.lastError(), hops(id, connected, routes)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bluetoothAvailable", bluetoothAdapter != null);
        result.put("scanning", scanner != null && scanner.isScanning());
        result.put("lastScanAt", scanner == null ? 0L : scanner.lastScanAt());
        result.put("items", items);
        return result;
    }

    private static Integer hops(String id, boolean connected, Map<String, RoutingTable.Route> routes) {
        if (connected) {
            return 1;
        }
        RoutingTable.Route route = id == null ? null : routes.get(id);
        return route == null ? null : route.hops();
    }

    private static Map<String, Object> nearbyItem(String kind, String deviceId, String name, String address,
                                                  Integer signal, Integer rssi, boolean relay, String state,
                                                  String error, Integer hops) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("deviceId", deviceId);
        item.put("name", name);
        item.put("address", address);
        item.put("signal", signal);
        item.put("rssi", rssi);
        item.put("relay", relay);
        item.put("state", state);
        item.put("error", error);
        item.put("hops", hops);
        return item;
    }

    @Override
    public boolean scanNow() {
        BluetoothScanner scanner = bluetoothScanner;
        if (scanner == null) {
            return false;
        }
        scanner.scanNow();
        return true;
    }

    @Override
    public void connectDevice(String deviceId) throws IOException {
        topology.connectNow(deviceId);
    }

    public String address() {
        return transport.localAddress();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // already closed (normal exit and the shutdown hook may both call this)
        }
        if (bluetoothScanner != null) {
            bluetoothScanner.close();
        }
        topology.close();
        files.close();
        chat.close();
        node.close();
        if (bluetoothAdapter != null) {
            bluetoothAdapter.close(); // puts the adapter's name and discoverable setting back
        }
    }
}
