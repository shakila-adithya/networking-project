package storage.bluetooth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * RFCOMM over the Windows Bluetooth stack: Winsock {@code AF_BTH} / {@code BTHPROTO_RFCOMM} sockets, called through the
 * JDK's FFM API (ws2_32.dll ships with Windows, nothing to install). Same {@link RfcommApi} contract as {@link NativeRfcomm}.
 *
 * <p>Not hardware-verified: it was written from the Winsock/SOCKADDR_BTH documentation and compiled on Linux only.
 * Pure helpers (address packing, struct layout) are unit tested; the socket calls need a Windows machine.
 */
public final class WindowsRfcomm implements RfcommApi {
    static final short AF_BTH = 32;
    private static final int SOCK_STREAM = 1;
    private static final int BTHPROTO_RFCOMM = 3;
    private static final int SD_BOTH = 2;
    private static final short POLLRDNORM = 0x0100;
    private static final short POLL_ERROR_BITS = 0x0001 | 0x0002 | 0x0004; // POLLERR, POLLHUP, POLLNVAL
    static final int SOCKADDR_BTH_SIZE = 30; // packed: family(2) btAddr(8) serviceClass GUID(16) port(4)
    private static final int MAX_IO = 32 * 1024;
    private static final long ACCEPT_SLICE_MILLIS = 100;
    private static final int WSAECONNRESET = 10054, WSAECONNABORTED = 10053, WSAESHUTDOWN = 10058,
            WSAEINTR = 10004, WSAENOTSOCK = 10038;

    private static final class Win {
        static final Linker LINKER = Linker.nativeLinker();
        static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
        static final VarHandle WSA_ERROR = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("WSAGetLastError"));
        static final MethodHandle STARTUP, SOCKET, BIND, LISTEN, ACCEPT, CONNECT, CLOSE, SHUTDOWN, RECV, SEND, POLL;

