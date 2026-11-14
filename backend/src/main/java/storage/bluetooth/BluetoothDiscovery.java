package storage.bluetooth;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Finds nearby Bluetooth devices. {@link BlueZAdapter} does it with the real radio. */
public interface BluetoothDiscovery {
    /** Scans for the given time and returns every device seen. */
    List<Nearby> scan(Duration duration) throws IOException;
}
