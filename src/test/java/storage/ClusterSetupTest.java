package storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterSetupTest {
    @TempDir Path root;
    static final String SECRET = "test-secret-never-return-this";
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<LocalNetworks.Candidate> NETWORKS = List.of(
            new LocalNetworks.Candidate("192.168.30.10", "Wi-Fi — 192.168.30.10", true),
            new LocalNetworks.Candidate("10.0.0.10", "Ethernet — 10.0.0.10", true));

    @Test void filtersUnusableNetworks() throws Exception {
        assertTrue(LocalNetworks.suitable(InetAddress.getByName("192.168.1.4"),true,false,false,true));
        for (String ip : List.of("127.0.0.1", "169.254.0.1", "0.0.0.0", "239.1.1.1", "::1"))
            assertFalse(LocalNetworks.suitable(InetAddress.getByName(ip),true,false,false,true));
        var address = InetAddress.getByName("10.0.0.10");
        assertFalse(LocalNetworks.suitable(address,false,false,false,true));
        assertFalse(LocalNetworks.suitable(address,true,true,false,true));
        assertFalse(LocalNetworks.suitable(address,true,false,true,true));
        assertFalse(LocalNetworks.suitable(address,true,false,false,false));
    }
    @Test void persistsConfigurationAndPreservesExistingFiles() throws Exception {
        Path legacy = root.resolve("legacy.json");
        Files.writeString(legacy,"untouched");
        var store = store();
        var config = saved("C", 5010, 6010);
        store.save(config);
        assertEquals(config,store.load());
        store.save(saved("D",5011,6011));
        assertEquals("D",store.load().peer().localPeerId());
        assertEquals("untouched",Files.readString(legacy));
        assertFalse(store.load().toString().contains(SECRET));
        Files.writeString(root.resolve("managed.json"),"invalid " + SECRET);
        assertFalse(assertThrows(java.io.IOException.class,store::load).getMessage().contains(SECRET));
    }
    @Test void validatesAddressPortsAndCredential() throws Exception {
        try (var agent = agent(); var occupied = new ServerSocket(0)) {
            int[] ports = ports();
            assertThrows(IllegalArgumentException.class,() -> agent.validate(join("127.0.0.1",ports[0],ports[1],SECRET)));
            assertThrows(IllegalArgumentException.class,() -> agent.validate(join(NETWORKS.getFirst().address(),occupied.getLocalPort(),ports[1],SECRET)));
            assertThrows(IllegalArgumentException.class,() -> agent.validate(join(NETWORKS.getFirst().address(),ports[0],ports[0],SECRET)));
            assertThrows(IllegalArgumentException.class,() -> agent.validate(join(NETWORKS.getFirst().address(),ports[0],ports[1],"short")));
            assertEquals("C",agent.validate(join(NETWORKS.getFirst().address(),ports[0],ports[1],SECRET)).peer().localPeerId());
        }
    }
    @Test void setupIsReachableWithoutConfigValidationDoesNotSaveApplyDoes() throws Exception {
        var store = store();
        try (var agent = new SetupAgent(0,store,root,() -> NETWORKS)) {
            agent.start();
            var initial = request(agent,"","GET",null,null);
            assertEquals(200,initial.statusCode());
            assertTrue(initial.body().contains("UNCONFIGURED"));
            assertTrue(request(agent,"/networks","GET",null,null).body().contains("Ethernet"));
            assertEquals(400,request(agent,"/restart","POST","{}",null).statusCode());
            assertEquals(400,request(agent,"/apply","POST","invalid " + SECRET,null).statusCode());
            int[] ports = ports();
            String body = JSON.writeValueAsString(join(NETWORKS.getFirst().address(),ports[0],ports[1],SECRET));
            assertEquals(200,request(agent,"/validate","POST",body,null).statusCode());
            assertFalse(store.exists());
            assertEquals(403,request(agent,"/apply","POST",body,"http://evil.example").statusCode());
            assertFalse(store.exists());
            var applied = request(agent,"/apply","POST",body,null);
            assertEquals(200,applied.statusCode());
            assertFalse(applied.body().contains(SECRET));
            assertEquals(SECRET,store.load().peer().discoverySecret());
            var status = request(agent,"","GET",null,null);
            assertTrue(status.body().contains("RESTART_REQUIRED"));
            assertFalse(status.body().contains(SECRET));
            assertFalse(status.body().contains("discoverySecret"));
            // Invalid later applies must not destroy a previously saved valid configuration.
            request(agent,"/apply","POST","{}",null);
            assertEquals("C",store.load().peer().localPeerId());
            // Chosen test IP is not local: restart failure must leave the setup API reachable.
            assertEquals(202,request(agent,"/restart","POST","{}",null).statusCode());
            long deadline = System.currentTimeMillis() + 5000;
            do {
                status = request(agent,"","GET",null,null);
                if (status.body().contains("\"phase\":\"ERROR\"")) break;
                Thread.sleep(25);
            } while (System.currentTimeMillis() < deadline);
            assertTrue(status.body().contains("\"phase\":\"ERROR\""));
            assertFalse(status.body().contains(SECRET));
            // A failed start must release both ports so corrected settings can be applied.
            try (var peerProbe = new ServerSocket(ports[0]);
                 var restProbe = new ServerSocket(ports[1])) {
                assertTrue(peerProbe.isBound());
                assertTrue(restProbe.isBound());
            }
        }
    }
    @Test void authenticatedDuplicateIdentityIsReported() throws Exception {
        var local = saved("C",5010,6010).peer();
        var other = new PeerConfiguration("C","0.0.0.0","192.168.30.11",5010,"unused",
                List.of(new PeerConfiguration.PeerDefinition("C","192.168.30.11",5010)),"test-cluster",true,
                "239.255.42.99",45555,SECRET);
        try (var discovery = new DiscoveryManager(local,new PeerRegistry("C",local.peerEndpoints()),p -> {})) {
            assertFalse(discovery.acceptDatagram(DiscoveryManager.encode(DiscoveryManager.createJoin(other,
                    System.currentTimeMillis(),UUID.randomUUID().toString())),InetAddress.getByName("192.168.30.11")));
            assertEquals(1,discovery.status().identityConflicts());
        }
    }
    private SetupConfigStore store() { return new SetupConfigStore(root.resolve("managed.json")); }
    private SetupAgent agent() throws Exception { return new SetupAgent(0,store(),root,() -> NETWORKS); }
    private SetupAgent.Join join(String address,int peer,int rest,String secret) {
        return new SetupAgent.Join("C","test-cluster",secret,address,peer,rest,"239.255.42.99",45555);
    }
    private SetupConfigStore.Saved saved(String id,int peer,int rest) {
        return new SetupConfigStore.Saved(new PeerConfiguration(id,"0.0.0.0","192.168.30.10",peer,"storage-data/"+id,
                List.of(new PeerConfiguration.PeerDefinition(id,"192.168.30.10",peer)),"test-cluster",true,
                "239.255.42.99",45555,SECRET),rest);
    }
    private int[] ports() throws Exception {
        try (ServerSocket a = new ServerSocket(0); ServerSocket b = new ServerSocket(0)) { return new int[]{a.getLocalPort(),b.getLocalPort()}; }
    }
    private HttpResponse<String> request(SetupAgent agent,String path,String method,String body,String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+agent.port()+"/api/setup"+path));
        if (origin != null) builder.header("Origin",origin);
        builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
