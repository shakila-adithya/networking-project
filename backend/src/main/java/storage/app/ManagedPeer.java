package storage.app;

import storage.cluster.PeerRegistry;
import storage.config.PeerConfiguration;
import storage.device.DeviceProfileService;
import storage.device.DeviceProfileStore;
import storage.config.SetupConfigStore;
import storage.link.HeartbeatManager;
import storage.transport.PeerServer;
import storage.web.RestApiServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** An owned node lifecycle for setup mode; CLI mode remains supported separately. */
public final class ManagedPeer implements AutoCloseable {
    public final PeerConfiguration configuration;
    public final PeerRegistry registry;
    public HeartbeatManager heartbeat;
    private PeerServer server;
    private RestApiServer rest;
    private MeshRuntime mesh;
    private final java.util.concurrent.ConcurrentHashMap<String, storage.cluster.PeerEndpoint> observed = new java.util.concurrent.ConcurrentHashMap<>();
    public ManagedPeer(SetupConfigStore.Saved saved) throws IOException { this(saved, null); }
    public ManagedPeer(SetupConfigStore.Saved saved, storage.bluetooth.LocalBluetooth sharedBluetooth) throws IOException {
        this(saved, sharedBluetooth, Path.of("config", "device-profile.json"));
    }
    public ManagedPeer(SetupConfigStore.Saved saved, storage.bluetooth.LocalBluetooth sharedBluetooth, Path profilePath) throws IOException {
        configuration = saved.peer();
        if (java.net.NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(configuration.advertisedAddress())) == null)
            throw new IOException("The configured local network address is no longer assigned to this device. Choose an active network in Settings.");
        String id = configuration.localPeerId();
        var security = new storage.security.MeshSecurity(configuration.clusterId(), configuration.discoverySecret());
        registry = new PeerRegistry(id, configuration.peerEndpoints());
        try {
            server = new PeerServer(configuration.bindAddress(), configuration.port(), configuration.storagePath(), id).secure(security);
            heartbeat = new HeartbeatManager(id, registry.endpoints(), security);
            // REST is internal to the setup agent. Browsers always use the agent's stable same-origin URL.
            rest = new RestApiServer("127.0.0.1", saved.restPort(), id, configuration.advertisedAddress(),
                    configuration.port(), server.fileStore(), heartbeat, registry);
            // Start immediately so failure cleanup releases the bound HTTP port.
            // HttpServer.stop() does not release a server that has never started. Mesh features are attached
            // below; until then those endpoints answer 503.
            rest.attachSecurity(security);
            rest.start();
            mesh = new MeshRuntime(configuration, server.fileStore(), new DeviceProfileService(
                    new DeviceProfileStore(profilePath)), sharedBluetooth);
            rest.attachMesh(mesh.node(), mesh.chat(), mesh.kind(), mesh::routes, mesh.files());
            rest.attachBluetooth(mesh::bluetoothStatus);
            rest.attachDevice(mesh);
            mesh.node().onNeighbourChange(idOfPeer -> {
                var endpoint = observed.get(idOfPeer);
                if (endpoint != null) registry.join(endpoint);
            }, idOfPeer -> registry.updateState(idOfPeer, PeerRegistry.PeerState.SUSPECTED));
            for (var endpoint : registry.endpoints()) mesh.offerPeer(endpoint, true);
            // Discovery is optional (static peer lists work without it), like in CLI mode.
            registry.addListener(member -> heartbeat.refreshPeers(registry.endpoints()));
            heartbeat.addTransitionListener(transition -> registry.updateState(transition.peer().name(),
                    PeerRegistry.PeerState.valueOf(transition.currentState().name())));
            server.start();
            heartbeat.start();
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    /** Unverified discovery candidate: mesh transport authenticates it before it may exchange traffic. */
    public void offerCandidate(storage.cluster.PeerEndpoint endpoint) {
        if (observed.size() < 256 || observed.containsKey(endpoint.name())) observed.put(endpoint.name(), endpoint);
        mesh.offerPeer(endpoint, false);
    }
    public java.util.List<storage.bluetooth.Nearby> bluetoothNearby() { return mesh.bluetoothNearby(); }
    public boolean scanBluetooth() { return mesh != null && mesh.scanNow(); }
    public Map<String, Object> deviceStatus() { return mesh.deviceStatus(); }
    public Map<String, Object> updateDeviceName(String name) throws IOException { return mesh.updateDevice(name, null); }
    public boolean connected() { return mesh != null && !mesh.node().neighbourIds().isEmpty(); }
    @Override public void close() {
        if (rest != null) rest.close();
        if (mesh != null) mesh.close();
        if (heartbeat != null) heartbeat.close();
        if (server != null) server.close();
    }
}
