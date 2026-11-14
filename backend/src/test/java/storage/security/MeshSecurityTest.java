package storage.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import storage.transport.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MeshSecurityTest {
    private static final String PASSWORD = "correct horse battery staple";
    private static MeshSecurity security(String password) { return new MeshSecurity("test-mesh", password); }

    @Test void authenticatesAndEncryptsFullSizedFrames() throws Exception {
        try (var server = new ServerSocket(0); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var remote = pool.submit(() -> {
                try (var socket = server.accept(); var link = security(PASSWORD).authenticate(raw(socket), false, "mesh")) {
                    byte[] frame = link.receive();
                    assertEquals(Link.MAX_FRAME_SIZE, frame.length);
                    link.send(frame);
                }
                return true;
            });
            try (var socket = new Socket("127.0.0.1", server.getLocalPort());
                 var link = security(PASSWORD).authenticate(raw(socket), true, "mesh")) {
                byte[] message = new byte[Link.MAX_FRAME_SIZE]; new java.security.SecureRandom().nextBytes(message);
                link.send(message); assertArrayEquals(message, link.receive());
            }
            assertTrue(remote.get());
        }
    }
    @Test void rejectsWrongPasswordAndPurpose() throws Exception {
        rejects("a completely different password", "mesh");
        rejects(PASSWORD, "file-service");
    }
    private void rejects(String password, String purpose) throws Exception {
        try (var server = new ServerSocket(0); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var remote = pool.submit(() -> {
                try (var socket = server.accept()) {
                    assertThrows(java.io.IOException.class, () -> security(PASSWORD).authenticate(raw(socket), false, "mesh"));
                }
                return true;
            });
            try (var socket = new Socket("127.0.0.1", server.getLocalPort())) {
                assertThrows(java.io.IOException.class, () -> security(password).authenticate(raw(socket), true, purpose));
            }
            assertTrue(remote.get());
        }
    }
    @Test void protectsDirectFileServiceAndRejectsLegacyRequests(@TempDir Path temp) throws Exception {
        int port;
        try (var probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        var security = security(PASSWORD);
        try (var server = new PeerServer("127.0.0.1", port, temp.resolve("received"), "B").secure(security)) {
            server.start();
            assertEquals("PONG", PeerClient.authenticated(security, () -> PeerClient.ping("127.0.0.1", port)));
            assertThrows(java.io.IOException.class, () -> PeerClient.authenticated(security("different password here"),
                    () -> PeerClient.ping("127.0.0.1", port)));
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write("PING\n".getBytes()); socket.getOutputStream().flush();
                // Never receive legacy PONG or file contents; server sends only a PAKE announcement.
                byte[] prefix = socket.getInputStream().readNBytes(4);
                assertFalse(new String(prefix).equals("PONG"));
            }
            Path source = temp.resolve("source.txt"); Files.writeString(source, "offline file with spaces");
            var uploaded = PeerClient.authenticated(security, () -> PeerClient.upload("127.0.0.1", port, source));
            assertTrue(uploaded.success(), uploaded.toString());
            Path target = temp.resolve("downloaded.txt");
            assertTrue(PeerClient.authenticated(security, () -> PeerClient.download("127.0.0.1", port, uploaded.fileId(), target)).success());
            assertEquals(Files.readString(source), Files.readString(target));
        }
    }
    @Test void tlsRejectsTamperedCiphertextBeforeApplicationDelivery() throws Exception {
        try (var server = new ServerSocket(0); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var remote = pool.submit(() -> {
                try (var socket = server.accept(); var link = security(PASSWORD).authenticate(raw(socket), false, "mesh")) {
                    assertThrows(java.io.IOException.class, link::receive);
                }
                return true;
            });
            try (var socket = new Socket("127.0.0.1", server.getLocalPort())) {
                Link raw = raw(socket);
                var tamper = new java.util.concurrent.atomic.AtomicBoolean();
                Link wire = new Link() {
                    public String remoteAddress() { return raw.remoteAddress(); }
                    public boolean isOpen() { return raw.isOpen(); }
                    public void close() { raw.close(); }
                    public byte[] receive() throws java.io.IOException { return raw.receive(); }
                    public void send(byte[] frame) throws java.io.IOException {
                        if (frame.length > 16 && tamper.compareAndSet(true, false)) {
                            frame = frame.clone(); frame[frame.length - 1] ^= 1;
                        }
                        raw.send(frame);
                    }
                };
                try (var link = security(PASSWORD).authenticate(wire, true, "mesh")) {
                    tamper.set(true);
                    link.send("Authenticated file contents with spaces".getBytes());
                    assertTrue(remote.get());
                }
            }
        }
    }

    @Test void rejectsSpoofedDiscoveryIdentityAfterPasswordAuthentication() throws Exception {
        var tcp = new TcpTransport("127.0.0.1", 0);
        try (var actual = new storage.messaging.MeshNode("actual-peer", new SecureTransport(tcp, security(PASSWORD)), new storage.messaging.FloodForwarder());
             var local = new storage.messaging.MeshNode("local", new SecureTransport(new TcpTransport("127.0.0.1", 0), security(PASSWORD)), new storage.messaging.FloodForwarder())) {
            actual.start(); local.start();
            var error = assertThrows(java.io.IOException.class, () -> local.connectTo(tcp.localAddress(), "spoofed-peer"));
            assertTrue(error.getMessage().contains("claimed spoofed-peer"));
            assertTrue(local.neighbourIds().isEmpty());
        }
    }

    private static Link raw(Socket socket) throws java.io.IOException {
        return new FramedStreamLink(socket.getInputStream(), socket.getOutputStream(), socket, socket.getRemoteSocketAddress().toString());
    }
}
