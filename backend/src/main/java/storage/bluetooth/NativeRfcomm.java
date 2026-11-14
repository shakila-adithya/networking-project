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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

public final class NativeRfcomm implements RfcommApi {
    private static final int AF_BLUETOOTH = 31;
    private static final int SOCK_STREAM = 1;
    private static final int BTPROTO_RFCOMM = 3;
    private static final int SOL_SOCKET = 1;
    private static final int SO_SNDTIMEO = 21;
    private static final int SHUT_RDWR = 2;
    private static final int MSG_NOSIGNAL = 0x4000;
    private static final short POLLIN = 0x001;
    private static final short POLLERR_HUP_NVAL = 0x008 | 0x010 | 0x020;
    private static final int EINTR = 4;
    private static final int MAX_IO = 32 * 1024;
    private static final long ACCEPT_SLICE_MILLIS = 100;
    private static final int SOCKADDR_RC_SIZE = 10; // family(2) + bdaddr(6) + channel(1) + padding(1)

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final MethodHandle SOCKET;
    private static final MethodHandle BIND;
    private static final MethodHandle LISTEN;
    private static final MethodHandle ACCEPT;
    private static final MethodHandle CONNECT;
    private static final MethodHandle CLOSE;
    private static final MethodHandle SHUTDOWN;
    private static final MethodHandle RECV;
    private static final MethodHandle SEND;
    private static final MethodHandle POLL;
    private static final MethodHandle SETSOCKOPT;

