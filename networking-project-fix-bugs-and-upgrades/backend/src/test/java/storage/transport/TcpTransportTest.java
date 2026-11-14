package storage.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class TcpTransportTest {
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void framesWithSpacesRoundTripAndEofReturnsNull() throws Exception {
        BlockingQueue<Link> accepted = new LinkedBlockingQueue<>();
        try (TcpTransport server = new TcpTransport("127.0.0.1", 0)) {
            server.start(accepted::add);
            Link client = new TcpTransport("127.0.0.1", 0).connect(server.localAddress());
            Link serverSide = accepted.poll(5, TimeUnit.SECONDS);
            assertNotNull(serverSide);

            client.send(bytes("hello mesh world"));
            assertEquals("hello mesh world", new String(serverSide.receive(), StandardCharsets.UTF_8));
            serverSide.send(bytes("reply"));
            assertEquals("reply", new String(client.receive(), StandardCharsets.UTF_8));

            client.close();
            assertNull(serverSide.receive());
            serverSide.close();
        }
    }

    @Test
    void secondClientIsServedWhileFirstIsIdle() throws Exception {
        BlockingQueue<Link> accepted = new LinkedBlockingQueue<>();
        try (TcpTransport server = new TcpTransport("127.0.0.1", 0)) {
            server.start(accepted::add);
            Link idle = new TcpTransport("127.0.0.1", 0).connect(server.localAddress());
            Link second = new TcpTransport("127.0.0.1", 0).connect(server.localAddress());
            assertNotNull(accepted.poll(5, TimeUnit.SECONDS));
            Link secondServerSide = accepted.poll(5, TimeUnit.SECONDS);
            assertNotNull(secondServerSide);
            second.send(bytes("ping"));
            assertEquals("ping", new String(secondServerSide.receive(), StandardCharsets.UTF_8));
            idle.close();
            second.close();
        }
    }

    @Test
    void connectToClosedPortFails() {
        assertThrows(IOException.class, () -> new TcpTransport("127.0.0.1", 0).connect("127.0.0.1:1"));
    }

    @Test
    void oversizedFrameIsRejected() throws Exception {
        BlockingQueue<Link> accepted = new LinkedBlockingQueue<>();
        try (TcpTransport server = new TcpTransport("127.0.0.1", 0)) {
            server.start(accepted::add);
            Link client = new TcpTransport("127.0.0.1", 0).connect(server.localAddress());
            assertThrows(IOException.class, () -> client.send(new byte[Link.MAX_FRAME_SIZE + 1]));
            client.close();
        }
    }
}
