package storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class PeerNode {
    private static final String COMMANDS = "ping <host> <port> | put <host> <port> <file-path> | "
            + "get <host> <port> <file-id> <destination> | inventory | status | peers | discovery-status | "
            + "repair-status | reconcile-status | help | quit";

    private PeerNode() {
    }

    public static void main(String[] args) throws IOException {
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
        try (PeerServer server = new PeerServer(configuration.bindAddress(), configuration.port(),
             configuration.storagePath(), replicationManager);
             HeartbeatManager heartbeatManager = new HeartbeatManager(name, registry.endpoints());
             SelfHealingManager selfHealingManager = new SelfHealingManager(
                     name, server.fileStore(), replicationManager, heartbeatManager);
             ReconciliationManager reconciliationManager = new ReconciliationManager(
                     name, server.fileStore(), replicationManager, heartbeatManager, selfHealingManager);
             DiscoveryManager discoveryManager = configuration.discoveryEnabled()
                     ? new DiscoveryManager(configuration, registry,
                     endpoint -> heartbeatManager.refreshPeers(registry.endpoints())) : null;
             BufferedReader console = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            registry.addListener(member -> heartbeatManager.refreshPeers(registry.endpoints()));
            heartbeatManager.addTransitionListener(transition ->
                    registry.updateState(transition.peer().name(),
                            PeerRegistry.PeerState.valueOf(transition.currentState().name())));
            server.start();
            heartbeatManager.start();
            selfHealingManager.start();
            reconciliationManager.start();
            if (discoveryManager != null) discoveryManager.start();
            System.out.printf("Peer %s listening on %s:%d (advertised as %s:%d)%n",
                    name, configuration.bindAddress(), configuration.port(),
                    configuration.advertisedAddress(), configuration.port());
            System.out.println("Storage: " + server.storageDirectory());
            System.out.println("Commands: " + COMMANDS);

            String line;
            while ((line = console.readLine()) != null) {
                String trimmed = line.trim();
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
                    case "help" -> System.out.println(COMMANDS);
                    case "quit", "exit" -> { return; }
                    default -> System.out.println("Unknown command. Type help.");
                }
            }
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
