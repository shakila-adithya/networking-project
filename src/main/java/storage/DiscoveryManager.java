package storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated, bounded multicast discovery. It changes membership only; it never transfers files. */
public final class DiscoveryManager implements AutoCloseable {
    public static final int MAX_MESSAGE_SIZE = 4096;
    public static final long MAX_CLOCK_SKEW_MILLIS = 30_000;
    public static final int MAX_MESSAGES_PER_MINUTE = 30;
    public static final long ANNOUNCE_INTERVAL_MILLIS = 5_000;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final PeerConfiguration configuration;
    private final PeerRegistry registry;
    private final Consumer<ReplicationManager.PeerEndpoint> peerListener;
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService announcer;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile String lastRejectReason = "none";
    private volatile MulticastSocket socket;
    private volatile InetAddress multicastAddress;
    private volatile NetworkInterface multicastInterface;
    private volatile Thread receiver;

    public DiscoveryManager(PeerConfiguration configuration, PeerRegistry registry,
                             Consumer<ReplicationManager.PeerEndpoint> peerListener) {
        this.configuration = Objects.requireNonNull(configuration);
        this.registry = Objects.requireNonNull(registry);
        this.peerListener = Objects.requireNonNull(peerListener);
        if (!configuration.discoveryEnabled()) throw new IllegalArgumentException("discovery is disabled");
        this.announcer = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("discovery-announcer-" + configuration.localPeerId()).unstarted(runnable));
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        try {
            MulticastSocket created = new MulticastSocket(configuration.discoveryPort());
            created.setReuseAddress(true);
            created.setSoTimeout(1_000);
            InetAddress group = InetAddress.getByName(configuration.discoveryGroup());
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(
                    InetAddress.getByName(configuration.advertisedAddress()));
            if (networkInterface == null || !networkInterface.isUp() || !networkInterface.supportsMulticast()) {
                throw new IOException("no usable multicast interface for advertised address "
                        + configuration.advertisedAddress());
            }
            created.setNetworkInterface(networkInterface);
            created.joinGroup(new InetSocketAddress(group, configuration.discoveryPort()), networkInterface);
            multicastAddress = group;
            multicastInterface = networkInterface;
            socket = created;
            receiver = Thread.ofPlatform().daemon().name("discovery-receiver-" + configuration.localPeerId())
                    .start(this::receiveLoop);
            announcer.scheduleAtFixedRate(this::announceSafely, 0, ANNOUNCE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (IOException | RuntimeException e) {
            running.set(false);
            closeSocket();
            throw e;
        }
    }

    private void receiveLoop() {
        while (running.get()) {
            try {
                byte[] buffer = new byte[MAX_MESSAGE_SIZE + 1];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                received.incrementAndGet();
                if (packet.getLength() > MAX_MESSAGE_SIZE) {
                    reject("message too large");
                    continue;
                }
                byte[] payload = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                acceptDatagram(payload, packet.getAddress());
            } catch (SocketTimeoutException e) {
                // Recheck running so shutdown is prompt even when no multicast traffic arrives.
            } catch (IOException e) {
                if (running.get()) reject("receive failure");
            }
        }
    }

    private void announceSafely() {
        try { announce(); }
        catch (Exception e) { if (running.get()) lastRejectReason = "announce failure"; }
    }

    private void announce() throws IOException {
        byte[] bytes = encode(createJoin(configuration, System.currentTimeMillis(), UUID.randomUUID().toString()));
        if (bytes.length > MAX_MESSAGE_SIZE) throw new IOException("discovery message exceeds size limit");
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length,
                InetAddress.getByName(configuration.discoveryGroup()), configuration.discoveryPort());
        socket.send(packet);
    }

