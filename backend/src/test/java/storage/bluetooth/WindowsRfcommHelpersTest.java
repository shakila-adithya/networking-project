package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Checks the Winsock SOCKADDR_BTH packing without needing Windows. The socket calls themselves are untested here. */
class WindowsRfcommHelpersTest {
    @Test
    void bthAddressRoundTripsWithFirstOctetMostSignificant() {
        assertEquals(0xAABBCCDDEEFFL, WindowsRfcomm.toBthAddress("aa:bb:cc:dd:ee:ff"));
        assertEquals("01:23:45:67:89:AB", WindowsRfcomm.fromBthAddress(0x0123456789ABL));
    }

    @Test
    void sockaddrIsPackedTo30Bytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment address = WindowsRfcomm.socketAddress(arena, "AA:BB:CC:DD:EE:FF", 7);
            assertEquals(30, address.byteSize());
            assertEquals(32, address.get(JAVA_SHORT, 0));
            assertEquals(0xAABBCCDDEEFFL, address.get(JAVA_LONG.withByteAlignment(1), 2));
            assertEquals(7, address.get(JAVA_INT.withByteAlignment(1), 26));
        }
    }

    @Test
    void connectFailuresAreReadable() {
        assertEquals("cannot connect to AA:BB:CC:DD:EE:FF: the connection timed out (the device did not answer)",
                WindowsRfcomm.connectFailure("AA:BB:CC:DD:EE:FF", 5, 10060).getMessage());
    }
}