    static {
        if (isLinux()) {
            SymbolLookup libc = LINKER.defaultLookup();
            Linker.Option errno = Linker.Option.captureCallState("errno");
            SOCKET = handle(libc, "socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), errno);
            BIND = handle(libc, "bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), errno);
            LISTEN = handle(libc, "listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), errno);
            ACCEPT = handle(libc, "accept", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), errno);
            CONNECT = handle(libc, "connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), errno);
            CLOSE = handle(libc, "close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            SHUTDOWN = handle(libc, "shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
            RECV = handle(libc, "recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), errno);
            SEND = handle(libc, "send", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), errno);
            POLL = handle(libc, "poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), errno);
            SETSOCKOPT = handle(libc, "setsockopt",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        } else {
            // The class is loaded by the cross-platform runtime even when TCP is selected.
            // Do not resolve Linux libc symbols on Windows or another non-Linux platform.
            SOCKET = BIND = LISTEN = ACCEPT = CONNECT = CLOSE = SHUTDOWN = RECV = SEND = POLL = SETSOCKOPT = null;
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static MethodHandle handle(SymbolLookup libc, String name, FunctionDescriptor descriptor,
                                       Linker.Option... options) {
        return LINKER.downcallHandle(libc.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("libc has no " + name)), descriptor, options);
    }

    /** True on Linux when the kernel offers RFCOMM sockets (the {@code rfcomm} module is loaded or built in). */
    public static boolean isSupported() {
        if (!isLinux() || SOCKET == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int fd = (int) SOCKET.invokeExact(state, AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
            if (fd < 0) {
                return false;
            }
            int ignored = (int) CLOSE.invokeExact(fd);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= RfcommApi =================

    @Override
    public RfcommServer listen(int channel) throws IOException {
        checkChannel(channel);
        Fd fd = newSocket();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            MemorySegment address = socketAddress(arena, null, channel); // any local adapter
            if (call(() -> (int) BIND.invokeExact(state, fd.number, address, SOCKADDR_RC_SIZE)) < 0) {
                throw failure("bind RFCOMM channel " + channel, state);
            }
            if (call(() -> (int) LISTEN.invokeExact(state, fd.number, 5)) < 0) {
                throw failure("listen on RFCOMM channel " + channel, state);
            }
        } catch (IOException | RuntimeException e) {
            fd.close();
            throw e;
        }
        return new NativeServer(fd);
    }

    @Override
    public RfcommConnection connect(String bluetoothAddress, int channel, Duration timeout) throws IOException {
        checkChannel(channel);
        String address = BluetoothAddress.normalize(bluetoothAddress);
        Fd fd = newSocket();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            // The kernel waits at most this long for the radio connection (page + RFCOMM setup).
            MemorySegment timeval = arena.allocate(16);
            long millis = Math.max(1_000, timeout.toMillis());
            timeval.set(JAVA_LONG, 0, millis / 1000);
            timeval.set(JAVA_LONG, 8, (millis % 1000) * 1000);
            int ignored = call(() -> (int) SETSOCKOPT.invokeExact(fd.number, SOL_SOCKET, SO_SNDTIMEO, timeval, 16));
            MemorySegment remote = socketAddress(arena, address, channel);
            if (call(() -> (int) CONNECT.invokeExact(state, fd.number, remote, SOCKADDR_RC_SIZE)) < 0) {
                throw connectFailure(address, channel, errno(state));
            }
        } catch (IOException | RuntimeException e) {
            fd.close();
            throw e;
        }
        return new NativeConnection(fd, address);
    }

    // ================= sockets =================

    private static void checkChannel(int channel) {
        if (channel < 1 || channel > 30) {
            throw new IllegalArgumentException("RFCOMM channel must be 1-30");
        }
    }

    private static Fd newSocket() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE);
            int number = call(() -> (int) SOCKET.invokeExact(state, AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM));
            if (number < 0) {
                throw failure("open an RFCOMM socket (is the 'rfcomm' kernel module loaded?)", state);
            }
            return new Fd(number);
        }
    }

    /** struct sockaddr_rc { sa_family_t family; bdaddr_t bdaddr; uint8_t channel; } (null address = any adapter). */
    private static MemorySegment socketAddress(Arena arena, String address, int channel) {
        MemorySegment segment = arena.allocate(SOCKADDR_RC_SIZE);
        segment.set(JAVA_SHORT, 0, (short) AF_BLUETOOTH);
        if (address != null) {
            byte[] bytes = BluetoothAddress.toKernelBytes(address);
            for (int i = 0; i < 6; i++) {
                segment.set(JAVA_BYTE, 2 + i, bytes[i]);
            }
        }
        segment.set(JAVA_BYTE, 8, (byte) channel);
        return segment;
    }

    private interface NativeCall {
        int run() throws Throwable;
    }

    private interface NativeLongCall {
        long run() throws Throwable;
    }

    private static int call(NativeCall call) throws IOException {
        try {
            return call.run();
        } catch (Throwable t) {
            throw new IOException("native call failed: " + t, t);
        }
    }

    private static long callLong(NativeLongCall call) throws IOException {
        try {
            return call.run();
        } catch (Throwable t) {
            throw new IOException("native call failed: " + t, t);
        }
    }

    private static int errno(MemorySegment state) {
        return (int) ERRNO.get(state, 0L);
    }

    private static IOException failure(String what, MemorySegment state) {
        int code = errno(state);
        return new IOException("could not " + what + ": " + describe(code));
    }

    private static IOException connectFailure(String address, int channel, int code) {
        String reason = switch (code) {
            case 111 -> "the device refused the connection (it is not running Relay on RFCOMM channel " + channel + ")";
            case 112, 113 -> "the device is not reachable (off, out of range, or not connectable)";
            case 110, 11, 115 -> "the connection timed out (the device did not answer)";
            case 77 -> "the connection was rejected (pairing or permission required)";
            default -> describe(code);
        };
        return new IOException("cannot connect to " + address + ": " + reason);
    }

    private static String describe(int code) {
        return switch (code) {
            case 1, 13 -> "permission denied (errno " + code + ")";
            case 16 -> "address in use (errno 16)";
            case 98 -> "that RFCOMM channel is already in use (errno 98)";
            case 97 -> "Bluetooth RFCOMM is not available (errno 97)";
            case 19, 100 -> "no Bluetooth adapter (errno " + code + ")";
            case 104 -> "connection reset by the device (errno 104)";
            case 32, 107 -> "the connection is closed (errno " + code + ")";
            default -> "errno " + code;
        };
    }

    /** A socket descriptor that is only released once no thread is inside a call using it. */
    private static final class Fd {
        final int number;
        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicInteger active = new AtomicInteger();

        Fd(int number) {
            this.number = number;
        }

        boolean enter() {
            active.incrementAndGet();
            if (closing.get()) {
                active.decrementAndGet();
                return false;
            }
            return true;
        }

        void leave() {
            active.decrementAndGet();
        }

        void close() {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            try {
                int ignored = (int) SHUTDOWN.invokeExact(number, SHUT_RDWR); // wakes blocked read/accept
            } catch (Throwable t) {
                System.err.println("Bluetooth shutdown failed: " + t);
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (active.get() > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (active.get() > 0) {
                // Releasing the number now could let another socket reuse it under a thread that still holds
                // it. Leaking one descriptor is safer than that.
                System.err.println("Bluetooth socket " + number + " is still in use; not releasing it");
                return;
            }
            try {
                int ignored = (int) CLOSE.invokeExact(number);
            } catch (Throwable t) {
                System.err.println("Bluetooth close failed: " + t);
            }
        }
    }

    private static final class NativeServer implements RfcommServer {
        private final Fd fd;

        NativeServer(Fd fd) {
            this.fd = fd;
        }

        @Override
        public RfcommConnection accept(Duration timeout) throws IOException {
            // Waiting in short slices lets close() be noticed promptly: shutdown() does not wake poll() on
            // a listening RFCOMM socket.
            long remaining = Math.max(1, timeout.toMillis());
            while (remaining > 0) {
                long slice = Math.min(remaining, ACCEPT_SLICE_MILLIS);
                RfcommConnection connection = acceptWithin((int) slice);
                if (connection != null) {
                    return connection;
                }
                remaining -= slice;
            }
            return null;
        }

        private RfcommConnection acceptWithin(int millis) throws IOException {
            if (!fd.enter()) {
                throw new IOException("server is closed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CAPTURE);
                MemorySegment poll = arena.allocate(8); // struct pollfd { int fd; short events; short revents; }
                poll.set(JAVA_INT, 0, fd.number);
                poll.set(JAVA_SHORT, 4, POLLIN);
                int ready = call(() -> (int) POLL.invokeExact(state, poll, 1L, millis));
                if (ready < 0) {
                    if (errno(state) == EINTR) {
                        return null;
                    }
                    throw failure("wait for a Bluetooth connection", state);
                }
                short events = poll.get(JAVA_SHORT, 6);
                if ((events & POLLIN) == 0) {
                    if ((events & POLLERR_HUP_NVAL) != 0) {
                        throw new IOException("server is closed");
                    }
                    return null; // slice elapsed, nobody connected
                }
                MemorySegment remote = arena.allocate(SOCKADDR_RC_SIZE);
                MemorySegment length = arena.allocate(JAVA_INT);
                length.set(JAVA_INT, 0, SOCKADDR_RC_SIZE);
                int client = call(() -> (int) ACCEPT.invokeExact(state, fd.number, remote, length));
                if (client < 0) {
                    if (fd.closing.get()) {
                        throw new IOException("server is closed");
                    }
                    throw failure("accept a Bluetooth connection", state);
                }
                byte[] raw = new byte[6];
                for (int i = 0; i < 6; i++) {
                    raw[i] = remote.get(JAVA_BYTE, 2 + i);
                }
                return new NativeConnection(new Fd(client), BluetoothAddress.fromKernelBytes(raw));
            } finally {
                fd.leave();
            }
        }

        @Override
        public void close() {
            fd.close();
        }
    }

    private static final class NativeConnection implements RfcommConnection {
        private final Fd fd;
        private final String remote;
        private final InputStream input = new FdInput();
        private final OutputStream output = new FdOutput();

        NativeConnection(Fd fd, String remote) {
            this.fd = fd;
            this.remote = remote;
        }

        @Override
        public String remoteAddress() {
            return remote;
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void close() {
            fd.close();
        }

        private final class FdInput extends InputStream {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int count = read(one, 0, 1);
                return count < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) {
                    return 0;
                }
                int wanted = Math.min(length, MAX_IO);
                if (!fd.enter()) {
                    return -1;
                }
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment state = arena.allocate(CAPTURE);
                    MemorySegment buffer = arena.allocate(wanted);
                    while (true) {
                        long count = callLong(() -> (long) RECV.invokeExact(state, fd.number, buffer, (long) wanted, 0));
                        if (count > 0) {
                            MemorySegment.copy(buffer, JAVA_BYTE, 0, bytes, offset, (int) count);
                            return (int) count;
                        }
                        if (count == 0) {
                            return -1;
                        }
                        int code = errno(state);
                        if (code == EINTR) {
                            continue;
                        }
                        if (fd.closing.get() || code == 104 || code == 107) {
                            return -1; // closed by us, reset or disconnected by the other side
                        }
                        throw failure("read from the Bluetooth link", state);
                    }
                } finally {
                    fd.leave();
                }
            }
        }

        private final class FdOutput extends OutputStream {
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
                    if (!fd.enter()) {
                        throw new IOException("Bluetooth link is closed");
                    }
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment state = arena.allocate(CAPTURE);
                        MemorySegment buffer = arena.allocate(part);
                        MemorySegment.copy(bytes, offset + done, buffer, JAVA_BYTE, 0, part);
                        long sent = callLong(() -> (long) SEND.invokeExact(state, fd.number, buffer, (long) part, MSG_NOSIGNAL));
                        if (sent < 0) {
                            if (errno(state) == EINTR) {
                                continue;
                            }
                            throw failure("write to the Bluetooth link", state);
                        }
                        done += (int) sent;
                    } finally {
                        fd.leave();
                    }
                }
            }
        }
    }
}
