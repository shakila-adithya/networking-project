package storage.bluetooth;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The local Windows Bluetooth radio: address and visibility through bthprops.cpl, device inquiry through Winsock's
 * {@code NS_BTH} name space. Companion of {@link WindowsRfcomm}.
 *
 * <p>Windows publishes Relay identity in an SDP service record instead of renaming the radio.
 * Inquiry finds radios; an SDP lookup then identifies Relay on radios without a Relay name.
 */
public final class WindowsBluetoothAdapter implements LocalBluetooth {
    private static final int NS_BTH = 16;
    private static final int LUP_CONTAINERS = 0x0002, LUP_RETURN_NAME = 0x0010, LUP_RETURN_ADDR = 0x0100,
            LUP_FLUSHCACHE = 0x1000;
    private static final int WSAQUERYSET_SIZE = 120;
    private static final int WSA_E_NO_MORE = 10110, WSAENOMORE = 10102;
    private static final int RADIO_INFO_SIZE = 520;
    private static final int RESULT_BUFFER = 4096;

    private static final class Api {
        static final Linker LINKER = Linker.nativeLinker();
        static final java.lang.foreign.StructLayout STATE = Linker.Option.captureStateLayout();
        static final java.lang.invoke.VarHandle LAST_ERROR = STATE.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
        static final MethodHandle FIND_FIRST_RADIO, FIND_RADIO_CLOSE, GET_RADIO_INFO, IS_DISCOVERABLE, ENABLE_DISCOVERY,
                ENABLE_INCOMING, IS_CONNECTABLE, CLOSE_HANDLE, LOOKUP_BEGIN, LOOKUP_NEXT, LOOKUP_END, WSA_ERROR;

        static {
            SymbolLookup bth = SymbolLookup.libraryLookup("bthprops.cpl", Arena.global());
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            SymbolLookup ws2 = SymbolLookup.libraryLookup("ws2_32", Arena.global());
            FIND_FIRST_RADIO = LINKER.downcallHandle(bth.find("BluetoothFindFirstRadio").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), Linker.Option.captureCallState("GetLastError"));
            FIND_RADIO_CLOSE = h(bth, "BluetoothFindRadioClose", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            GET_RADIO_INFO = h(bth, "BluetoothGetRadioInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            IS_DISCOVERABLE = h(bth, "BluetoothIsDiscoverable", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            ENABLE_DISCOVERY = hWithError(bth, "BluetoothEnableDiscovery");
            ENABLE_INCOMING = hWithError(bth, "BluetoothEnableIncomingConnections");
            IS_CONNECTABLE = h(bth, "BluetoothIsConnectable", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            CLOSE_HANDLE = h(k32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            LOOKUP_BEGIN = h(ws2, "WSALookupServiceBeginW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            LOOKUP_NEXT = h(ws2, "WSALookupServiceNextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            LOOKUP_END = h(ws2, "WSALookupServiceEnd", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            WSA_ERROR = h(ws2, "WSAGetLastError", FunctionDescriptor.of(JAVA_INT));
        }

        static MethodHandle h(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(lookup.find(name).orElseThrow(
                    () -> new UnsatisfiedLinkError(name + " is missing")), descriptor);
        }
        static MethodHandle hWithError(SymbolLookup lookup, String name) {
            return LINKER.downcallHandle(lookup.find(name).orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), Linker.Option.captureCallState("GetLastError"));
        }
    }

    private final MemorySegment radio; // HANDLE, owned for the adapter's lifetime
    private final String address;
    private final String radioName;
    private final Path restoreFile;
    private final boolean wasDiscoverable;
    private final boolean wasConnectable;
    private boolean advertising;
    private WindowsBluetoothService service;
    private String advertisedName = "";
    private volatile String scanError = "";
    private final RadioState connectable = new ConnectableState();
    private final RadioState discoverable = new DiscoverableState();

    /** BOOL reports whether Windows changed a state; an already-correct state is also success. */
    interface RadioState {
        boolean get() throws Throwable;
        int set(boolean enabled) throws Throwable;
    }
    static void ensureState(RadioState state, boolean enabled, String label) throws IOException {
        try {
            if (state.get() == enabled) return;
            int error = state.set(enabled);
            if (state.get() != enabled)
                throw new IOException("Windows could not " + (enabled ? "enable " : "disable ") + label
                        + (error == 0 ? "" : " (Windows error " + error + ")"));
        } catch (IOException e) { throw e; }
        catch (Throwable e) { throw new IOException("Could not update " + label + ": " + e, e); }
    }
    private int setRadioState(MethodHandle function, boolean enabled) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Api.STATE);
            int changed = (int) function.invokeExact(state, radio, enabled ? 1 : 0);
            return changed == 0 ? (int) Api.LAST_ERROR.get(state, 0L) : 0;
        }
    }
    private final class ConnectableState implements RadioState {
        public boolean get() throws Throwable { return (int) Api.IS_CONNECTABLE.invokeExact(radio) != 0; }
        public int set(boolean enabled) throws Throwable { return setRadioState(Api.ENABLE_INCOMING, enabled); }
    }
    private final class DiscoverableState implements RadioState {
        public boolean get() throws Throwable { return (int) Api.IS_DISCOVERABLE.invokeExact(radio) != 0; }
        public int set(boolean enabled) throws Throwable { return setRadioState(Api.ENABLE_DISCOVERY, enabled); }
    }

