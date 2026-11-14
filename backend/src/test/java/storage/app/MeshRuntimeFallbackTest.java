package storage.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import storage.bluetooth.NativeRfcomm;
import storage.bluetooth.RfcommServer;
import storage.config.PeerConfiguration;
import storage.device.DeviceProfileService;
import storage.device.DeviceProfileStore;
import storage.files.FileStore;

/**
 * Bluetooth is a bonus on top of TCP: if its RFCOMM channel is taken (for example a second Relay on the same
 * computer) the node must carry on over TCP, and "bluetooth only" must fail with a readable reason.
 * Uses the real kernel RFCOMM sockets; skipped where they are not available.
 */
@Timeout(60)
class MeshRuntimeFallbackTest {
    @TempDir
    Path tmp;

    private static int freePort() throws IOException {
        for (int i = 0; i < 100; i++) {
            int port = 20_000 + (int) (Math.random() * 20_000);
            try (ServerSocket a = new ServerSocket(port); ServerSocket b = new ServerSocket(port + 2_000)) {
                return port;
            } catch (IOException busy) {
                // try another range
            }
        }
        throw new IOException("no free port");
    }

    private MeshRuntime start(String mode, int channel, int port) throws IOException {
        System.setProperty("mesh.transport", mode);
        System.setProperty("mesh.bluetooth.channel", String.valueOf(channel));
        PeerConfiguration configuration = new PeerConfiguration("F1", "127.0.0.1", "127.0.0.1", port, tmp.resolve("data").toString(),
                List.of(new PeerConfiguration.PeerDefinition("F1", "127.0.0.1", port)), "fallback-test", true,
                "239.255.42.99", 45555, "fallback-test-secret-0123456789");
        return new MeshRuntime(configuration, new FileStore(tmp.resolve("store")),
                new DeviceProfileService(new DeviceProfileStore(tmp.resolve("profile.json"))));
    }

    @Test
    void aTakenBluetoothChannelMakesTheNodeCarryOnOverTcp() throws Exception {
        assumeTrue(NativeRfcomm.isSupported(), "RFCOMM sockets are not available on this machine");
        int channel = 26;
        RfcommServer holder = new NativeRfcomm().listen(channel);
        try (MeshRuntime runtime = start("both", channel, freePort())) {
            assertEquals("tcp", runtime.kind(), "only TCP is in use");
            @SuppressWarnings("unchecked")
            Map<String, Object> bluetooth = (Map<String, Object>) runtime.deviceStatus().get("bluetooth");
            assertEquals(false, bluetooth.get("available"));
            assertFalse(bluetooth.get("reason").toString().isBlank(), "the UI can say why Bluetooth is off");
            assertNull(runtime.bluetoothStatus());
            assertFalse(runtime.scanNow(), "scanning is not possible without Bluetooth");
        } finally {
            holder.close();
            System.clearProperty("mesh.transport");
            System.clearProperty("mesh.bluetooth.channel");
        }
    }

    @Test
    void bluetoothOnlyModeReportsWhyItCannotStart() throws Exception {
        assumeTrue(NativeRfcomm.isSupported(), "RFCOMM sockets are not available on this machine");
        int channel = 25;
        RfcommServer holder = new NativeRfcomm().listen(channel);
        try {
            IOException failure = assertThrows(IOException.class, () -> start("bluetooth", channel, freePort()));
            assertTrue(failure.getMessage().startsWith("Bluetooth is not available"), failure.getMessage());
        } finally {
            holder.close();
            System.clearProperty("mesh.transport");
            System.clearProperty("mesh.bluetooth.channel");
        }
    }

    @Test
    void tcpOnlyModeNeverTouchesBluetooth() throws Exception {
        try (MeshRuntime runtime = start("tcp", 24, freePort())) {
            assertEquals("tcp", runtime.kind());
            assertNull(runtime.bluetoothStatus());
            assertTrue(runtime.deviceStatus().get("bluetooth").toString().contains("mesh.transport=tcp"));
        } finally {
            System.clearProperty("mesh.transport");
            System.clearProperty("mesh.bluetooth.channel");
        }
    }
}
