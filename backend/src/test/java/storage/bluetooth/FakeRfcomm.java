package storage.bluetooth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TEST FAKE, NOT BLUETOOTH. An in-memory "radio" so the transport and mesh logic can be tested without
 * hardware. Real hardware behaviour is covered by NativeRfcommHardwareTest / BlueZAdapterHardwareTest.
 */
final class FakeRfcomm implements RfcommApi {
    /** The shared air between fake devices. */
    static final class Air {
        final Map<String, FakeRfcomm> devices = new ConcurrentHashMap<>();
        final Map<String, Boolean> blocked = new ConcurrentHashMap<>();

        FakeRfcomm device(String address) {
            FakeRfcomm device = new FakeRfcomm(this, BluetoothAddress.normalize(address));
            devices.put(device.address, device);
            return device;
        }

        void setReachable(String a, String b, boolean reachable) {
            blocked.put(key(a, b), !reachable);
        }

        boolean reachable(String a, String b) {
            return !blocked.getOrDefault(key(a, b), false);
        }

        private static String key(String a, String b) {
            return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        }
    }

    private final Air air;
    final String address;
    private final Map<Integer, FakeServer> servers = new ConcurrentHashMap<>();

    private FakeRfcomm(Air air, String address) {
        this.air = air;
        this.address = address;
    }

    @Override
    public RfcommServer listen(int channel) throws IOException {
        FakeServer server = new FakeServer(this, channel);
        if (servers.putIfAbsent(channel, server) != null) {
            throw new IOException("that RFCOMM channel is already in use");
        }
        return server;
    }

    @Override
    public RfcommConnection connect(String bluetoothAddress, int channel, Duration timeout) throws IOException {
        String remote = BluetoothAddress.normalize(bluetoothAddress);
        FakeRfcomm peer = air.devices.get(remote);
        if (peer == null || !air.reachable(address, remote)) {
            throw new IOException("cannot connect to " + remote + ": the device is not reachable");
        }
        FakeServer server = peer.servers.get(channel);
        if (server == null) {
            throw new IOException("cannot connect to " + remote + ": the device refused the connection");
        }
        Pipe toPeer = new Pipe();
        Pipe toMe = new Pipe();
        FakeConnection mine = new FakeConnection(remote, toMe, toPeer);
        FakeConnection theirs = new FakeConnection(address, toPeer, toMe);
        server.pending.add(theirs);
        return mine;
    }

    private static final class FakeServer implements RfcommServer {
        final FakeRfcomm owner;
        final int channel;
        final BlockingQueue<FakeConnection> pending = new LinkedBlockingQueue<>();
        private volatile boolean closed;

        FakeServer(FakeRfcomm owner, int channel) {
            this.owner = owner;
            this.channel = channel;
        }

        @Override
        public RfcommConnection accept(Duration timeout) throws IOException {
            if (closed) {
                throw new IOException("server is closed");
            }
            try {
                return pending.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {
            closed = true;
            owner.servers.remove(channel, this);
        }
    }

    /** One direction of a connection. close() delivers EOF to the reader. */
    static final class Pipe {
        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int position;
        private volatile boolean closed;

        void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("connection closed");
            }
            chunks.add(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
        }

        int read(byte[] bytes, int offset, int length) throws IOException {
            while (position >= current.length) {
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                position = 0;
                if (current == EOF) {
                    chunks.add(EOF);
                    current = new byte[0];
                    return -1;
                }
            }
            int count = Math.min(length, current.length - position);
            System.arraycopy(current, position, bytes, offset, count);
            position += count;
            return count;
        }

        void close() {
            if (!closed) {
                closed = true;
                chunks.add(EOF);
            }
        }
    }

    private static final class FakeConnection implements RfcommConnection {
        private final String remote;
        private final Pipe in;
        private final Pipe out;

        FakeConnection(String remote, Pipe in, Pipe out) {
            this.remote = remote;
            this.in = in;
            this.out = out;
        }

        @Override
        public String remoteAddress() {
            return remote;
        }

        @Override
        public InputStream input() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int count = read(one, 0, 1);
                    return count < 0 ? -1 : one[0] & 0xFF;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    return in.read(bytes, offset, length);
                }
            };
        }

        @Override
        public OutputStream output() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    out.write(new byte[] {(byte) value}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
            };
        }

        @Override
        public void close() {
            out.close();
            in.close();
        }
    }
}
