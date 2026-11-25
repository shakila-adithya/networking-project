package storage.bluetooth;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static java.lang.foreign.ValueLayout.*;

/** Windows x64 SDP boundary. Service names are discovery hints, never authentication. */
final class WindowsBluetoothService implements AutoCloseable {
    // Application-specific service class; all Relay platforms query this same UUID.
    static final String UUID_TEXT = "6c9e9f40-8a5b-4f86-a4b7-6e8c02d19a31";
    static final int QUERY_SIZE = 120;
    private static final int NS_BTH = 16, RETURN_NAME = 0x10, FLUSH_CACHE = 0x1000;
    private static final int NO_MORE = 10110, NO_MORE_OLD = 10102, NOT_FOUND = 10108;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment registration;
    private boolean registered;

    private static final class Api {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup LIB = SymbolLookup.libraryLookup("ws2_32", Arena.global());
        static final StructLayout STATE = Linker.Option.captureStateLayout();
        static final java.lang.invoke.VarHandle ERROR = STATE.varHandle(MemoryLayout.PathElement.groupElement("WSAGetLastError"));
        static final MethodHandle SET = handle("WSASetServiceW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle BEGIN = handle("WSALookupServiceBeginW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle NEXT = handle("WSALookupServiceNextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle END = handle("WSALookupServiceEnd", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle FORMAT = handle("WSAAddressToStringW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static MethodHandle handle(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(LIB.find(name).orElseThrow(), descriptor,
                    Linker.Option.captureCallState("WSAGetLastError"));
        }
    }

    WindowsBluetoothService(String address, int channel, String name) throws IOException {
        registration = registration(arena, address, channel, name);
        try {
            MemorySegment state = arena.allocate(Api.STATE);
            if ((int) Api.SET.invokeExact(state, registration, 0, 0) != 0) throw failure("publish Relay Bluetooth service", state);
            registered = true;
        } catch (Throwable e) {
            arena.close();
            throw io(e);
        }
    }

    /** WSAQUERYSETW + CSADDR_INFO, kept alive until RNRSERVICE_DELETE succeeds. */
    static MemorySegment registration(Arena arena, String address, int channel, String name) {
        MemorySegment query = query(arena);
        MemorySegment local = WindowsRfcomm.socketAddress(arena, address, channel);
        MemorySegment csa = arena.allocate(40, 8);
        csa.set(ADDRESS, 0, local);
        csa.set(JAVA_INT, 8, WindowsRfcomm.SOCKADDR_BTH_SIZE);
        csa.set(ADDRESS, 16, local);
        csa.set(JAVA_INT, 24, WindowsRfcomm.SOCKADDR_BTH_SIZE);
        csa.set(JAVA_INT, 32, 1); // SOCK_STREAM
        csa.set(JAVA_INT, 36, 3); // BTHPROTO_RFCOMM
        query.set(ADDRESS, 8, wide(arena, name));
        query.set(JAVA_INT, 88, 1);
        query.set(ADDRESS, 96, csa);
        return query;
    }

    static MemorySegment query(Arena arena) {
        MemorySegment query = arena.allocate(QUERY_SIZE, 8);
        query.set(JAVA_INT, 0, QUERY_SIZE);
        query.set(JAVA_INT, 40, NS_BTH);
        query.set(ADDRESS, 16, guid(arena));
        return query;
    }

    static MemorySegment guid(Arena arena) {
        UUID uuid = UUID.fromString(UUID_TEXT);
        MemorySegment guid = arena.allocate(16, 4);
        long high = uuid.getMostSignificantBits(), low = uuid.getLeastSignificantBits();
        guid.set(JAVA_INT, 0, (int) (high >>> 32));
        guid.set(JAVA_SHORT, 4, (short) (high >>> 16));
        guid.set(JAVA_SHORT, 6, (short) high);
        for (int i = 0; i < 8; i++) guid.set(JAVA_BYTE, 8 + i, (byte) (low >>> (56 - 8 * i)));
        return guid;
    }

    static MemorySegment wide(Arena arena, String text) {
        byte[] bytes = (text + '\0').getBytes(StandardCharsets.UTF_16LE);
        MemorySegment result = arena.allocate(bytes.length, 2);
        MemorySegment.copy(bytes, 0, result, JAVA_BYTE, 0, bytes.length);
        return result;
    }

    /** Query one remote radio. The caller has already completed device inquiry. */
    static String findName(String address) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Api.STATE);
            MemorySegment remote = WindowsRfcomm.socketAddress(arena, address, 0);
            MemorySegment context = arena.allocate(80, 2); // Windows requires at least 40 WCHARs
            MemorySegment count = arena.allocate(JAVA_INT);
            count.set(JAVA_INT, 0, 40);
            if ((int) Api.FORMAT.invokeExact(state, remote, WindowsRfcomm.SOCKADDR_BTH_SIZE,
                    MemorySegment.NULL, context, count) != 0) throw failure("format Bluetooth address", state);
            MemorySegment query = query(arena);
            query.set(ADDRESS, 56, context);
            MemorySegment output = arena.allocate(ADDRESS);
            if ((int) Api.BEGIN.invokeExact(state, query, FLUSH_CACHE, output) != 0) {
                if (noResults(state)) return "";
                throw failure("query Relay Bluetooth service", state);
            }
            MemorySegment lookup = output.get(ADDRESS, 0);
            try {
                MemorySegment buffer = arena.allocate(65536, 8);
                for (int i = 0; i < 16; i++) {
                    buffer.fill((byte) 0);
                    buffer.set(JAVA_INT, 0, QUERY_SIZE);
                    count.set(JAVA_INT, 0, (int) buffer.byteSize());
                    if ((int) Api.NEXT.invokeExact(state, lookup, RETURN_NAME, count, buffer) != 0) {
                        if (noResults(state)) return "";
                        throw failure("read Relay Bluetooth service", state);
                    }
                    MemorySegment pointer = buffer.get(ADDRESS, 8);
                    if (!pointer.equals(MemorySegment.NULL)) {
                        String name = WindowsBluetoothAdapter.readWide(pointer.reinterpret(512));
                        if (new Nearby(address, name, null, false).relayDeviceId().isPresent()) return name;
                    }
                }
                return "";
            } finally {
                if ((int) Api.END.invokeExact(state, lookup) != 0)
                    System.err.println(failure("close Bluetooth service query", state).getMessage());
            }
        } catch (Throwable e) { throw io(e); }
    }

    private static boolean noResults(MemorySegment state) {
        int code = (int) Api.ERROR.get(state, 0L);
        return code == NO_MORE || code == NO_MORE_OLD || code == NOT_FOUND;
    }
    private static IOException failure(String action, MemorySegment state) {
        return new IOException("Could not " + action + ": Winsock error " + (int) Api.ERROR.get(state, 0L));
    }
    private static IOException io(Throwable e) { return e instanceof IOException io ? io : new IOException("Windows SDP failed: " + e, e); }

    @Override public synchronized void close() throws IOException {
        if (!registered) return;
        try {
            MemorySegment state = arena.allocate(Api.STATE);
            if ((int) Api.SET.invokeExact(state, registration, 2, 0) != 0) throw failure("remove Relay Bluetooth service", state);
            registered = false;
            arena.close();
        } catch (Throwable e) { throw io(e); }
    }
}