    private WindowsBluetoothAdapter(MemorySegment radio, String address, String radioName, boolean wasDiscoverable,
                                    boolean wasConnectable, Path restoreFile) {
        this.radio = radio; this.address = address; this.radioName = radioName;
        this.wasDiscoverable = wasDiscoverable; this.restoreFile = restoreFile;
        this.wasConnectable = wasConnectable;
    }

    /** Opens the first Bluetooth radio. {@code restoreFile} is unused: visibility is restored in {@link #close()}. */
    public static WindowsBluetoothAdapter open(Path restoreFile) throws IOException {
        if (!BluetoothPlatform.isWindows()) throw new IOException("Windows Bluetooth requires Windows.");
        if (!WindowsRfcomm.isSupported()) throw new IOException("Bluetooth is turned off or no Bluetooth radio is present");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(4);
            params.set(JAVA_INT, 0, 4); // BLUETOOTH_FIND_RADIO_PARAMS.dwSize
            MemorySegment handleOut = arena.allocate(ADDRESS);
            MemorySegment state = arena.allocate(Api.STATE);
            MemorySegment find = (MemorySegment) Api.FIND_FIRST_RADIO.invokeExact(state, params, handleOut);
            if (find.equals(MemorySegment.NULL)) throw new IOException("no Bluetooth radio found (Windows error "
                    + (int) Api.LAST_ERROR.get(state, 0L) + "); check that Bluetooth is switched on");
            MemorySegment radio = handleOut.get(ADDRESS, 0);
            int ignored = (int) Api.FIND_RADIO_CLOSE.invokeExact(find);
            MemorySegment info = arena.allocate(RADIO_INFO_SIZE);
            info.set(JAVA_INT, 0, RADIO_INFO_SIZE);
            int status = (int) Api.GET_RADIO_INFO.invokeExact(radio, info);
            if (status != 0) {
                int ignoredClose = (int) Api.CLOSE_HANDLE.invokeExact(radio);
                throw new IOException("could not read the Bluetooth radio (error " + status + ")");
            }
            String address = WindowsRfcomm.fromBthAddress(info.get(JAVA_LONG, 8));
            String name = readWide(info.asSlice(16, 496));
            boolean discoverable = ((int) Api.IS_DISCOVERABLE.invokeExact(radio)) != 0;
            boolean connectable = ((int) Api.IS_CONNECTABLE.invokeExact(radio)) != 0;
            return new WindowsBluetoothAdapter(radio.reinterpret(8), address, name, discoverable, connectable, restoreFile);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("cannot reach the Windows Bluetooth stack: " + t, t);
        }
    }

    @Override public String discoveryLimitation() {
        return scanError;
    }
    @Override public String address() { return address; }
    @Override public synchronized String name() { return advertising ? advertisedName : radioName; }
    @Override public synchronized boolean isAdvertising() { return advertising; }

