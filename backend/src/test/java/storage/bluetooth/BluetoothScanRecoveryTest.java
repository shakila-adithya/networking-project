package storage.bluetooth;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BluetoothScanRecoveryTest {
    @Test void emptySuccessfulScanClearsAnEarlierFailureAndCanFindDevicesLater() throws Exception {
        StubRadio radio = new StubRadio();
        try (var directory = new BluetoothMeshDirectory(radio)) {
            radio.failure = new IOException("Radio temporarily unavailable");
            scan(directory);
            assertTrue(directory.reason().contains("Radio temporarily unavailable"));
            radio.failure = null;
            scan(directory);
            assertEquals("", directory.reason());
            assertTrue(directory.nearby().isEmpty());
            radio.devices = List.of(new Nearby("AA:BB:CC:DD:EE:FF", "relay:Other", null, false));
            scan(directory);
            assertEquals("Other", directory.nearby().getFirst().relayDeviceId().orElseThrow());
        }
    }
    @Test void anEmptyLookupStatusDoesNotIncludePermissionOrRadioFailures() {
        assertTrue(WindowsBluetoothAdapter.noScanResults(10108));
        assertTrue(WindowsBluetoothAdapter.noScanResults(10110));
        assertTrue(WindowsBluetoothAdapter.noScanResults(10102));
        for (int code : new int[]{10013, 10050, 10014, 10106})
            assertFalse(WindowsBluetoothAdapter.noScanResults(code), "A genuine lookup failure must remain an error: " + code);
    }
    private void scan(BluetoothMeshDirectory directory) throws Exception {
        directory.scan();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (directory.scanning() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(directory.scanning(), "Scan must leave its busy state even after a failure or no results");
    }
    private static final class StubRadio implements LocalBluetooth {
        volatile IOException failure;
        volatile List<Nearby> devices = List.of();
        public String address() { return "00:11:22:33:44:55"; }
        public String name() { return "Local"; }
        public boolean isAdvertising() { return false; }
        public List<Nearby> scan(Duration duration) throws IOException { if (failure != null) throw failure; return devices; }
        public void advertise(String id, String name, String mesh) { }
        public void stopAdvertising() { }
        public void close() { }
    }
}
