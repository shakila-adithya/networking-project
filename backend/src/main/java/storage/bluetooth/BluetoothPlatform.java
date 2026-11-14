package storage.bluetooth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Chooses the Bluetooth implementation for the operating system the agent runs on. */
public final class BluetoothPlatform {
    private BluetoothPlatform() { }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    public static boolean isSupported() {
        return isWindows() ? WindowsRfcomm.isSupported() : NativeRfcomm.isSupported();
    }

    public static RfcommApi rfcomm() {
        return isWindows() ? new WindowsRfcomm() : new NativeRfcomm();
    }

    public static LocalBluetooth open(Path restoreFile) throws IOException {
        return isWindows() ? WindowsBluetoothAdapter.open(restoreFile) : BlueZAdapter.open(restoreFile);
    }

    public static String unsupportedReason() {
        return isWindows()
                ? "No Windows Bluetooth radio with Winsock RFCOMM support was found."
                : "RFCOMM Bluetooth sockets are not available on this machine (Linux with the 'rfcomm' kernel module is required).";
    }
}
