package storage;

import java.io.IOException;
import java.util.*;

/** An owned node lifecycle for setup mode; CLI mode remains supported separately. */
final class ManagedPeer implements AutoCloseable {
    final PeerConfiguration configuration;
    final PeerRegistry registry;
    HeartbeatManager heartbeat;
    DiscoveryManager discovery;
    private PeerServer server;
    private RestApiServer rest;
    private SelfHealingManager healing;
    private ReconciliationManager reconciliation;
    ManagedPeer(SetupConfigStore.Saved saved) throws IOException {
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
            // HttpServer.stop() does not release a server that has never started.
            rest.start();
            discovery = new DiscoveryManager(configuration, registry, peer -> heartbeat.refreshPeers(registry.endpoints()));
            registry.addListener(member -> heartbeat.refreshPeers(registry.endpoints()));
            heartbeat.addTransitionListener(transition -> registry.updateState(transition.peer().name(),
                    PeerRegistry.PeerState.valueOf(transition.currentState().name())));
            server.start();
            discovery.start();
            heartbeat.start();
            healing.start();
            reconciliation.start();
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    @Override public void close() {
        if (rest != null) rest.close();
        if (discovery != null) discovery.close();
        if (reconciliation != null) reconciliation.close();
        if (healing != null) healing.close();
        if (heartbeat != null) heartbeat.close();
        if (server != null) server.close();
    }
}
