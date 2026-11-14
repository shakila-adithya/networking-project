package storage.config;

import storage.cluster.PeerEndpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerConfigurationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void parsesLanConfigurationAndResolvesConfiguredEndpoints() throws Exception {
        Path config = temporaryDirectory.resolve("peer.json");
        Files.writeString(config, """
                {
                  "localPeerId":"B",
                  "bindAddress":"0.0.0.0",
                  "advertisedAddress":"192.168.50.11",
                  "port":6101,
                  "storageDirectory":"data/B",
                  "peers":[
                    {"id":"A","address":"192.168.50.10","port":6100},
                    {"id":"B","address":"192.168.50.11","port":6101},
                    {"id":"C","address":"192.168.50.12","port":6102}
                  ]
                }
                """);
        PeerConfiguration loaded = PeerConfiguration.load(config);
        assertEquals("B", loaded.localPeerId());
        assertEquals("0.0.0.0", loaded.bindAddress());
        assertEquals("192.168.50.11", loaded.advertisedAddress());
        assertEquals(Path.of("data/B"), loaded.storagePath());
        assertEquals(List.of(
                new PeerEndpoint("A", "192.168.50.10", 6100),
                new PeerEndpoint("B", "192.168.50.11", 6101),
                new PeerEndpoint("C", "192.168.50.12", 6102)),
                loaded.peerEndpoints());
    }

    @Test
    void localhostLegacyConfigurationRemainsCompatible() {
        PeerConfiguration configuration = PeerConfiguration.localhost("B", "127.0.0.1", 5001);
        assertEquals("B", configuration.localPeerId());
        assertEquals("127.0.0.1", configuration.advertisedAddress());
        assertEquals(5001, configuration.port());
        assertEquals(3, configuration.peerEndpoints().size());
    }

    @Test
    void rejectsDuplicateIdsEndpointsInvalidPortsMissingLocalAndWildcardAdvertisedAddress() {
        assertThrows(IllegalArgumentException.class, () -> new PeerConfiguration("A", "0.0.0.0",
                "192.168.1.10", 5000, "data/A", List.of(
                new PeerConfiguration.PeerDefinition("A", "192.168.1.10", 5000),
                new PeerConfiguration.PeerDefinition("A", "192.168.1.11", 5001))));
        assertThrows(IllegalArgumentException.class, () -> new PeerConfiguration("A", "0.0.0.0",
                "192.168.1.10", 5000, "data/A", List.of(
                new PeerConfiguration.PeerDefinition("A", "192.168.1.10", 5000),
                new PeerConfiguration.PeerDefinition("B", "192.168.1.10", 5000))));
        assertThrows(IllegalArgumentException.class, () -> new PeerConfiguration("A", "0.0.0.0",
                "192.168.1.10", 0, "data/A", List.of(
                new PeerConfiguration.PeerDefinition("A", "192.168.1.10", 0),
                new PeerConfiguration.PeerDefinition("B", "192.168.1.11", 5001))));
        assertThrows(IllegalArgumentException.class, () -> new PeerConfiguration("Z", "0.0.0.0",
                "192.168.1.10", 5000, "data/Z", List.of(
                new PeerConfiguration.PeerDefinition("A", "192.168.1.10", 5000),
                new PeerConfiguration.PeerDefinition("B", "192.168.1.11", 5001))));
        assertThrows(IllegalArgumentException.class, () -> new PeerConfiguration("A", "0.0.0.0",
                "0.0.0.0", 5000, "data/A", List.of(
                new PeerConfiguration.PeerDefinition("A", "192.168.1.10", 5000),
                new PeerConfiguration.PeerDefinition("B", "192.168.1.11", 5001))));
    }

    @Test
    void rejectsUnknownJsonFields() throws Exception {
        Path config = temporaryDirectory.resolve("unknown.json");
        Files.writeString(config, """
                {"localPeerId":"A","bindAddress":"127.0.0.1","advertisedAddress":"127.0.0.1",
                 "port":5000,"storageDirectory":"data/A","unexpected":true,
                 "peers":[{"id":"A","address":"127.0.0.1","port":5000},
                          {"id":"B","address":"127.0.0.1","port":5001}]}
                """);
        assertThrows(Exception.class, () -> PeerConfiguration.load(config));
    }

    @Test
    void discoveryAndStaticModesAllowSinglePeer() {
        List<PeerConfiguration.PeerDefinition> onlyA = List.of(
                new PeerConfiguration.PeerDefinition("A", "192.168.80.10", 6700));
        PeerConfiguration discovery = new PeerConfiguration("A", "0.0.0.0", "192.168.80.10", 6700,
                "data/A", onlyA, "cluster", true, "239.255.42.99", 45555,
                "0123456789abcdef");
        assertEquals(1, discovery.peerEndpoints().size());
        PeerConfiguration standalone = new PeerConfiguration("A", "0.0.0.0",
                "192.168.80.10", 6700, "data/A", onlyA);
        assertEquals(1, standalone.peerEndpoints().size());
    }
}
