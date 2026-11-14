package storage.bluetooth;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The local Bluetooth adapter through BlueZ (the Linux Bluetooth daemon) on the system D-Bus: adapter
 * information, device discovery with signal strength, and advertising this device to other Relay devices.
 *
 * <p>Advertising temporarily renames the adapter to {@code relay:<deviceId>} and makes it discoverable, which
 * is how other Relay devices recognise it among phones and headphones. The original name and discoverable
 * settings are written to a restore file first and put back by {@link #close()}; if the process dies without
 * closing, the next start restores them before doing anything else.
 */
public final class BlueZAdapter implements LocalBluetooth {
    private final DeviceManager manager;
    private final BluetoothAdapter adapter;
    private final Path restoreFile;
    private boolean advertising;
    /**
     * BlueZ applies changes asynchronously: a value read right after a change can still be the old one, and a
     * quickly following change replaces the earlier one. So the original settings are captured once, kept here
     * (and in the restore file), and never re-read from an adapter that may be in the middle of a change.
     */
    private Properties originals;
    private String advertisedName = "";

    private BlueZAdapter(DeviceManager manager, BluetoothAdapter adapter, Path restoreFile) {
        this.manager = manager;
        this.adapter = adapter;
        this.restoreFile = restoreFile;
    }

    /** Opens the first Bluetooth adapter. Fails with a readable message if BlueZ or the adapter is missing. */
    public static BlueZAdapter open(Path restoreFile) throws IOException {
        DeviceManager manager = null;
        try {
            manager = DeviceManager.createInstance(false);
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null) {
                manager.closeConnection();
                throw new IOException("no Bluetooth adapter found");
            }
            if (!Boolean.TRUE.equals(adapter.isPowered())) {
                manager.closeConnection();
                throw new IOException("Bluetooth is turned off (adapter " + adapter.getAddress() + ")");
            }
            return new BlueZAdapter(manager, adapter, restoreFile);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (manager != null) {
                manager.closeConnection();
            }
            throw new IOException("cannot reach BlueZ on the system D-Bus: " + e.getMessage(), e);
        }
    }

    @Override
    public String address() {
        return BluetoothAddress.normalize(adapter.getAddress());
    }

    @Override
    public synchronized String name() {
        return advertising && !advertisedName.isEmpty() ? advertisedName : adapter.getAlias();
    }

    @Override
    public boolean isAdvertising() {
        return advertising;
    }

    @Override
    public List<Nearby> scan(Duration duration) throws IOException {
        try {
            List<Nearby> found = new ArrayList<>();
            for (BluetoothDevice device : manager.scanForBluetoothDevices((int) Math.max(1_000, duration.toMillis()))) {
                String name = device.getName();
                if (name == null || name.isBlank()) {
                    name = device.getAlias();
                }
                Short rssi = device.getRssi();
                found.add(new Nearby(device.getAddress().toUpperCase(Locale.ROOT), name,
                        rssi == null ? null : rssi.intValue(), Boolean.TRUE.equals(device.isPaired())));
            }
            return found;
        } catch (RuntimeException e) {
            throw new IOException("Bluetooth scan failed: " + e.getMessage(), e);
        }
    }

    /** Makes this device visible to other Relay devices. Undone by {@link #stopAdvertising()} or {@link #close()}. */
    public synchronized void advertise(String deviceId) throws IOException {
        advertise(deviceId, "");
    }

    /**
     * Makes this device visible as {@code "<displayName> [relay:<id>]"} (or {@code relay:<id>} without a display name).
     * Calling it again while advertising only renames the adapter.
     */
    public synchronized void advertise(String deviceId, String displayName) throws IOException {
        advertise(deviceId, displayName, null);
    }
    @Override
    public synchronized void advertise(String deviceId, String displayName, String meshId) throws IOException {
        if (!deviceId.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("invalid device id");
        }
        String wanted = meshId == null ? Nearby.advertisedName(displayName, deviceId) : Nearby.advertisedMeshName(displayName, deviceId, meshId);
        try {
            if (advertising) {
                adapter.setAlias(wanted);
                advertisedName = wanted;
                return;
            }
            restoreLeftoversFromEarlierRun();
            if (originals == null) {
                originals = captureOriginals();
            }
            Files.createDirectories(restoreFile.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(restoreFile)) {
                originals.store(out, "Bluetooth adapter settings to restore when Relay stops");
            }
            advertising = true; // from here on close() restores, even if a call below fails
            adapter.setAlias(wanted);
            advertisedName = wanted;
            adapter.setDiscoverableTimeout(0); // stay discoverable while visible (default is 180 s)
            adapter.setDiscoverable(true);
        } catch (RuntimeException e) {
            throw new IOException("cannot advertise over Bluetooth: " + e.getMessage(), e);
        }
    }

    /** Stops being visible: puts the adapter's original name and discoverable settings back. Scanning still works. */
    @Override
    public synchronized void stopAdvertising() throws IOException {
        if (!advertising) {
            return;
        }
        try {
            restoreOriginals();
        } catch (RuntimeException e) {
            throw new IOException("cannot restore the Bluetooth adapter settings: " + e.getMessage(), e);
        }
    }

    /** Reads the adapter's current settings as the "original" ones. An alias that looks like ours is a leftover. */
    private Properties captureOriginals() {
        String alias = adapter.getAlias();
        String systemName = adapter.getName();
        boolean ours = alias != null && (alias.startsWith(Nearby.NAME_PREFIX) || new Nearby("", alias, null, false).relayDeviceId().isPresent());
        Properties captured = new Properties();
        // An empty alias means "reset to the system name", which is also what restores a default-named adapter.
        captured.setProperty("alias", alias == null || ours || alias.equals(systemName) ? "" : alias);
        Integer timeout = adapter.getDiscoverableTimeout();
        captured.setProperty("discoverable", String.valueOf(!ours && Boolean.TRUE.equals(adapter.isDiscoverable())));
        captured.setProperty("timeout", String.valueOf(ours || timeout == null || timeout == 0 ? 180 : timeout));
        return captured;
    }

    /** A restore file means an earlier run changed the adapter and did not put it back (it crashed). */
    private void restoreLeftoversFromEarlierRun() throws IOException {
        if (Files.isRegularFile(restoreFile)) {
            System.err.println("Restoring Bluetooth settings left by an earlier run that did not shut down cleanly.");
            Properties saved = new Properties();
            try (InputStream in = Files.newInputStream(restoreFile)) {
                saved.load(in);
            }
            originals = saved; // the file holds the true originals, unlike whatever the adapter shows now
            restoreOriginals();
        }
    }

    private void restoreOriginals() throws IOException {
        Properties saved = originals;
        if (saved == null && Files.isRegularFile(restoreFile)) {
            saved = new Properties();
            try (InputStream in = Files.newInputStream(restoreFile)) {
                saved.load(in);
            }
        }
        if (saved != null) {
            String alias = saved.getProperty("alias");
            if (alias != null && !"null".equals(alias)) {
                adapter.setAlias(alias); // "" resets to the system name
            }
            adapter.setDiscoverableTimeout(Integer.parseInt(saved.getProperty("timeout", "180")));
            adapter.setDiscoverable(Boolean.parseBoolean(saved.getProperty("discoverable", "false")));
        }
        Files.deleteIfExists(restoreFile);
        advertising = false;
        advertisedName = "";
    }

    @Override
    public synchronized void close() {
        try {
            if (advertising) {
                restoreOriginals();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not restore the Bluetooth adapter settings (see " + restoreFile + "): "
                    + e.getMessage());
        } finally {
            advertising = false;
            manager.closeConnection();
        }
    }
}
