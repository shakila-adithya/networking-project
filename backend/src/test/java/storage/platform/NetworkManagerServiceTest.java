package storage.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class NetworkManagerServiceTest {
    @Test void scanUsesTheNetworkManagerSignedInt32Contract() {
        var received = new java.util.ArrayList<java.util.Map<String, org.freedesktop.dbus.types.Variant<?>>>();
        var radio = new NetworkManagerService.WifiP2P() {
            public String getObjectPath() { return "/test/p2p"; }
            public void StartFind(java.util.Map<String, org.freedesktop.dbus.types.Variant<?>> options) { received.add(options); }
            public void StopFind() { fail("A scan must not stop an existing discovery session"); }
        };
        NetworkManagerService.startFind(radio);
        assertEquals(1, received.size());
        var timeout = received.getFirst().get("timeout");
        assertEquals("i", timeout.getSig(), "NetworkManager rejects an unsigned 'u' timeout");
        assertEquals(15, timeout.getValue());
    }
    @Test void scanErrorsPreserveTheOperatingSystemReason() {
        var error = new java.io.IOException("wrapper", new IllegalArgumentException("\"timeout\" must be an integer \"i\""));
        assertEquals("\"timeout\" must be an integer \"i\"", NetworkManagerService.scanFailureReason(error));
        assertEquals("Wi-Fi disabled by rfkill", NetworkManagerService.scanFailureReason(new IllegalStateException("Wi-Fi disabled by rfkill")));
        assertFalse(NetworkManagerService.scanFailureReason(new Exception("Denied\nnext line")).contains("\n"));
    }
    @Test @EnabledIfSystemProperty(named = "relay.test.nativeScan", matches = "true")
    void actualNetworkManagerAcceptsTheScanWithoutActivatingAConnection() throws Exception {
        try (var service = new NetworkManagerService()) {
            assertTrue(service.adapters().stream().anyMatch(adapter -> adapter.type().equals("wifi-direct")));
            service.scan();
            var status = service.status();
            assertEquals("", status.get("error"), status.toString());
            assertEquals("STOPPED", status.get("state"));
            assertEquals("", status.get("mode"));
            System.out.println("Actual NetworkManager accepted Wi-Fi Direct StartFind; no Relay connection was activated.");
        }
    }
    @Test void radioChangesRequireExplicitDisconnectionAcknowledgement() {
        assertThrows(IllegalArgumentException.class, () -> NetworkManagerService.requireConfirmed(false));
        assertDoesNotThrow(() -> NetworkManagerService.requireConfirmed(true));
    }
    @Test void cleanupPreservesAConnectionSelectedLaterInSystemSettings() {
        assertFalse(NetworkManagerService.mayRestorePrevious("/active/user-selected", "/active/relay"));
        assertTrue(NetworkManagerService.mayRestorePrevious("/active/relay", "/active/relay"));
        assertTrue(NetworkManagerService.mayRestorePrevious("/", "/active/relay"));
    }
    @Test @EnabledIfSystemProperty(named = "relay.test.nativeRead", matches = "true")
    void readsActualNetworkManagerCapabilitiesWithoutChangingConnections() {
        try (var service = new NetworkManagerService()) {
            var status = service.status();
            System.out.println("Native read-only status: " + status);
            assertTrue((Boolean)status.get("available"), status.toString());
            assertFalse(status.containsKey("password"));
            assertEquals("STOPPED", status.get("state"));
        }
    }
}