        static {
            SymbolLookup ws2 = SymbolLookup.libraryLookup("ws2_32", Arena.global());
            Linker.Option err = Linker.Option.captureCallState("WSAGetLastError");
            STARTUP = h(ws2, "WSAStartup", FunctionDescriptor.of(JAVA_INT, JAVA_SHORT, ADDRESS));
            SOCKET = h(ws2, "socket", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT), err);
            BIND = h(ws2, "bind", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT), err);
            LISTEN = h(ws2, "listen", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT), err);
            ACCEPT = h(ws2, "accept", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS), err);
            CONNECT = h(ws2, "connect", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT), err);
            CLOSE = h(ws2, "closesocket", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
            SHUTDOWN = h(ws2, "shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
            RECV = h(ws2, "recv", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT), err);
            SEND = h(ws2, "send", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT), err);
            POLL = h(ws2, "WSAPoll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), err);
            try (Arena arena = Arena.ofConfined()) {
                int result = (int) STARTUP.invokeExact((short) 0x0202, arena.allocate(512)); // WSADATA is 408 bytes on x64
                if (result != 0) throw new IllegalStateException("WSAStartup failed: " + result);
            } catch (Throwable t) {
                throw new IllegalStateException("Winsock could not start: " + t, t);
            }
        }

        static MethodHandle h(SymbolLookup lookup, String name, FunctionDescriptor descriptor, Linker.Option... options) {
            return LINKER.downcallHandle(lookup.find(name).orElseThrow(
                    () -> new UnsatisfiedLinkError("ws2_32 has no " + name)), descriptor, options);
        }
    }

    // ----- pure helpers (unit tested on any OS) -----

    /** BTH_ADDR: the 48-bit address as an integer, first text octet most significant. */
    static long toBthAddress(String address) {
        long value = 0;
        for (String part : BluetoothAddress.normalize(address).split(":")) value = (value << 8) | Integer.parseInt(part, 16);
        return value;
    }

    static String fromBthAddress(long value) {
        StringBuilder text = new StringBuilder();
        for (int shift = 40; shift >= 0; shift -= 8) {
            if (text.length() > 0) text.append(':');
            text.append(String.format("%02X", (value >>> shift) & 0xFF));
        }
        return text.toString();
    }

    /** SOCKADDR_BTH; {@code address == null} means any local adapter. */
    static MemorySegment socketAddress(Arena arena, String address, int channel) {
        MemorySegment segment = arena.allocate(SOCKADDR_BTH_SIZE);
        segment.set(JAVA_SHORT, 0, AF_BTH);
        segment.set(JAVA_LONG.withByteAlignment(1), 2, address == null ? 0L : toBthAddress(address));
        segment.set(JAVA_INT.withByteAlignment(1), 26, channel);
        return segment;
    }

    /** True when the Windows Bluetooth Winsock provider can create an RFCOMM socket. */
    public static boolean isSupported() {
        if (!BluetoothPlatform.isWindows()) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Win.CAPTURE);
            long socket = (long) Win.SOCKET.invokeExact(state, (int) AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
            if (socket == -1L) return false;
            int ignored = (int) Win.CLOSE.invokeExact(socket);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void requireWindows() throws IOException {
        if (!BluetoothPlatform.isWindows()) throw new IOException("Windows RFCOMM requires Windows.");
    }

    @Override
    public RfcommServer listen(int channel) throws IOException {
        requireWindows();
        checkChannel(channel);
        Sock sock = newSocket();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Win.CAPTURE);
            MemorySegment local = socketAddress(arena, null, channel);
            if (call(() -> (int) Win.BIND.invokeExact(state, sock.handle, local, SOCKADDR_BTH_SIZE)) != 0)
                throw failure("bind RFCOMM channel " + channel, state);
            if (call(() -> (int) Win.LISTEN.invokeExact(state, sock.handle, 5)) != 0)
                throw failure("listen on RFCOMM channel " + channel, state);
        } catch (IOException | RuntimeException e) {
            sock.close();
            throw e;
        }
        return new WinServer(sock);
    }

    @Override
    public RfcommConnection connect(String bluetoothAddress, int channel, Duration timeout) throws IOException {
        requireWindows();
        checkChannel(channel);
        String address = BluetoothAddress.normalize(bluetoothAddress);
        Sock sock = newSocket();
        ConnectTask task = new ConnectTask(sock, address, channel);
        Thread worker = new Thread(task, "rfcomm-connect");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(Math.max(1_000, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sock.close();
            throw new IOException("interrupted while connecting to " + address);
        }
        if (worker.isAlive()) { // Winsock has no RFCOMM connect timeout: closing the socket aborts the pending connect
            sock.close();
            throw new IOException("cannot connect to " + address + ": the connection timed out (the device did not answer)");
        }
        if (task.error != null) {
            sock.close();
            throw task.error;
        }
        return new WinConnection(sock, address);
    }

    /** Blocking connect on its own thread so the caller can enforce a deadline. */
    private static final class ConnectTask implements Runnable {
        private final Sock sock;
        private final String address;
        private final int channel;
        volatile IOException error;

        ConnectTask(Sock sock, String address, int channel) {
            this.sock = sock; this.address = address; this.channel = channel;
        }

        @Override
        public void run() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(Win.CAPTURE);
                MemorySegment remote = socketAddress(arena, address, channel);
                if (call(() -> (int) Win.CONNECT.invokeExact(state, sock.handle, remote, SOCKADDR_BTH_SIZE)) != 0)
                    error = connectFailure(address, channel, wsaError(state));
            } catch (IOException e) {
                error = e;
            }
        }
    }

    private static void checkChannel(int channel) {
        if (channel < 1 || channel > 30) throw new IllegalArgumentException("RFCOMM channel must be 1-30");
    }

    private static Sock newSocket() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Win.CAPTURE);
            long handle = callLong(() -> (long) Win.SOCKET.invokeExact(state, (int) AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM));
            if (handle == -1L) throw failure("open an RFCOMM socket (is Bluetooth turned on?)", state);
            return new Sock(handle);
        }
    }

    private interface NativeCall { int run() throws Throwable; }
    private interface NativeLongCall { long run() throws Throwable; }

    private static int call(NativeCall call) throws IOException {
        try { return call.run(); } catch (Throwable t) { throw new IOException("native call failed: " + t, t); }
    }

    private static long callLong(NativeLongCall call) throws IOException {
        try { return call.run(); } catch (Throwable t) { throw new IOException("native call failed: " + t, t); }
    }

    private static int wsaError(MemorySegment state) {
        return (int) Win.WSA_ERROR.get(state, 0L);
    }

    private static IOException failure(String what, MemorySegment state) {
        return new IOException("could not " + what + ": " + describe(wsaError(state)));
    }

    static IOException connectFailure(String address, int channel, int code) {
        String reason = switch (code) {
            case 10061 -> "the device refused the connection (it is not running Relay on RFCOMM channel " + channel + ")";
            case 10065, 10051, 10050, 10064 -> "the device is not reachable (off, out of range, or not connectable)";
            case 10060 -> "the connection timed out (the device did not answer)";
            case 10013 -> "the connection was rejected (pairing or permission required)";
            default -> describe(code);
        };
        return new IOException("cannot connect to " + address + ": " + reason);
    }

    static String describe(int code) {
        return switch (code) {
            case 10048 -> "that RFCOMM channel is already in use (WSA 10048)";
            case 10013 -> "permission denied (WSA 10013)";
            case 10047, 10043 -> "Bluetooth RFCOMM is not available (WSA " + code + ")";
            case WSAECONNRESET -> "connection reset by the device (WSA 10054)";
            default -> "Winsock error " + code;
        };
    }

    /** A socket handle only closed once no thread is inside a call using it (same scheme as {@link NativeRfcomm}). */
    private static final class Sock {
        final long handle;
        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicInteger active = new AtomicInteger();

        Sock(long handle) { this.handle = handle; }

        boolean enter() {
            active.incrementAndGet();
            if (closing.get()) { active.decrementAndGet(); return false; }
            return true;
        }

        void leave() { active.decrementAndGet(); }

        void close() {
            if (!closing.compareAndSet(false, true)) return;
            try {
                int ignored = (int) Win.SHUTDOWN.invokeExact(handle, SD_BOTH); // wakes blocked recv
            } catch (Throwable t) {
                System.err.println("Bluetooth shutdown failed: " + t);
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (active.get() > 0 && System.nanoTime() < deadline) {
                try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (active.get() > 0) {
                System.err.println("Bluetooth socket " + handle + " is still in use; not releasing it");
                return;
            }
            try {
                int ignored = (int) Win.CLOSE.invokeExact(handle);
            } catch (Throwable t) {
                System.err.println("Bluetooth close failed: " + t);
            }
        }
    }

    private static final class WinServer implements RfcommServer {
        private final Sock sock;

        WinServer(Sock sock) { this.sock = sock; }

        @Override
        public RfcommConnection accept(Duration timeout) throws IOException {
            long remaining = Math.max(1, timeout.toMillis());
            while (remaining > 0) {
                long slice = Math.min(remaining, ACCEPT_SLICE_MILLIS);
                RfcommConnection connection = acceptWithin((int) slice);
                if (connection != null) return connection;
                remaining -= slice;
            }
            return null;
        }

        private RfcommConnection acceptWithin(int millis) throws IOException {
            if (!sock.enter()) throw new IOException("server is closed");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(Win.CAPTURE);
                MemorySegment poll = arena.allocate(16); // WSAPOLLFD { SOCKET fd; SHORT events; SHORT revents; }
                poll.set(JAVA_LONG, 0, sock.handle);
                poll.set(JAVA_SHORT, 8, POLLRDNORM);
                int ready = call(() -> (int) Win.POLL.invokeExact(state, poll, 1, millis));
                if (ready < 0) {
                    if (sock.closing.get()) throw new IOException("server is closed");
                    throw failure("wait for a Bluetooth connection", state);
                }
                short events = poll.get(JAVA_SHORT, 10);
                if ((events & POLLRDNORM) == 0) {
                    if ((events & POLL_ERROR_BITS) != 0) throw new IOException("server is closed");
                    return null;
                }
                MemorySegment remote = arena.allocate(SOCKADDR_BTH_SIZE);
                MemorySegment length = arena.allocate(JAVA_INT);
                length.set(JAVA_INT, 0, SOCKADDR_BTH_SIZE);
                long client = callLong(() -> (long) Win.ACCEPT.invokeExact(state, sock.handle, remote, length));
                if (client == -1L) {
                    if (sock.closing.get()) throw new IOException("server is closed");
                    throw failure("accept a Bluetooth connection", state);
                }
                long raw = remote.get(JAVA_LONG.withByteAlignment(1), 2);
                return new WinConnection(new Sock(client), fromBthAddress(raw));
            } finally {
                sock.leave();
            }
        }

        @Override
        public void close() { sock.close(); }
    }

    private static final class WinConnection implements RfcommConnection {
        private final Sock sock;
        private final String remote;
        private final InputStream input = new SockInput();
        private final OutputStream output = new SockOutput();

        WinConnection(Sock sock, String remote) { this.sock = sock; this.remote = remote; }

        @Override public String remoteAddress() { return remote; }
        @Override public InputStream input() { return input; }
        @Override public OutputStream output() { return output; }
        @Override public void close() { sock.close(); }

        private final class SockInput extends InputStream {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int count = read(one, 0, 1);
                return count < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                int wanted = Math.min(length, MAX_IO);
                if (!sock.enter()) return -1;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment state = arena.allocate(Win.CAPTURE);
                    MemorySegment buffer = arena.allocate(wanted);
                    int count = call(() -> (int) Win.RECV.invokeExact(state, sock.handle, buffer, wanted, 0));
                    if (count > 0) {
                        MemorySegment.copy(buffer, JAVA_BYTE, 0, bytes, offset, count);
                        return count;
                    }
                    if (count == 0) return -1;
                    int code = wsaError(state);
                    if (sock.closing.get() || code == WSAECONNRESET || code == WSAECONNABORTED || code == WSAESHUTDOWN
                            || code == WSAEINTR || code == WSAENOTSOCK) return -1;
                    throw failure("read from the Bluetooth link", state);
                } finally {
                    sock.leave();
                }
            }
        }

        private final class SockOutput extends OutputStream {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                int done = 0;
                while (done < length) {
                    int part = Math.min(length - done, MAX_IO);
                    if (!sock.enter()) throw new IOException("Bluetooth link is closed");
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment state = arena.allocate(Win.CAPTURE);
                        MemorySegment buffer = arena.allocate(part);
                        MemorySegment.copy(bytes, offset + done, buffer, JAVA_BYTE, 0, part);
                        int sent = call(() -> (int) Win.SEND.invokeExact(state, sock.handle, buffer, part, 0));
                        if (sent < 0) throw failure("write to the Bluetooth link", state);
                        done += sent;
                    } finally {
                        sock.leave();
                    }
                }
            }
        }
    }
}