    @Override
    public List<Nearby> scan(Duration duration) throws IOException {
        // Winsock's Bluetooth inquiry length is chosen by the stack (about 10 s); `duration` only bounds retries.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment query = arena.allocate(WSAQUERYSET_SIZE);
            query.set(JAVA_INT, 0, WSAQUERYSET_SIZE);
            query.set(JAVA_INT, 40, NS_BTH);
            MemorySegment handleOut = arena.allocate(ADDRESS);
            int flags = LUP_CONTAINERS | LUP_RETURN_NAME | LUP_RETURN_ADDR | LUP_FLUSHCACHE;
            if (((int) Api.LOOKUP_BEGIN.invokeExact(query, flags, handleOut)) != 0)
                throw new IOException("Bluetooth scan failed: Winsock error " + (int) Api.WSA_ERROR.invokeExact());
            MemorySegment lookup = handleOut.get(ADDRESS, 0);
            Map<String, Nearby> found = new LinkedHashMap<>();
            try {
                MemorySegment buffer = arena.allocate(RESULT_BUFFER);
                MemorySegment length = arena.allocate(JAVA_INT);
                while (true) {
                    buffer.fill((byte) 0);
                    buffer.set(JAVA_INT, 0, WSAQUERYSET_SIZE);
                    length.set(JAVA_INT, 0, RESULT_BUFFER);
                    int result = (int) Api.LOOKUP_NEXT.invokeExact(lookup, LUP_RETURN_NAME | LUP_RETURN_ADDR, length, buffer);
                    if (result != 0) {
                        int code = (int) Api.WSA_ERROR.invokeExact();
                        if (code == WSA_E_NO_MORE || code == WSAENOMORE) break;
                        throw new IOException("Bluetooth scan failed: Winsock error " + code);
                    }
                    Nearby device = parseResult(buffer);
                    if (device != null) found.putIfAbsent(device.address(), device);
                }
            } finally {
                int ignored = (int) Api.LOOKUP_END.invokeExact(lookup);
            }
            scanError = "";
            long deadline = System.nanoTime() + duration.toNanos();
            List<Nearby> identified = new ArrayList<>();
            for (Nearby device : found.values()) {
                if (device.relayDeviceId().isEmpty() && System.nanoTime() < deadline) {
                    try {
                        String identity = WindowsBluetoothService.findName(device.address());
                        if (!identity.isEmpty()) device = new Nearby(device.address(), identity, device.rssi(), device.paired());
                    } catch (IOException e) { scanError = "Some Bluetooth service queries failed: " + e.getMessage(); }
                }
                identified.add(device);
            }
            return identified;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Bluetooth scan failed: " + t, t);
        }
    }

    /** Reads WSAQUERYSETW.lpszServiceInstanceName and the first CSADDR_INFO's remote SOCKADDR_BTH. RSSI is not reported. */
    private static Nearby parseResult(MemorySegment buffer) {
        MemorySegment base = buffer.reinterpret(RESULT_BUFFER);
        MemorySegment namePointer = base.get(ADDRESS, 8);
        String name = namePointer.equals(MemorySegment.NULL) ? "" : readWide(namePointer.reinterpret(512));
        if (base.get(JAVA_INT, 88) < 1) return null;
        MemorySegment csa = base.get(ADDRESS, 96).reinterpret(40);
        MemorySegment remote = csa.get(ADDRESS, 16).reinterpret(WindowsRfcomm.SOCKADDR_BTH_SIZE);
        long value = remote.get(JAVA_LONG.withByteAlignment(1), 2);
        return new Nearby(WindowsRfcomm.fromBthAddress(value), name, null, false);
    }

    @Override
    public synchronized void advertise(String deviceId, String displayName, String meshId) throws IOException {
        if (!deviceId.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("invalid device id");
        String wanted = meshId == null ? Nearby.advertisedName(displayName, deviceId)
                : Nearby.advertisedMeshName(displayName, deviceId, meshId);
        try {
            advertising = true; // own partial visibility changes, including a failed enable or SDP call
            // Windows requires incoming connections enabled BEFORE enabling discovery.
            ensureState(connectable, true, "Bluetooth incoming connections");
            ensureState(discoverable, true, "Bluetooth discovery");
            if (service != null && wanted.equals(advertisedName)) return;
            if (service != null) { service.close(); service = null; }
            service = new WindowsBluetoothService(address,
                    Integer.getInteger("mesh.bluetooth.channel", BluetoothTransport.DEFAULT_CHANNEL), wanted);
            advertisedName = wanted;
        } catch (IOException e) {
            try { stopAdvertising(); } catch (IOException restore) { e.addSuppressed(restore); }
            throw e;
        } catch (Throwable t) {
            IOException failure = new IOException("could not change Bluetooth visibility: " + t, t);
            try { stopAdvertising(); } catch (IOException restore) { failure.addSuppressed(restore); }
            throw failure;
        }
    }

    @Override
    public synchronized void stopAdvertising() throws IOException {
        IOException failure = null;
        if (service != null) {
            try { service.close(); service = null; } catch (IOException e) { failure = e; }
        }
        if (!advertising) { if (failure != null) throw failure; return; }
        // Restore both settings even if one fails, using the order required by Windows.
        RadioState[] states = wasDiscoverable ? new RadioState[]{connectable, discoverable} : new RadioState[]{discoverable, connectable};
        boolean[] wanted = wasDiscoverable ? new boolean[]{wasConnectable, true} : new boolean[]{false, wasConnectable};
        String[] labels = wasDiscoverable ? new String[]{"Bluetooth incoming connections", "Bluetooth discovery"}
                : new String[]{"Bluetooth discovery", "Bluetooth incoming connections"};
        for (int i = 0; i < states.length; i++) {
            try { ensureState(states[i], wanted[i], labels[i]); }
            catch (IOException restore) { if (failure == null) failure = restore; else failure.addSuppressed(restore); }
        }
        advertising = false;
        advertisedName = "";
        if (failure != null) throw failure;
    }

    @Override
    public synchronized void close() {
        try {
            stopAdvertising();
        } catch (IOException e) {
            System.err.println("Could not restore Bluetooth visibility: " + e.getMessage());
        }
        try {
            int ignored = (int) Api.CLOSE_HANDLE.invokeExact(radio);
        } catch (Throwable t) {
            System.err.println("Bluetooth radio handle close failed: " + t);
        }
    }

    /** Decodes a NUL-terminated UTF-16LE string from native memory. */
    static String readWide(MemorySegment segment) {
        int end = 0;
        while (end + 1 < segment.byteSize() && (segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, end) != 0
                || segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, end + 1) != 0)) end += 2;
        byte[] bytes = new byte[end];
        MemorySegment.copy(segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes, 0, end);
        return new String(bytes, StandardCharsets.UTF_16LE);
    }
}
