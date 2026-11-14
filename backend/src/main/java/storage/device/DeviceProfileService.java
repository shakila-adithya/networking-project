package storage.device;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Holds the current {@link DeviceProfile}, saves changes and tells listeners (the mesh runtime) about them. */
public final class DeviceProfileService {
    private final DeviceProfileStore store;
    private final List<Consumer<DeviceProfile>> listeners = new CopyOnWriteArrayList<>();
    private DeviceProfile current;

    public DeviceProfileService(DeviceProfileStore store) {
        this.store = store;
        this.current = store.load();
    }

    public java.nio.file.Path directory() { return store.file().toAbsolutePath().getParent(); }

    public synchronized DeviceProfile profile() {
        return current;
    }

    public void onChange(Consumer<DeviceProfile> listener) {
        listeners.add(listener);
    }

    /**
     * Changes the name and/or visibility ({@code null} keeps the current value), saves the result and notifies
     * listeners. Throws IllegalArgumentException for an invalid name; nothing is changed in that case.
     */
    public DeviceProfile update(String displayName, Boolean visible) throws IOException {
        DeviceProfile updated;
        synchronized (this) {
            updated = current;
            if (displayName != null) {
                updated = updated.withDisplayName(displayName);
            }
            if (visible != null) {
                updated = updated.withVisible(visible);
            }
            if (updated.equals(current)) {
                return current;
            }
            store.save(updated);
            current = updated;
        }
        for (Consumer<DeviceProfile> listener : listeners) {
            try {
                listener.accept(updated);
            } catch (RuntimeException e) {
                System.err.println("Device settings listener failed: " + e);
            }
        }
        return updated;
    }
}
