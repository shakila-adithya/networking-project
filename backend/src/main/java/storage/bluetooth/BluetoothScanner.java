package storage.bluetooth;

import storage.discovery.Candidate;
import storage.link.TopologyController;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Periodically scans for Bluetooth devices and reports every Relay device (recognised by its
 * {@code relay:<id>} name) to the topology controller, which decides whether to connect.
 */
public final class BluetoothScanner implements AutoCloseable {
    private final BluetoothDiscovery discovery;
    private final TopologyController topology;
    private final String localDeviceId;
    private final String meshId;
    private final Duration scanDuration;
    private final Duration interval;
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile List<Nearby> lastScan = List.of();
    private volatile String lastError = "";
    private volatile boolean scanning;
    private volatile long lastScanAt;
    private boolean scanRequested;
    private Thread worker;

    public BluetoothScanner(BluetoothDiscovery discovery, TopologyController topology, String localDeviceId,
                            Duration scanDuration, Duration interval) {
        this(discovery, topology, localDeviceId, scanDuration, interval, null);
    }
    public BluetoothScanner(BluetoothDiscovery discovery, TopologyController topology, String localDeviceId,
                            Duration scanDuration, Duration interval, String meshId) {
        this.meshId = meshId;
        this.discovery = discovery;
        this.topology = topology;
        this.localDeviceId = localDeviceId;
        this.scanDuration = scanDuration;
        this.interval = interval;
    }

    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("already started");
        }
        running = true;
        worker = new Thread(new BluetoothScanTask(this), "bluetooth-scan");
        worker.setDaemon(true);
        worker.start();
    }

    boolean isRunning() {
        return running;
    }

    /** Devices seen in the most recent scan (Relay devices and others). */
    public List<Nearby> lastScan() {
        return lastScan;
    }

    public String lastError() {
        return lastError;
    }

    /** True while a scan is running (it takes several seconds). */
    public boolean isScanning() {
        return scanning;
    }

    /** When the last scan finished (epoch milliseconds), or 0 if none has finished yet. */
    public long lastScanAt() {
        return lastScanAt;
    }

    /** Asks for a scan right now instead of waiting for the next interval. */
    public void scanNow() {
        synchronized (lock) {
            scanRequested = true;
            lock.notifyAll();
        }
    }

    /** One scan now: updates {@link #lastScan()} and reports Relay devices. Used by the scan task. */
    void scanOnce() {
        scanning = true;
        try {
            List<Nearby> found = discovery.scan(scanDuration);
            lastScan = List.copyOf(found);
            lastError = "";
            for (Nearby device : found) {
                if (meshId != null && !device.meshId().filter(meshId::equals).isPresent()) continue;
                device.relayDeviceId()
                        .filter(id -> !id.equals(localDeviceId))
                        .ifPresent(id -> topology.offer(new Candidate(id,
                                BluetoothAddress.withPrefix(device.address()), device.signal())));
            }
        } catch (IOException | RuntimeException e) {
            lastError = String.valueOf(e.getMessage());
            if (running) { // a scan cut short because the app is closing is not worth reporting
                System.err.println("Bluetooth scan failed: " + e.getMessage());
            }
        } finally {
            lastScanAt = System.currentTimeMillis();
            scanning = false;
        }
    }

    /** Waits between scans; returns false if the scanner was closed meanwhile. */
    boolean pause() {
        synchronized (lock) {
            if (!running) {
                return false;
            }
            try {
                if (!scanRequested) {
                    lock.wait(interval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            scanRequested = false;
            return running;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(2_000); // the scan thread is a daemon: do not wait out a scan that is still running
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
