package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Talks to the REAL adapter through BlueZ. Opt in with -Drelay.hardwareTests=true: these tests briefly rename
 * the adapter and make it discoverable (visible to nearby devices), and always put everything back.
 */
@Timeout(90)
class BlueZAdapterHardwareTest {
    @TempDir
    Path tmp;

    private static BlueZAdapter openOrSkip(Path restoreFile) {
        assumeTrue(Boolean.getBoolean("relay.hardwareTests"), "opt in with -Drelay.hardwareTests=true");
        try {
            return BlueZAdapter.open(restoreFile);
        } catch (Exception e) {
            assumeTrue(false, "no usable Bluetooth adapter: " + e.getMessage());
            return null;
        }
    }

    /** Reads the real alias, waiting for BlueZ (it applies changes a moment after they are requested). */
    private static void awaitName(BlueZAdapter adapter, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!expected.equals(adapter.name()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(expected, adapter.name());
    }

    @Test
    void advertisingRenamesTheAdapterAndCloseRestoresIt() throws Exception {
        Path restore = tmp.resolve("restore.properties");
        BlueZAdapter adapter = openOrSkip(restore);
        String originalName = adapter.name();
        try {
            assertTrue(BluetoothAddress.isValid(adapter.address()), adapter.address());
            adapter.advertise("hwtest");
            assertEquals("relay:hwtest", adapter.name());
            assertTrue(adapter.isAdvertising());
            assertTrue(Files.isRegularFile(restore), "restore file is written before changing anything");
        } finally {
            adapter.close();
        }
        BlueZAdapter check = BlueZAdapter.open(restore);
        try {
            awaitName(check, originalName); // the original adapter name is back
            assertFalse(Files.exists(restore), "restore file removed after a clean close");
        } finally {
            check.close();
        }
    }

    @Test
    void aCrashedRunIsRepairedByTheNextStart() throws Exception {
        Path restore = tmp.resolve("crash-restore.properties");
        BlueZAdapter first = openOrSkip(restore);
        String originalName = first.name();
        BlueZAdapter second = null;
        try {
            first.advertise("crashed");              // ... and the process "dies" here without close()
            assertEquals("relay:crashed", first.name());
            second = BlueZAdapter.open(restore);
            second.advertise("recovered");           // must restore the leftovers first
            assertEquals("relay:recovered", second.name());
        } finally {
            if (second != null) second.close();
            first.close();
        }
        BlueZAdapter check = BlueZAdapter.open(restore);
        try {
            awaitName(check, originalName); // never left renamed, even after a simulated crash
        } finally {
            check.close();
        }
    }

    @Test
    void realScanReturnsNearbyDevicesWithAddresses() throws Exception {
        BlueZAdapter adapter = openOrSkip(tmp.resolve("scan-restore.properties"));
        try {
            List<Nearby> found = adapter.scan(Duration.ofSeconds(4));
            System.out.println("real scan found " + found.size() + " device(s): " + found);
            for (Nearby device : found) {
                assertTrue(BluetoothAddress.isValid(device.address()), device.address());
            }
        } finally {
            adapter.close();
        }
    }

    @Test
    void visibilityWithAFriendlyNameCanBeSwitchedOnRenamedAndOffWithoutClosing() throws Exception {
        Path restore = tmp.resolve("visibility-restore.properties");
        BlueZAdapter adapter = openOrSkip(restore);
        String originalName = adapter.name();
        try {
            adapter.advertise("hwtest", "Test Laptop");
            assertEquals("Test Laptop [relay:hwtest]", adapter.name());
            assertTrue(adapter.isAdvertising());

            adapter.advertise("hwtest", "Renamed");            // rename while visible
            assertEquals("Renamed [relay:hwtest]", adapter.name());
            assertTrue(Files.isRegularFile(restore), "the original settings are still saved");

            adapter.stopAdvertising();                          // hidden again, adapter still open
            assertFalse(adapter.isAdvertising());
            awaitName(adapter, originalName); // original name is back once it is hidden
            assertFalse(Files.exists(restore), "nothing left to restore");
            assertDoesNotThrow(() -> adapter.scan(Duration.ofSeconds(1)), "scanning still works while hidden");

            adapter.advertise("hwtest", "");                    // visible again, without a name
            assertEquals("relay:hwtest", adapter.name());
        } finally {
            adapter.close();
        }
        BlueZAdapter check = BlueZAdapter.open(restore);
        try {
            awaitName(check, originalName);
        } finally {
            check.close();
        }
    }

    @Test
    void switchingVisibilityOffAndOnQuicklyNeverSavesOurOwnNameAsTheOriginal() throws Exception {
        Path restore = tmp.resolve("rapid-restore.properties");
        BlueZAdapter adapter = openOrSkip(restore);
        String originalName = adapter.name();
        try {
            for (int i = 0; i < 4; i++) {          // faster than BlueZ applies the changes
                adapter.advertise("hwtest", "Round " + i);
                adapter.stopAdvertising();
            }
            adapter.advertise("hwtest", "Final");
            assertEquals("Final [relay:hwtest]", adapter.name());
        } finally {
            adapter.close();
        }
        BlueZAdapter check = BlueZAdapter.open(restore);
        try {
            awaitName(check, originalName);        // restored to the true original, not one of the Round names
            assertFalse(Files.exists(restore));
        } finally {
            check.close();
        }
    }
}