    /** Package-visible deterministic validation entry point used by tests. */
    boolean acceptDatagram(byte[] payload, InetAddress sourceAddress) {
        if (payload == null || sourceAddress == null || payload.length == 0 || payload.length > MAX_MESSAGE_SIZE) {
            reject("invalid message size");
            return false;
        }
        DiscoveryMessage message;
        try {
            message = MAPPER.readValue(payload, DiscoveryMessage.class);
        } catch (IOException | IllegalArgumentException e) {
            reject("invalid message");
            return false;
        }
        String rateKey = sourceAddress.getHostAddress() + "/" + (message.peerId() == null ? "?" : message.peerId());
        if (!allow(rateKey)) {
            reject("rate limit");
            return false;
        }
        if (!"JOIN".equals(message.type()) || !configuration.clusterId().equals(message.clusterId())
                || message.peerId() == null || message.peerId().equals(configuration.localPeerId())
                || message.address() == null || PeerConfiguration.isWildcardForDiscovery(message.address())
                || message.port() < 1 || message.port() > 65_535
                || Math.abs(System.currentTimeMillis() - message.timestamp()) > MAX_CLOCK_SKEW_MILLIS
                || !validSignature(message)) {
            reject("unauthorized or invalid join");
            return false;
        }
        ReplicationManager.PeerEndpoint endpoint;
        try { endpoint = new ReplicationManager.PeerEndpoint(message.peerId(), message.address(), message.port()); }
        catch (IllegalArgumentException e) { reject("invalid endpoint"); return false; }
        if (!registry.join(endpoint)) {
            reject("duplicate identity or endpoint");
            return false;
        }
        accepted.incrementAndGet();
        peerListener.accept(endpoint);
        return true;
    }

    private boolean validSignature(DiscoveryMessage message) {
        try {
            byte[] expected = hmac(configuration.discoverySecret(), canonical(message));
            byte[] actual = java.util.HexFormat.of().parseHex(message.signature());
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) { return false; }
    }

    static DiscoveryMessage createJoin(PeerConfiguration configuration, long timestamp, String nonce) {
        DiscoveryMessage unsigned = new DiscoveryMessage("JOIN", configuration.clusterId(),
                configuration.localPeerId(), configuration.advertisedAddress(), configuration.port(),
                nonce, timestamp, "");
        return new DiscoveryMessage(unsigned.type(), unsigned.clusterId(), unsigned.peerId(), unsigned.address(),
                unsigned.port(), unsigned.nonce(), unsigned.timestamp(),
                java.util.HexFormat.of().formatHex(hmac(configuration.discoverySecret(), canonical(unsigned))));
    }

    static byte[] encode(DiscoveryMessage message) throws IOException {
        return MAPPER.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
    }

    private static String canonical(DiscoveryMessage message) {
        return String.join("\n", message.type(), message.clusterId(), message.peerId(), message.address(),
                Integer.toString(message.port()), message.nonce(), Long.toString(message.timestamp()));
    }

    private static byte[] hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("could not initialize discovery authentication", e); }
    }

    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        AtomicLong count = new AtomicLong();
        rateWindows.compute(key, (ignored, previous) -> {
            RateWindow window = previous;
            if (window == null || now - window.startedAt() >= 60_000) window = new RateWindow(now, 0);
            count.set(window.count() + 1);
            return new RateWindow(window.startedAt(), count.get());
        });
        return count.get() <= MAX_MESSAGES_PER_MINUTE;
    }

    private void reject(String reason) {
        rejected.incrementAndGet();
        lastRejectReason = reason;
    }

    public DiscoveryStatus status() {
        return new DiscoveryStatus(running.get(), received.get(), accepted.get(), rejected.get(), lastRejectReason,
                configuration.discoveryGroup(), configuration.discoveryPort());
    }

    @Override public synchronized void close() {
        if (running.compareAndSet(true, false)) {
            announcer.shutdownNow();
            closeSocket();
            if (receiver != null && receiver != Thread.currentThread()) {
                try { receiver.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void closeSocket() {
        MulticastSocket current = socket;
        if (current != null) {
            if (multicastAddress != null && multicastInterface != null) {
                try { current.leaveGroup(new InetSocketAddress(multicastAddress, configuration.discoveryPort()), multicastInterface); }
                catch (Exception ignored) { }
            }
            current.close();
        }
    }

    public record DiscoveryMessage(String type, String clusterId, String peerId, String address,
                                   int port, String nonce, long timestamp, String signature) { }
    public record DiscoveryStatus(boolean running, long received, long accepted, long rejected,
                                  String lastRejectReason, String group, int port) { }
    private record RateWindow(long startedAt, long count) { }
}
