package storage.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class NetworkManagerServiceTest {
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
