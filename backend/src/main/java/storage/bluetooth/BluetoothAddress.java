package storage.bluetooth;

import java.util.Locale;
import java.util.regex.Pattern;

/** Bluetooth device addresses ("AA:BB:CC:DD:EE:FF") and the "bt:" prefix that marks them as mesh addresses. */
public final class BluetoothAddress {
    public static final String PREFIX = "bt:";
    private static final Pattern FORMAT = Pattern.compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    private BluetoothAddress() {
    }

    public static boolean isValid(String address) {
        return address != null && FORMAT.matcher(address).matches();
    }

    /** Upper-case canonical form; throws IllegalArgumentException for anything that is not an address. */
    public static String normalize(String address) {
        if (!isValid(address)) {
            throw new IllegalArgumentException("not a Bluetooth address: " + address);
        }
        return address.toUpperCase(Locale.ROOT);
    }

    public static String withPrefix(String address) {
        return PREFIX + normalize(address);
    }

    /** Removes the "bt:" prefix and validates the rest. */
    public static String strip(String meshAddress) {
        if (meshAddress == null || !meshAddress.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a Bluetooth mesh address: " + meshAddress);
        }
        return normalize(meshAddress.substring(PREFIX.length()));
    }

    /** Linux stores bdaddr_t least-significant byte first, i.e. reversed compared with the text form. */
    public static byte[] toKernelBytes(String address) {
        String[] parts = normalize(address).split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[5 - i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    public static String fromKernelBytes(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (int i = 5; i >= 0; i--) {
            if (text.length() > 0) {
                text.append(':');
            }
            text.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return text.toString();
    }
}
