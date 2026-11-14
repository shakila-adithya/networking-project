package storage.bluetooth;

import storage.transport.FramedStreamLink;
import storage.transport.Link;
import storage.transport.LinkListener;
import storage.transport.Transport;
import java.io.IOException;
import java.time.Duration;

/**
 * Mesh links over Bluetooth Classic RFCOMM. Mesh addresses look like {@code bt:AA:BB:CC:DD:EE:FF}.
 * The radio is reached only through {@link RfcommApi}; use {@link NativeRfcomm} for real hardware.
 */
public final class BluetoothTransport implements Transport {
    /** Every Relay device listens on this RFCOMM channel (1-30), so no service lookup is needed to connect. */
    public static final int DEFAULT_CHANNEL = 22;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final RfcommApi api;
    private final String localAddress;
    private final int channel;
    private volatile boolean running;
    private RfcommServer server;
    private Thread acceptThread;

    /**
     * @param localBluetoothAddress this device's adapter address, shown to the user
     */
    public BluetoothTransport(RfcommApi api, String localBluetoothAddress, int channel) {
        this.api = api;
        this.localAddress = BluetoothAddress.withPrefix(localBluetoothAddress);
        this.channel = channel;
    }

    @Override
    public String kind() {
        return "bluetooth";
    }

    @Override
    public String localAddress() {
        return localAddress;
    }

    @Override
    public boolean handles(String address) {
        return address.startsWith(BluetoothAddress.PREFIX);
    }

    @Override
    public synchronized void start(LinkListener incoming) throws IOException {
        if (running) {
            throw new IllegalStateException("transport already started");
        }
        server = api.listen(channel);
        running = true;
        acceptThread = new Thread(new BluetoothAcceptTask(this, server, incoming), "bluetooth-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    boolean isRunning() {
        return running;
    }

    static Link link(RfcommConnection connection) {
        return new FramedStreamLink(connection.input(), connection.output(), connection::close,
                BluetoothAddress.withPrefix(connection.remoteAddress()));
    }

    @Override
    public Link connect(String address) throws IOException {
        String bluetoothAddress;
        try {
            bluetoothAddress = BluetoothAddress.strip(address);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        return link(api.connect(bluetoothAddress, channel, CONNECT_TIMEOUT));
    }

    @Override
    public synchronized void close() {
        running = false;
        if (server != null) {
            server.close();
        }
        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try {
                acceptThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
