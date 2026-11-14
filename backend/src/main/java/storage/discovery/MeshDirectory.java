package storage.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import storage.config.PeerConfiguration;
import storage.cluster.PeerEndpoint;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Public, UNVERIFIED nearby mesh advertisements. Never carries a password or password-derived MAC.
 * Announcements are hints only: SecureTransport authenticates every connection before HELLO/routing.
 */
public final class MeshDirectory implements AutoCloseable {
    private static final String GROUP = "239.255.42.100";
    private static final int PORT = 45556;
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Advertisement(String protocol, String meshId, String peerId, int peerPort) { }
    public record NearbyMesh(String meshId, String peerId, String address, int peerPort,
                             long seenAt, boolean verified) { }
    private final Map<String, NearbyMesh> nearby = new ConcurrentHashMap<>();
    @FunctionalInterface interface NetworkInterfaces { List<NetworkInterface> get() throws SocketException; }
    private final NetworkInterfaces networkInterfaces;
    private final Supplier<PeerConfiguration> configuration;
    private final Consumer<PeerEndpoint> candidate;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r ->
            Thread.ofPlatform().daemon().name("mesh-advertisements").unstarted(r));
    private DatagramSocket socket;
    private final List<NetworkInterface> interfaces = new ArrayList<>();
    private volatile boolean closed;
    private volatile String error = "";
    private volatile String interfaceError = "";
    private volatile String announceError = "";
    private Thread reader;
    public MeshDirectory(Supplier<PeerConfiguration> configuration, Consumer<PeerEndpoint> candidate) {
        this(configuration, candidate, () -> Collections.list(NetworkInterface.getNetworkInterfaces()));
    }
    MeshDirectory(Supplier<PeerConfiguration> configuration, Consumer<PeerEndpoint> candidate, NetworkInterfaces networkInterfaces) {
        this.configuration = configuration; this.candidate = candidate; this.networkInterfaces = networkInterfaces;
    }
    public void start() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress(PORT)); socket.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 1);
            refreshInterfaces();
            reader = new Thread(new DirectoryReader(), "mesh-directory"); reader.setDaemon(true); reader.start();
            timer.scheduleWithFixedDelay(this::scan, 0, 4, TimeUnit.SECONDS);
        } catch (IOException e) { error = "Nearby mesh discovery unavailable: " + e.getMessage(); close(); }
    }
    private final class DirectoryReader implements Runnable {
        public void run() {
            while (!closed) {
                try {
                    byte[] bytes = new byte[2049]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                    socket.receive(packet);
                    if (error.startsWith("Mesh discovery failed:")) error = "";
                    accept(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()), packet.getAddress());
                } catch (IOException e) { if (!closed) error = "Mesh discovery failed: " + e.getMessage(); }
            }
        }
    }
    /** Structural validation only. The source address, mesh name and peer identity remain unverified. */
    public boolean accept(byte[] bytes, InetAddress source) {
        if (bytes.length > 2048 || source == null || source.isAnyLocalAddress() || source.isMulticastAddress()) return false;
        try {
            Advertisement a = JSON.readValue(bytes, Advertisement.class);
            if (!"Relay-v2".equals(a.protocol()) || a.meshId() == null || !a.meshId().matches("[A-Za-z0-9_.-]{1,80}")
                    || a.peerId() == null || !a.peerId().matches("[A-Za-z0-9_-]{1,40}") || a.peerPort() < 1 || a.peerPort() > 63535) return false;
            PeerConfiguration local = configuration.get();
            if (local != null && a.peerId().equals(local.localPeerId())) return false;
            String key = a.meshId() + "/" + a.peerId() + "/" + source.getHostAddress();
            if (nearby.size() >= 256 && !nearby.containsKey(key)) return false;
            nearby.put(key, new NearbyMesh(a.meshId(), a.peerId(), source.getHostAddress(), a.peerPort(), System.currentTimeMillis(), false));
            if (local != null && local.clusterId().equals(a.meshId()))
                candidate.accept(new PeerEndpoint(a.peerId(), source.getHostAddress(), a.peerPort()));
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }
    private synchronized void refreshInterfaces() throws IOException {
        // Hotspots/P2P create interfaces after the application starts; join those networks too.
        String issue = "";
        for (NetworkInterface nic : networkInterfaces.get()) {
            if (!nic.isUp() || !nic.supportsMulticast() || nic.isLoopback()
                    || nic.inetAddresses().noneMatch(a -> a instanceof Inet4Address)) continue;
            if (interfaces.stream().anyMatch(existing -> existing.getIndex() == nic.getIndex())) continue;
            try { socket.joinGroup(new InetSocketAddress(GROUP, PORT), nic); interfaces.add(nic); }
            catch (IOException e) { issue = "Cannot discover on " + nic.getName() + ": " + e.getMessage(); }
        }
        interfaces.removeIf(nic -> {
            try { return !nic.isUp(); } catch (SocketException gone) { return true; }
        });
        interfaceError = interfaces.isEmpty() ? "No multicast-capable local network. Connect a LAN or hotspot." : issue;
    }
    public List<NearbyMesh> nearby() {
        nearby.values().removeIf(item -> System.currentTimeMillis() - item.seenAt() > 20000);
        return nearby.values().stream().sorted(Comparator.comparing(NearbyMesh::meshId).thenComparing(NearbyMesh::peerId)).toList();
    }
    public String error() { return !error.isEmpty() ? error : !interfaceError.isEmpty() ? interfaceError : announceError; }
    public synchronized void scan() {
        nearby();
        if (closed || socket == null) return;
        try { refreshInterfaces(); } catch (IOException e) { interfaceError = "Cannot refresh network interfaces: " + e.getMessage(); }
        PeerConfiguration c = configuration.get();
        if (c == null) announceError = "";
        if (closed || c == null || socket == null) return;
        try {
            announceError = "";
            byte[] bytes = JSON.writeValueAsBytes(new Advertisement("Relay-v2", c.clusterId(), c.localPeerId(), c.port()));
            for (NetworkInterface nic : interfaces) {
                socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, nic);
                socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(GROUP), PORT));
            }
        } catch (IOException e) { announceError = "Cannot announce mesh: " + e.getMessage(); }
    }
    public void close() {
        closed = true; timer.shutdownNow();
        if (socket != null) socket.close();
        if (reader != null && reader != Thread.currentThread()) {
            try { reader.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
