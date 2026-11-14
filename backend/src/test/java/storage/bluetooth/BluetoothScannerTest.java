package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import storage.link.TopologyController;
import storage.link.TopologyPolicy;
import storage.messaging.FloodForwarder;
import storage.messaging.MeshNode;
import storage.transport.InMemoryTransport;

class BluetoothScannerTest {
    private final InMemoryTransport.Network net = new InMemoryTransport.Network();

    private TopologyController controller() {
        MeshNode node = new MeshNode("A", net.node("A"), new FloodForwarder());
        return new TopologyController(node, TopologyPolicy.defaults(), Set::of);
    }

    @Test
    void onlyRelayDevicesBecomeCandidatesWithAddressAndSignal() {
        TopologyController topology = controller();
        BluetoothDiscovery discovery = duration -> List.of(
                new Nearby("34:09:C9:B1:39:88", "soundcore R60i NC", -50, true),
                new Nearby("43:E1:F8:F9:4C:92", "43-E1-F8-F9-4C-92", null, false),
                new Nearby("AA:BB:CC:DD:EE:01", "relay:B", -55, false),
                new Nearby("AA:BB:CC:DD:EE:02", "relay:C", -90, false),
                new Nearby("AA:BB:CC:DD:EE:03", "relay:A", -40, false));            // this device itself
        BluetoothScanner scanner = new BluetoothScanner(discovery, topology, "A", Duration.ofSeconds(1), Duration.ofSeconds(5));
        scanner.scanOnce();

        var candidates = topology.status();
        assertEquals(2, candidates.size());
        assertEquals("B", candidates.get(0).candidate().deviceId());
        assertEquals("bt:AA:BB:CC:DD:EE:01", candidates.get(0).candidate().address());
        assertEquals(75, candidates.get(0).candidate().signal());      // -55 dBm
        assertEquals("C", candidates.get(1).candidate().deviceId());
        assertEquals(16, candidates.get(1).candidate().signal());      // -90 dBm
        assertEquals(5, scanner.lastScan().size(), "the last scan keeps every device for display");
        assertEquals("", scanner.lastError());
    }

    @Test
    void aFailingScanIsReportedAndDoesNotStopTheScanner() {
        TopologyController topology = controller();
        int[] calls = {0};
        BluetoothDiscovery flaky = duration -> {
            if (calls[0]++ == 0) throw new IOException("adapter busy");
            return List.of(new Nearby("AA:BB:CC:DD:EE:01", "relay:B", -60, false));
        };
        BluetoothScanner scanner = new BluetoothScanner(flaky, topology, "A", Duration.ofSeconds(1), Duration.ofSeconds(5));
        scanner.scanOnce();
        assertEquals("adapter busy", scanner.lastError());
        assertTrue(topology.status().isEmpty());
        scanner.scanOnce();
        assertEquals("", scanner.lastError());
        assertEquals(1, topology.status().size());
    }

    @Test
    void scanLoopRunsInItsOwnThreadAndStopsOnClose() throws Exception {
        TopologyController topology = controller();
        BluetoothDiscovery discovery = duration -> List.of(new Nearby("AA:BB:CC:DD:EE:01", "relay:B", -60, false));
        BluetoothScanner scanner = new BluetoothScanner(discovery, topology, "A", Duration.ofMillis(50), Duration.ofMillis(50));
        scanner.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (topology.status().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, topology.status().size());
        scanner.close();
        assertFalse(scanner.isRunning());
    }

    @Test
    void scanNowInterruptsTheWaitAndReportsProgress() throws Exception {
        TopologyController topology = controller();
        java.util.concurrent.atomic.AtomicInteger scans = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch inScan = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        BluetoothDiscovery slow = duration -> {
            if (scans.incrementAndGet() == 2) {            // the user-requested scan
                inScan.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.of(new Nearby("AA:BB:CC:DD:EE:01", "relay:B", -60, false));
        };
        // A one-hour interval: only scanNow() can trigger the second scan.
        BluetoothScanner scanner = new BluetoothScanner(slow, topology, "A", Duration.ofMillis(50), Duration.ofHours(1));
        assertFalse(scanner.isScanning());
        assertEquals(0, scanner.lastScanAt());
        scanner.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (scans.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        while (scanner.isScanning() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, scans.get(), "the first scan ran, then the scanner waits");
        assertTrue(scanner.lastScanAt() > 0);

        scanner.scanNow();
        assertTrue(inScan.await(5, java.util.concurrent.TimeUnit.SECONDS), "scanNow woke the waiting scanner");
        assertTrue(scanner.isScanning(), "reports that a scan is in progress");
        release.countDown();
        while (scanner.isScanning() && System.currentTimeMillis() < deadline + 5_000) Thread.sleep(10);
        assertFalse(scanner.isScanning());
        assertEquals(2, scans.get());
        scanner.close();
    }
    @Test void onlyOffersUnverifiedCandidatesAdvertisingTheSelectedMesh() {
        TopologyController topology = controller();
        BluetoothDiscovery discovery = duration -> List.of(
                new Nearby("AA:BB:CC:DD:EE:01", Nearby.advertisedMeshName("B", "B", "chosen"), -50, false),
                new Nearby("AA:BB:CC:DD:EE:02", Nearby.advertisedMeshName("C", "C", "other"), -40, false),
                new Nearby("AA:BB:CC:DD:EE:03", "relay:D", -40, false));
        var scanner = new BluetoothScanner(discovery, topology, "A", Duration.ofSeconds(1), Duration.ofSeconds(30), "chosen");
        scanner.scanOnce();
        assertEquals(1, topology.status().size());
        assertEquals("B", topology.status().getFirst().candidate().deviceId());
        assertEquals(3, scanner.lastScan().size(), "Other meshes/devices remain visible as unverified discovery hints");
    }
}
