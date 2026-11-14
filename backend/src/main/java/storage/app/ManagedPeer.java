package storage.app;

import storage.cluster.PeerRegistry;
import storage.cluster.ReconciliationManager;
import storage.cluster.ReplicationManager;
import storage.cluster.SelfHealingManager;
import storage.config.PeerConfiguration;
import storage.device.DeviceProfileService;
import storage.device.DeviceProfileStore;
import storage.config.SetupConfigStore;
import storage.discovery.DiscoveryManager;
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
    public DiscoveryManager discovery;
    private PeerServer server;
    private RestApiServer rest;
    private SelfHealingManager healing;
    private ReconciliationManager reconciliation;
    private MeshRuntime mesh;
    public ManagedPeer(SetupConfigStore.Saved saved) throws IOException {
        configuration = saved.peer();
        String id = configuration.localPeerId();
        registry = new PeerRegistry(id, configuration.peerEndpoints());
        try {
            ReplicationManager replication = new ReplicationManager(id, registry::endpoints);
            server = new PeerServer(configuration.bindAddress(), configuration.port(), configuration.storagePath(), replication);
            heartbeat = new HeartbeatManager(id, registry.endpoints());
            healing = new SelfHealingManager(id, server.fileStore(), replication, heartbeat);
            reconciliation = new ReconciliationManager(id, server.fileStore(), replication, heartbeat, healing);
            // REST is internal to the setup agent. Browsers always use the agent's stable same-origin URL.
            rest = new RestApiServer("127.0.0.1", saved.restPort(), id, configuration.advertisedAddress(),
                    configuration.port(), server.fileStore(), heartbeat, replication);
            // Start immediately so failure cleanup releases the bound HTTP port.
            // HttpServer.stop() does not release a server that has never started. Mesh features are attached
            // below; until then those endpoints answer 503.
            rest.start();
            mesh = new MeshRuntime(configuration, server.fileStore(), new DeviceProfileService(
                    new DeviceProfileStore(Path.of("config", "device-profile.json"))));
            replication.setRoutedDelivery(mesh::sendFile);
            rest.attachMesh(mesh.node(), mesh.chat(), mesh.kind(), mesh::routes, mesh.files());
            rest.attachBluetooth(mesh::bluetoothStatus);
            rest.attachDevice(mesh);
            for (var endpoint : registry.endpoints()) mesh.offerPeer(endpoint, true);
            // Discovery is optional (static peer lists work without it), like in CLI mode.
            if (configuration.discoveryEnabled()) {
                discovery = new DiscoveryManager(configuration, registry, peer -> {
                    heartbeat.refreshPeers(registry.endpoints());
                    mesh.offerPeer(peer, false);
                });
            }
            registry.addListener(member -> heartbeat.refreshPeers(registry.endpoints()));
            heartbeat.addTransitionListener(transition -> registry.updateState(transition.peer().name(),
                    PeerRegistry.PeerState.valueOf(transition.currentState().name())));
            server.start();
            if (discovery != null) discovery.start();
            heartbeat.start();
            healing.start();
            reconciliation.start();
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    @Override public void close() {
        if (rest != null) rest.close();
        if (mesh != null) mesh.close();
        if (discovery != null) discovery.close();
        if (reconciliation != null) reconciliation.close();
        if (healing != null) healing.close();
        if (heartbeat != null) heartbeat.close();
        if (server != null) server.close();
    }
}
