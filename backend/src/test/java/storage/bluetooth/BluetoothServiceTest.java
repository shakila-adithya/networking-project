package storage.bluetooth;

import java.lang.foreign.Arena;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BluetoothServiceTest {
    @Test void windowsRegistrationKeepsServiceIdentityAndChannel() {
        try (Arena arena = Arena.ofConfined()) {
            var query = WindowsBluetoothService.registration(arena, "AA:BB:CC:DD:EE:FF", 22,
                    "Laptop [relay:C] [mesh:demo]");
            assertEquals(120, query.get(JAVA_INT, 0));
            assertEquals(16, query.get(JAVA_INT, 40));
            assertEquals("Laptop [relay:C] [mesh:demo]",
                    WindowsBluetoothAdapter.readWide(query.get(ADDRESS, 8).reinterpret(512)));
            var csa = query.get(ADDRESS, 96).reinterpret(40);
            var local = csa.get(ADDRESS, 0).reinterpret(30);
            assertEquals(0xAABBCCDDEEFFL, local.get(JAVA_LONG.withByteAlignment(1), 2));
            assertEquals(22, local.get(JAVA_INT.withByteAlignment(1), 26));
            var guid = query.get(ADDRESS, 16).reinterpret(16);
            assertEquals(0x6c9e9f40, guid.get(JAVA_INT, 0));
            assertEquals((byte) 0xa4, guid.get(JAVA_BYTE, 8));
        }
    }

    @Test void linuxSdpIdentifiesOnlyOurServiceAndDecodesNames() throws Exception {
        String record = "<record><attribute id=\"0x0001\"><sequence><uuid value=\""
                + WindowsBluetoothService.UUID_TEXT + "\"/></sequence></attribute>"
                + "<attribute id=\"0x0100\"><text value=\"Maya &amp; Sam [relay:C] [mesh:demo]\"/></attribute></record>";
        assertEquals("Maya & Sam [relay:C] [mesh:demo]", LinuxBluetoothService.parseName(
                "Connecting...\n<?xml version=\"1.0\"?><record></record>\n" + record).orElseThrow());
        assertTrue(LinuxBluetoothService.parseName(record.replace(WindowsBluetoothService.UUID_TEXT,
                "00001101-0000-1000-8000-00805f9b34fb")).isEmpty());
        assertTrue(LinuxBluetoothService.parseName(record.replace("[relay:C]", "[relay:invalid id]")).isEmpty());
    }

    @Test void windowsSdpCanPublishQueryAndRemoveOnTheLocalRadio() throws Exception {
        assumeTrue(Boolean.getBoolean("relay.hardwareTests") && WindowsRfcomm.isSupported(),
                "Opt in to a real local Windows SDP check with -Drelay.hardwareTests=true");
        try (var adapter = WindowsBluetoothAdapter.open(null);
             var server = new WindowsRfcomm().listen(29)) {
            String name = "SDP check [relay:local_sdp_test]";
            try (var service = new WindowsBluetoothService(adapter.address(), 29, name)) {
                assertEquals(name, WindowsBluetoothService.findName(adapter.address()));
            }
            assertEquals("", WindowsBluetoothService.findName(adapter.address()));
            assertNull(server.accept(Duration.ofMillis(50)));
        }
    }
}
