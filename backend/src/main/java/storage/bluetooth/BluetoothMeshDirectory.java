package storage.bluetooth;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Read-only mesh discovery before membership exists. Shares the adapter with the running node. */
public final class BluetoothMeshDirectory implements AutoCloseable {
    private LocalBluetooth adapter;
    private volatile List<Nearby> nearby = List.of();
    private volatile String reason = "";
    private volatile long lastScan;
    private final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bluetooth-mesh-directory"); thread.setDaemon(true); return thread;
    });
    public BluetoothMeshDirectory() { this(Path.of("config", "bluetooth-restore.properties")); }
    public BluetoothMeshDirectory(Path restoreFile) {
        if (System.getProperty("mesh.transport", "both").equals("tcp")) { reason = "Bluetooth is disabled for this run."; return; }
        if (!BluetoothPlatform.isSupported()) { reason = BluetoothPlatform.unsupportedReason(); return; }
        try { adapter = BluetoothPlatform.open(restoreFile); }
        catch (Exception e) { reason = "Bluetooth unavailable: " + e.getMessage(); }
    }
    public LocalBluetooth adapter() { return adapter; }
    public String reason() { return reason; }
    public boolean scanning() { return scanning.get(); }
    public List<Nearby> nearby() { return System.currentTimeMillis() - lastScan > 60000 ? List.of() : nearby; }
    public void scan() {
        if (adapter == null || !scanning.compareAndSet(false, true)) return;
        try { worker.submit(new ScanTask()); }
        catch (RejectedExecutionException closed) { scanning.set(false); }
    }
    private final class ScanTask implements Runnable {
        public void run() {
            try { nearby = adapter.scan(java.time.Duration.ofSeconds(8)); lastScan = System.currentTimeMillis(); }
            catch (Exception e) { reason = "Bluetooth scan failed: " + e.getMessage(); }
            finally { scanning.set(false); }
        }
    }
    public void close() {
        worker.shutdownNow();
        if (adapter != null) adapter.close();
    }
}
