package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BluetoothAddressTest {
    @Test
    void kernelByteOrderIsReversedAndRoundTrips() {
        byte[] bytes = BluetoothAddress.toKernelBytes("28:11:a8:30:ea:39");
        assertArrayEquals(new byte[] {0x39, (byte) 0xEA, 0x30, (byte) 0xA8, 0x11, 0x28}, bytes);
        assertEquals("28:11:A8:30:EA:39", BluetoothAddress.fromKernelBytes(bytes));
    }

    @Test
    void prefixHandling() {
        assertEquals("bt:AA:BB:CC:DD:EE:FF", BluetoothAddress.withPrefix("aa:bb:cc:dd:ee:ff"));
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.strip("bt:aa:bb:cc:dd:ee:ff"));
        assertThrows(IllegalArgumentException.class, () -> BluetoothAddress.strip("127.0.0.1:5000"));
        assertThrows(IllegalArgumentException.class, () -> BluetoothAddress.normalize("AA:BB:CC"));
        assertThrows(IllegalArgumentException.class, () -> BluetoothAddress.normalize("GG:BB:CC:DD:EE:FF"));
        assertFalse(BluetoothAddress.isValid(null));
    }
}
