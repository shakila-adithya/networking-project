package storage.bluetooth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import storage.transport.Link;

/** Transport logic over the in-memory fake radio (not real Bluetooth). */
@Timeout(30)
class BluetoothTransportTest {
    private static final String A = "AA:00:00:00:00:01";
    private static final String B = "AA:00:00:00:00:02";

    @Test
    void framesFlowBothWaysAndAddressesCarryThePrefix() throws Exception {
        FakeRfcomm.Air air = new FakeRfcomm.Air();
        BlockingQueue<Link> accepted = new LinkedBlockingQueue<>();
        BluetoothTransport a = new BluetoothTransport(air.device(A), A, BluetoothTransport.DEFAULT_CHANNEL);
        BluetoothTransport b = new BluetoothTransport(air.device(B), B, BluetoothTransport.DEFAULT_CHANNEL);
        try {
            a.start(link -> { });
            b.start(accepted::add);
            assertEquals("bluetooth", a.kind());
            assertEquals("bt:" + A, a.localAddress());
            assertTrue(a.handles("bt:" + B));
            assertFalse(a.handles("127.0.0.1:5000"));

            Link toB = a.connect("bt:" + B);
            Link fromA = accepted.poll(5, TimeUnit.SECONDS);
            assertNotNull(fromA);
            assertEquals("bt:" + B, toB.remoteAddress());
            assertEquals("bt:" + A, fromA.remoteAddress());

            toB.send("hello over the air".getBytes(StandardCharsets.UTF_8));
            assertEquals("hello over the air", new String(fromA.receive(), StandardCharsets.UTF_8));
            fromA.send(new byte[] {1, 2, 3});
            assertArrayEquals(new byte[] {1, 2, 3}, toB.receive());
            assertThrows(IOException.class, () -> toB.send(new byte[Link.MAX_FRAME_SIZE + 1]));

            toB.close();
            assertNull(fromA.receive(), "the other side sees EOF after close");
            assertFalse(toB.isOpen());
            assertThrows(IOException.class, () -> toB.send(new byte[] {1}));
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void connectFailuresAreReportedAsIoExceptions() throws Exception {
        FakeRfcomm.Air air = new FakeRfcomm.Air();
        BluetoothTransport a = new BluetoothTransport(air.device(A), A, BluetoothTransport.DEFAULT_CHANNEL);
        BluetoothTransport b = new BluetoothTransport(air.device(B), B, BluetoothTransport.DEFAULT_CHANNEL);
        try {
            a.start(link -> { });
            assertThrows(IOException.class, () -> a.connect("bt:AA:00:00:00:00:99"), "no such device");
            assertThrows(IOException.class, () -> a.connect("bt:" + B), "device is not listening yet");
            assertThrows(IOException.class, () -> a.connect("not-a-bluetooth-address"));
            b.start(link -> { });
            air.setReachable(A, B, false);
            assertThrows(IOException.class, () -> a.connect("bt:" + B), "out of range");
            air.setReachable(A, B, true);
            assertNotNull(a.connect("bt:" + B));
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void closingTheTransportReleasesItsChannel() throws Exception {
        FakeRfcomm.Air air = new FakeRfcomm.Air();
        FakeRfcomm device = air.device(A);
        BluetoothTransport first = new BluetoothTransport(device, A, 22);
        first.start(link -> { });
        BluetoothTransport second = new BluetoothTransport(device, A, 22);
        assertThrows(IOException.class, () -> second.start(link -> { }), "channel already in use");
        first.close();
        second.start(link -> { });
        second.close();
    }
}
