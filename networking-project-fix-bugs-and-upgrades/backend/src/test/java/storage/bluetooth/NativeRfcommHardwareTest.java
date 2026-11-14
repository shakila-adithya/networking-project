package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs against the REAL Linux Bluetooth kernel stack. Skipped on machines without Bluetooth RFCOMM support.
 * The socket tests are harmless and always run when supported; tests that make the radio do something visible
 * to other devices are opt-in with -Drelay.hardwareTests=true.
 */
@Timeout(60)
class NativeRfcommHardwareTest {
    private static boolean hardwareTestsEnabled() {
        return Boolean.getBoolean("relay.hardwareTests");
    }

    private static boolean adapterPresent() {
        return Files.isDirectory(Path.of("/sys/class/bluetooth/hci0"));
    }

    @Test
    void realKernelSocketCanListenAcceptTimeoutAndReleaseTheChannel() throws Exception {
        assumeTrue(NativeRfcomm.isSupported(), "RFCOMM sockets are not available on this machine");
        NativeRfcomm rfcomm = new NativeRfcomm();
        RfcommServer server = rfcomm.listen(29);
        try {
            long started = System.nanoTime();
            assertNull(server.accept(Duration.ofMillis(300)), "nobody connected, so accept times out");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 250, "it really waited");
            IOException inUse = assertThrows(IOException.class, () -> rfcomm.listen(29));
            assertTrue(inUse.getMessage().contains("already in use"), inUse.getMessage());
        } finally {
            server.close();
        }
        RfcommServer again = rfcomm.listen(29);     // the channel is free again after close
        again.close();
        assertThrows(IOException.class, () -> again.accept(Duration.ofMillis(50)), "a closed server cannot accept");
    }

    @Test
    void closingTheServerWakesABlockedAcceptingThread() throws Exception {
        assumeTrue(NativeRfcomm.isSupported(), "RFCOMM sockets are not available on this machine");
        RfcommServer server = new NativeRfcomm().listen(28);
        Throwable[] outcome = new Throwable[1];
        Thread waiting = new Thread(() -> {
            try {
                server.accept(Duration.ofSeconds(30));
            } catch (Throwable t) {
                outcome[0] = t;
            }
        }, "accept-waiter");
        waiting.start();
        Thread.sleep(300);
        long started = System.nanoTime();
        server.close();
        waiting.join(5_000);
        assertFalse(waiting.isAlive(), "close must wake the waiting thread");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
        assertInstanceOf(IOException.class, outcome[0]);
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTouchingTheRadio() {
        NativeRfcomm rfcomm = new NativeRfcomm();
        assertThrows(IllegalArgumentException.class, () -> rfcomm.listen(0));
        assertThrows(IllegalArgumentException.class, () -> rfcomm.listen(31));
        assertThrows(IllegalArgumentException.class,
                () -> rfcomm.connect("not-an-address", 22, Duration.ofSeconds(1)));
    }

    @Test
    void connectingToADeviceThatIsNotThereFailsWithAReadableError() throws Exception {
        assumeTrue(hardwareTestsEnabled() && NativeRfcomm.isSupported() && adapterPresent(),
                "opt in with -Drelay.hardwareTests=true on a machine with a Bluetooth adapter");
        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class,
                () -> new NativeRfcomm().connect("00:11:22:33:44:55", 22, Duration.ofSeconds(4)));
        assertTrue(failure.getMessage().startsWith("cannot connect to 00:11:22:33:44:55"), failure.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20, "bounded by the timeout");
        System.out.println("real connect failure message: " + failure.getMessage());
    }
}
