package storage.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MeshDirectoryTest {
    @Test void spoofedAnnouncementsRemainUnverifiedAndCannotAuthorizeMembership() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        try (var directory = new MeshDirectory(() -> null, peer -> offers.incrementAndGet())) {
            var announcement = new MeshDirectory.Advertisement("Relay-v2", "spoofed-mesh", "B", 5000);
            byte[] json = new ObjectMapper().writeValueAsBytes(announcement);
            assertTrue(directory.accept(json, InetAddress.getLoopbackAddress()));
            assertEquals(1, directory.nearby().size());
            assertFalse(directory.nearby().getFirst().verified());
            assertEquals(0, offers.get(), "Discovery alone never joins or authenticates a peer");
            assertFalse(new String(json).contains("password"));
            assertFalse(directory.accept(new byte[2049], InetAddress.getLoopbackAddress()));
            assertFalse(directory.accept("{\"protocol\":\"Relay-v1\"}".getBytes(), InetAddress.getLoopbackAddress()));
        }
    }
    @Test void boundsUntrustedDirectoryStorageAndUsesPacketSourceAddress() throws Exception {
        try (var directory = new MeshDirectory(() -> null, peer -> {})) {
            var json = new ObjectMapper();
            for (int i = 0; i < 256; i++) assertTrue(directory.accept(json.writeValueAsBytes(
                    new MeshDirectory.Advertisement("Relay-v2", "mesh" + i, "B", 5000)), InetAddress.getByName("127.0.0.2")));
            assertFalse(directory.accept(json.writeValueAsBytes(new MeshDirectory.Advertisement("Relay-v2", "overflow", "B", 5000)), InetAddress.getLoopbackAddress()));
            assertTrue(directory.nearby().stream().allMatch(item -> item.address().equals("127.0.0.2")));
        }
    }
    @Test void clearsTheNoLanWarningWhenALocalMulticastInterfaceAppears() throws Exception {
        var available = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
                .filter(nic -> {
                    try { return nic.isUp() && !nic.isLoopback() && nic.supportsMulticast()
                            && nic.inetAddresses().anyMatch(address -> address instanceof java.net.Inet4Address); }
                    catch (java.net.SocketException e) { return false; }
                }).toList();
        org.junit.jupiter.api.Assumptions.assumeFalse(available.isEmpty(), "No multicast-capable interface for the recovery check");
        var interfaces = new java.util.concurrent.atomic.AtomicReference<java.util.List<java.net.NetworkInterface>>(java.util.List.of());
        try (var directory = new MeshDirectory(() -> null, peer -> {}, interfaces::get)) {
            directory.start();
            assertTrue(directory.error().contains("No multicast-capable"), directory.error());
            interfaces.set(java.util.List.of(available.getFirst()));
            directory.scan();
            assertEquals("", directory.error(), "An earlier missing-LAN warning must not persist after recovery");
        }
    }
}
