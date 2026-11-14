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
import storage.files.FileMetadata;
import storage.files.InventoryComparator;
import storage.files.TransferResult;
import storage.link.HeartbeatManager;
import storage.transport.PeerClient;
import storage.transport.PeerServer;
import storage.web.RestApiServer;
import storage.web.SetupAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public final class PeerNode {
    private static final String COMMANDS = "ping <host> <port> | put <host> <port> <file-path> | "
            + "get <host> <port> <file-id> <destination> | inventory | status | peers | discovery-status | "
            + "repair-status | reconcile-status | neighbours | topology | routes | name <text> | visible on|off | bluetooth | send-file <device-id> <path> | transfers | mesh-connect <host:meshPort> | "
            + "chat <device-id|*> <text> | inbox | chat-status <chat-id> | help | quit";

    private PeerNode() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--setup")) {
            SetupAgent agent = new SetupAgent(Integer.getInteger("setup.port", 6080),
                    new SetupConfigStore(Path.of("config", "local-setup.json")), Path.of(System.getProperty("ui.dir", "../frontend/dist")));
            Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownTask(agent)));
            agent.start();
            System.out.println("Open http://localhost:" + agent.port() + " and choose Settings → Join existing cluster.");
            System.out.println("Keep this agent running. Press Ctrl+C to stop it.");
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); agent.close(); }
            return;
        }
        PeerConfiguration configuration;
        try {
            if (args.length == 1) {
                configuration = PeerConfiguration.load(Path.of(args[0]));
            } else if (args.length == 3) {
                int port = Integer.parseInt(args[2]);
                configuration = PeerConfiguration.localhost(args[0], args[1], port);
            } else {
                System.err.println("Usage: PeerNode <config.json> or PeerNode <peer-name> <bind-address> <port>");
                return;
            }
        } catch (NumberFormatException e) {
            System.err.println("Port must be a number between 1 and 65535.");
            return;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Could not load peer configuration: " + e.getMessage());
            return;
        }
        run(configuration);
    }

    private static void run(PeerConfiguration configuration) throws IOException {
        String name = configuration.localPeerId();
        PeerRegistry registry = new PeerRegistry(name, configuration.peerEndpoints());
        ReplicationManager replicationManager = new ReplicationManager(name, registry::endpoints);
        int restPort = Integer.getInteger("rest.port", configuration.port() + RestApiServer.DEFAULT_PORT_OFFSET);
        DeviceProfileService profile = new DeviceProfileService(
                new DeviceProfileStore(Path.of("config", "device-profile.json")));
        try (PeerServer server = new PeerServer(configuration.bindAddress(), configuration.port(),
             configuration.storagePath(), replicationManager);
             HeartbeatManager heartbeatManager = new HeartbeatManager(name, registry.endpoints());
             SelfHealingManager selfHealingManager = new SelfHealingManager(
                     name, server.fileStore(), replicationManager, heartbeatManager);
             ReconciliationManager reconciliationManager = new ReconciliationManager(
                     name, server.fileStore(), replicationManager, heartbeatManager, selfHealingManager);
             RestApiServer restApi = new RestApiServer(configuration.bindAddress(), restPort, name,
                     configuration.advertisedAddress(), configuration.port(), server.fileStore(), heartbeatManager,
                     replicationManager);
             MeshRuntime mesh = new MeshRuntime(configuration, server.fileStore(), profile);
             DiscoveryManager discoveryManager = configuration.discoveryEnabled()
                     ? new DiscoveryManager(configuration, registry, endpoint -> {
                         heartbeatManager.refreshPeers(registry.endpoints());
                         mesh.offerPeer(endpoint, false);
                     }) : null) {
            for (var endpoint : registry.endpoints()) mesh.offerPeer(endpoint, true);
            // Ctrl+C / SIGTERM must still restore the Bluetooth adapter's name; closing the mesh is idempotent.
            Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownTask(mesh), "mesh-shutdown"));
            mesh.node().onNeighbourChange(
                    id -> System.out.println("[mesh] neighbour up: " + id),
                    id -> System.out.println("[mesh] neighbour down: " + id));
            mesh.chat().onReceive(chat ->
                    System.out.println("[chat] " + chat.from() + " -> " + chat.to() + ": " + chat.text()));
            replicationManager.setRoutedDelivery(mesh::sendFile);
            restApi.attachMesh(mesh.node(), mesh.chat(), mesh.kind(), mesh::routes, mesh.files());
            restApi.attachBluetooth(mesh::bluetoothStatus);
            restApi.attachDevice(mesh);
            mesh.files().onReceived(file -> System.out.println("[file] received " + file.metadata().fileName()
                    + " (" + file.metadata().size() + " bytes) from " + file.from()));
            // The single owner of System.in; no worker thread reads the console.
            Scanner ScannerInput = new Scanner(System.in);
            registry.addListener(member -> heartbeatManager.refreshPeers(registry.endpoints()));
            heartbeatManager.addTransitionListener(transition ->
                    registry.updateState(transition.peer().name(),
                            PeerRegistry.PeerState.valueOf(transition.currentState().name())));
            server.start();
            heartbeatManager.start();
            restApi.start();
            selfHealingManager.start();
            reconciliationManager.start();
            if (discoveryManager != null) discoveryManager.start();
            System.out.printf("Peer %s listening on %s:%d (advertised as %s:%d)%n",
                    name, configuration.bindAddress(), configuration.port(),
                    configuration.advertisedAddress(), configuration.port());
            System.out.println("REST API: http://127.0.0.1:" + restPort + "/api");
            System.out.println("Mesh (" + mesh.kind() + " link) listening on " + mesh.address());
            if (mesh.bluetoothStatus() != null) {
                System.out.println("Bluetooth: adapter " + mesh.bluetoothStatus().get("adapter") + " advertised as "
                        + mesh.bluetoothStatus().get("advertisedAs") + "; type 'bluetooth' to list nearby devices");
            }
            System.out.println("Storage: " + server.storageDirectory());
            System.out.println("Commands: " + COMMANDS);

            while (ScannerInput.hasNextLine()) {
                String trimmed = ScannerInput.nextLine().trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String command = trimmed.split("\\s+", 2)[0].toLowerCase();
                switch (command) {
                    case "ping" -> ping(trimmed.split("\\s+"));
                    case "put" -> put(trimmed.split("\\s+", 4));
                    case "get" -> get(trimmed.split("\\s+", 5));
                    case "inventory" -> inventory(name, server, heartbeatManager);
                    case "status" -> status(heartbeatManager);
                    case "peers" -> peers(registry);
                    case "discovery-status" -> discoveryStatus(configuration, discoveryManager);
                    case "repair-status" -> repairStatus(selfHealingManager);
                    case "reconcile-status" -> reconcileStatus(reconciliationManager);
                    case "neighbours" -> System.out.println("Neighbours: " + new java.util.TreeSet<>(mesh.node().neighbourIds()));
                    case "topology" -> mesh.topologyStatus().forEach(t -> System.out.printf(
                            "%-12s %-22s signal=%-3d %s failures=%d %s%n", t.candidate().deviceId(),
                            t.candidate().address(), t.candidate().signal(), t.connected() ? "CONNECTED" : "not connected",
                            t.failures(), t.lastError()));
                    case "name" -> deviceName(profile, mesh, trimmed.split("\\s+", 2));
                    case "visible" -> deviceVisible(profile, mesh, trimmed.split("\\s+"));
                    case "bluetooth" -> bluetooth(mesh);
                    case "send-file" -> sendFile(mesh, trimmed.split("\\s+", 3));
                    case "transfers" -> mesh.files().transfers().forEach(t -> System.out.printf(
                            "%-12s -> %-10s %-9s %3d%% %s%s%n", t.fileName(), t.to(), t.state(), t.percent(),
                            t.stalled() ? "(waiting for a route) " : "", t.error()));
                    case "routes" -> mesh.routes().forEach(r -> System.out.printf(
                            "%-12s via %-12s cost=%d hops=%d seq=%d %s%n", r.destination(), r.nextHop(),
                            r.cost(), r.hops(), r.sequence(), r.valid() ? "valid" : "INVALID"));
                    case "mesh-connect" -> meshConnect(mesh, trimmed.split("\\s+"));
                    case "chat" -> chat(mesh, trimmed.split("\\s+", 3));
                    case "inbox" -> mesh.chat().inbox().forEach(c ->
                            System.out.println(c.from() + " -> " + c.to() + ": " + c.text()));
                    case "chat-status" -> chatStatus(mesh, trimmed.split("\\s+"));
                    case "help" -> System.out.println(COMMANDS);
                    case "quit", "exit" -> { return; }
                    default -> System.out.println("Unknown command. Type help.");
                }
            }
        }
    }

    private static void meshConnect(MeshRuntime mesh, String[] parts) {
        if (parts.length != 2) {
            System.out.println("Usage: mesh-connect <host:meshPort>");
            return;
        }
        try {
            mesh.node().connectTo(parts[1]);
            System.out.println("Connecting to " + parts[1] + " ...");
        } catch (IOException e) {
            System.out.println("Could not connect to " + parts[1] + " (" + e.getMessage() + ")");
        }
    }

    private static void chat(MeshRuntime mesh, String[] parts) {
        if (parts.length != 3) {
            System.out.println("Usage: chat <device-id|*> <text>");
            return;
        }
        try {
            System.out.println("Sent, chat id " + mesh.chat().send(parts[1], parts[2])
                    + " (check with chat-status <id>)");
        } catch (IllegalArgumentException e) {
            System.out.println("Not sent: " + e.getMessage());
        }
    }

    private static void deviceName(DeviceProfileService profile, MeshRuntime mesh, String[] parts) {
        try {
            if (parts.length == 2) {
                profile.update(parts[1], null);
            }
            System.out.println("Device name: " + mesh.deviceStatus().get("displayName")
                    + " (id " + mesh.deviceStatus().get("deviceId") + "). Usage: name <new name>");
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Not changed: " + e.getMessage());
        }
    }

    private static void deviceVisible(DeviceProfileService profile, MeshRuntime mesh, String[] parts) {
        try {
            if (parts.length == 2 && (parts[1].equals("on") || parts[1].equals("off"))) {
                profile.update(null, parts[1].equals("on"));
            } else if (parts.length != 1) {
                System.out.println("Usage: visible on|off");
                return;
            }
            System.out.println("Visible to nearby devices: " + (profile.profile().visible() ? "yes" : "no")
                    + (mesh.bluetoothStatus() == null ? " (Bluetooth is not in use, so this has no effect)" : ""));
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Not changed: " + e.getMessage());
        }
    }

    private static void bluetooth(MeshRuntime mesh) {
        var status = mesh.bluetoothStatus();
        if (status == null) {
            System.out.println("Bluetooth is not in use. Start with -Dmesh.transport=bluetooth (or both).");
            return;
        }
        System.out.println("Adapter " + status.get("adapter") + ", advertised as " + status.get("advertisedAs")
                + (status.get("lastError").toString().isEmpty() ? "" : "  [last scan error: " + status.get("lastError") + "]"));
        for (Object item : (java.util.List<?>) status.get("nearby")) {
            var d = (java.util.Map<?, ?>) item;
            System.out.printf("  %-17s %-28s rssi=%-5s %s%n", d.get("address"), d.get("name"), d.get("rssi"),
                    d.get("relayDevice") != null ? "RELAY device " + d.get("relayDevice") : "");
        }
    }

    private static void sendFile(MeshRuntime mesh, String[] parts) {
        if (parts.length != 3) {
            System.out.println("Usage: send-file <device-id> <path>");
            return;
        }
        try {
            Path source = Path.of(parts[2]);
            storage.files.FileMetadata metadata = new storage.files.FileMetadata(UUID.randomUUID(),
                    source.getFileName().toString(), Files.size(source), storage.files.HashUtils.sha256(source),
                    java.time.Instant.now().toString(), List.of(parts[1]));
            var status = mesh.files().send(parts[1], metadata, source);
            System.out.println("Sending, transfer id " + status.transferId() + " (see: transfers)");
        } catch (IOException | RuntimeException e) {
            System.out.println("Not sent: " + e.getMessage());
        }
    }

    private static void chatStatus(MeshRuntime mesh, String[] parts) {
        try {
            System.out.println(mesh.chat().status(UUID.fromString(parts[1])));
        } catch (RuntimeException e) {
            System.out.println("Usage: chat-status <chat-id> (" + e.getMessage() + ")");
        }
    }

    private static void ping(String[] parts) {
        if (parts.length != 3) {
            System.out.println("Usage: ping <host> <port>");
            return;
        }
        try {
            String response = PeerClient.ping(parts[1], Integer.parseInt(parts[2]));
            System.out.printf("Response from %s:%s -> %s%n", parts[1], parts[2], response);
        } catch (IOException | NumberFormatException e) {
            System.out.printf("Ping failed for %s:%s (%s)%n", parts[1], parts[2], e.getMessage());
        }
    }

    private static void put(String[] parts) {
        if (parts.length != 4) {
            System.out.println("Usage: put <host> <port> <file-path>");
            return;
        }
        try {
            TransferResult result = PeerClient.upload(parts[1], Integer.parseInt(parts[2]), Path.of(parts[3]));
            printTransferResult("Upload", result);
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Upload failed: " + e.getMessage());
        }
    }

    private static void get(String[] parts) {
        if (parts.length != 5) {
            System.out.println("Usage: get <host> <port> <file-id> <destination>");
            return;
        }
        try {
            TransferResult result = PeerClient.download(parts[1], Integer.parseInt(parts[2]),
                    UUID.fromString(parts[3]), Path.of(parts[4]));
            printTransferResult("Download", result);
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("Download failed: " + e.getMessage());
        }
    }

    private static void printTransferResult(String operation, TransferResult result) {
        if (result.success()) {
            System.out.printf("%s successful. File ID: %s (%s)%n",
                    operation, result.fileId(), result.message());
        } else {
            System.out.printf("%s rejected [%s]: %s%n",
                    operation, result.code(), result.message());
        }
    }

    private static void status(HeartbeatManager heartbeatManager) {
        System.out.println("Peer  Address              State       Failures");
        for (HeartbeatManager.PeerStatus status : heartbeatManager.statuses()) {
            String address = status.peer().host() + ":" + status.peer().port();
            System.out.printf("%-5s %-20s %-11s %d%n",
                    status.peer().name(), address, status.state(), status.consecutiveFailures());
        }
    }

    private static void peers(PeerRegistry registry) {
        System.out.println("Peer  Address              State       Source");
        for (PeerRegistry.Member member : registry.members()) {
            ReplicationManager.PeerEndpoint peer = member.endpoint();
            System.out.printf("%-5s %-20s %-11s %s%n", peer.name(),
                    peer.host() + ":" + peer.port(), member.state(),
                    member.configured() ? "configured" : "discovered");
        }
    }

    private static void discoveryStatus(PeerConfiguration configuration, DiscoveryManager manager) {
        if (!configuration.discoveryEnabled() || manager == null) {
            System.out.println("Discovery: disabled");
            return;
        }
        DiscoveryManager.DiscoveryStatus status = manager.status();
        System.out.printf("Discovery: %s group=%s:%d received=%d accepted=%d rejected=%d last-rejection=%s%n",
                status.running() ? "running" : "stopped", status.group(), status.port(),
                status.received(), status.accepted(), status.rejected(), status.lastRejectReason());
    }

    private static void repairStatus(SelfHealingManager manager) {
        SelfHealingManager.RepairStatus status = manager.status();
        System.out.println("Under-replicated files:");
        if (status.underReplicated().isEmpty()) System.out.println("  (none)");
        for (SelfHealingManager.RepairView view : status.underReplicated()) {
            System.out.printf("  %s  state=%s  copies=%s%n", view.fileId(), view.state(), view.copies());
        }
        System.out.println("Active repairs: " + status.activeRepairs());
        System.out.println("Recent outcomes:");
        status.recentOutcomes().forEach(outcome ->
                System.out.printf("  %s %s %s%n", outcome.at(), outcome.fileId(), outcome.state()));
    }

    private static void reconcileStatus(ReconciliationManager manager) {
        ReconciliationManager.ReconciliationStatus status = manager.status();
        System.out.println("Reconciliation state:");
        if (status.files().isEmpty()) System.out.println("  (none)");
        status.files().forEach(view -> System.out.printf(
                "  %s  state=%s  preferred=%s  extra-candidates=%s  conflicts=%s  unavailable=%s%n",
                view.fileId(), view.state(), view.preferredReplicas(), view.extraCandidates(),
                view.metadataConflicts(), view.unavailablePeers()));
        System.out.println("Active reconciliations: " + status.activeReconciliations());
        System.out.println("Recent outcomes:");
        status.recentOutcomes().forEach(outcome ->
                System.out.printf("  %s %s %s %s%n", outcome.at(), outcome.fileId(),
                        outcome.state(), outcome.message()));
    }

    private static void inventory(String localName, PeerServer server,
                                  HeartbeatManager heartbeatManager) {
        List<InventoryComparator.SourceInventory> sources = new ArrayList<>();
        try {
            sources.add(InventoryComparator.SourceInventory.available(
                    localName, server.inventory()));
        } catch (IOException e) {
            sources.add(InventoryComparator.SourceInventory.unavailable(localName, e.getMessage()));
        }

        System.out.println("Inventory sources:");
        for (HeartbeatManager.PeerStatus status : heartbeatManager.statuses()) {
            String peerName = status.peer().name();
            try {
                List<FileMetadata> files = PeerClient.listFiles(status.peer().host(), status.peer().port());
                sources.add(InventoryComparator.SourceInventory.available(peerName, files));
                System.out.printf("  %-5s %-20s %-11s %d files%n", peerName,
                        status.peer().host() + ":" + status.peer().port(), status.state(), files.size());
            } catch (IOException e) {
                String reason = e.getMessage() == null ? "unavailable" : e.getMessage();
                sources.add(InventoryComparator.SourceInventory.unavailable(peerName, reason));
                System.out.printf("  %-5s %-20s %-11s unavailable (%s)%n", peerName,
                        status.peer().host() + ":" + status.peer().port(), status.state(), reason);
            }
        }

        System.out.println("Local and reachable peer files:");
        List<InventoryComparator.FileCopies> copies = InventoryComparator.compare(sources);
        if (copies.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        for (InventoryComparator.FileCopies copy : copies) {
            String locations = String.join(", ", copy.copies().keySet());
            FileMetadata metadata = copy.copies().values().iterator().next();
            String conflict = copy.metadataAgrees() ? "" : " [CONFLICTING METADATA]";
            System.out.printf("  %s  %s  %d bytes  sha256=%s  copies=%s%s%n",
                    copy.fileId(), metadata.fileName(), metadata.size(), metadata.sha256(), locations, conflict);
        }
    }
}
