package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NearbyTest {
    @Test
    void onlyRelayNamesAreRecognised() {
        assertEquals("B", new Nearby("AA:BB:CC:DD:EE:01", "relay:B", -60, false).relayDeviceId().orElseThrow());
        assertEquals("laptop-2", new Nearby("AA:BB:CC:DD:EE:02", "relay:laptop-2", null, true).relayDeviceId().orElseThrow());
        assertTrue(new Nearby("AA:BB:CC:DD:EE:03", "soundcore R60i NC", -50, true).relayDeviceId().isEmpty());
        assertTrue(new Nearby("AA:BB:CC:DD:EE:04", null, null, false).relayDeviceId().isEmpty());
        assertTrue(new Nearby("AA:BB:CC:DD:EE:05", "relay:", null, false).relayDeviceId().isEmpty());
        assertTrue(new Nearby("AA:BB:CC:DD:EE:06", "relay:bad id!", null, false).relayDeviceId().isEmpty());
        assertTrue(new Nearby("AA:BB:CC:DD:EE:07", "relay:" + "x".repeat(41), null, false).relayDeviceId().isEmpty());
    }

    @Test
    void rssiMapsToTheZeroToHundredSignalScale() {
        assertEquals(50, new Nearby("a", "n", null, false).signal(), "unknown RSSI is neutral");
        assertEquals(100, new Nearby("a", "n", -30, false).signal());
        assertEquals(0, new Nearby("a", "n", -120, false).signal());
        assertEquals(50, new Nearby("a", "n", -70, false).signal());
        assertTrue(new Nearby("a", "n", -55, false).signal() > new Nearby("a", "n", -85, false).signal());
    }

    @Test
    void friendlyNamesAreAdvertisedAndParsedWithTheIdInBrackets() {
        assertEquals("Maya's laptop [relay:C]", Nearby.advertisedName("Maya's laptop", "C"));
        assertEquals("relay:C", Nearby.advertisedName("", "C"), "no name: just the id");
        assertEquals("relay:C", Nearby.advertisedName(null, "C"));
        assertEquals("A x B [relay:C]", Nearby.advertisedName("A [x]\nB", "C"), "brackets and control characters removed");
        assertEquals(32, Nearby.advertisedName("x".repeat(60), "C").indexOf(" [relay:C]"), "name part limited to 32 characters");

        Nearby named = new Nearby("AA:BB:CC:DD:EE:01", "Maya's laptop [relay:C]", -60, false);
        assertEquals("C", named.relayDeviceId().orElseThrow());
        assertEquals("Maya's laptop", named.friendlyName().orElseThrow());
        Nearby legacy = new Nearby("AA:BB:CC:DD:EE:02", "relay:D", -60, false);
        assertEquals("D", legacy.relayDeviceId().orElseThrow());
        assertTrue(legacy.friendlyName().isEmpty());
        Nearby unnamed = new Nearby("AA:BB:CC:DD:EE:03", " [relay:E]", -60, false);
        assertEquals("E", unnamed.relayDeviceId().orElseThrow());
        assertTrue(unnamed.friendlyName().isEmpty());
        assertTrue(new Nearby("a", "Phone [relay:bad id]", null, false).relayDeviceId().isEmpty());
        assertTrue(new Nearby("a", "Phone [relay:C] extra", null, false).relayDeviceId().isEmpty(), "the id must end the name");
        assertTrue(new Nearby("a", "soundcore [R60i]", null, false).relayDeviceId().isEmpty());
        // whatever we advertise, we can read back
        for (String name : new String[] {"", "Maya", "මායා 📱", "A [b] c"}) {
            Nearby back = new Nearby("a", Nearby.advertisedName(name, "id_1"), null, false);
            assertEquals("id_1", back.relayDeviceId().orElseThrow(), name);
        }
    }
}
