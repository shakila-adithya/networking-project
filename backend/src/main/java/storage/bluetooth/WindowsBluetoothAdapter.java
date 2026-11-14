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
 * <p>Windows has no public API to rename the local radio, so unlike {@link BlueZAdapter} this adapter cannot publish the
 * {@code [relay:id] [mesh:id]} tags in the Bluetooth name. {@link #advertise} makes the radio discoverable and connectable
 * only; Linux peers recognise Relay devices by those name tags and will therefore not list a Windows device until it is
 * reached some other way. Windows can still discover Linux Relay devices. Not hardware-verified (compiled on Linux only).
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
        static final MethodHandle FIND_FIRST_RADIO, FIND_RADIO_CLOSE, GET_RADIO_INFO, IS_DISCOVERABLE, ENABLE_DISCOVERY,
                ENABLE_INCOMING, CLOSE_HANDLE, LOOKUP_BEGIN, LOOKUP_NEXT, LOOKUP_END, WSA_ERROR;

        static {
            SymbolLookup bth = SymbolLookup.libraryLookup("bthprops.cpl", Arena.global());
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            SymbolLookup ws2 = SymbolLookup.libraryLookup("ws2_32", Arena.global());
            FIND_FIRST_RADIO = h(bth, "BluetoothFindFirstRadio", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            FIND_RADIO_CLOSE = h(bth, "BluetoothFindRadioClose", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            GET_RADIO_INFO = h(bth, "BluetoothGetRadioInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            IS_DISCOVERABLE = h(bth, "BluetoothIsDiscoverable", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            ENABLE_DISCOVERY = h(bth, "BluetoothEnableDiscovery", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            ENABLE_INCOMING = h(bth, "BluetoothEnableIncomingConnections", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
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
    }

    private final MemorySegment radio; // HANDLE, owned for the adapter's lifetime
    private final String address;
    private final String radioName;
    private final Path restoreFile;
    private final boolean wasDiscoverable;
    private boolean advertising;

    private WindowsBluetoothAdapter(MemorySegment radio, String address, String radioName, boolean wasDiscoverable, Path restoreFile) {
        this.radio = radio; this.address = address; this.radioName = radioName;
        this.wasDiscoverable = wasDiscoverable; this.restoreFile = restoreFile;
    }

    /** Opens the first Bluetooth radio. {@code restoreFile} is unused: visibility is restored in {@link #close()}. */
    public static WindowsBluetoothAdapter open(Path restoreFile) throws IOException {
        if (!BluetoothPlatform.isWindows()) throw new IOException("Windows Bluetooth requires Windows.");
        if (!WindowsRfcomm.isSupported()) throw new IOException("Bluetooth is turned off or no Bluetooth radio is present");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(4);
            params.set(JAVA_INT, 0, 4); // BLUETOOTH_FIND_RADIO_PARAMS.dwSize
            MemorySegment handleOut = arena.allocate(ADDRESS);
            MemorySegment find = (MemorySegment) Api.FIND_FIRST_RADIO.invokeExact(params, handleOut);
            if (find.equals(MemorySegment.NULL)) throw new IOException("no Bluetooth radio found");
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
            return new WindowsBluetoothAdapter(radio.reinterpret(8), address, name, discoverable, restoreFile);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("cannot reach the Windows Bluetooth stack: " + t, t);
        }
    }

    @Override public String discoveryLimitation() {
        return "Windows radio discovery is available, but this Relay version cannot publish its Bluetooth service identity on Windows. Other Relay devices may not find this device over Bluetooth. Use a shared Wi-Fi/Ethernet network. Windows Bluetooth is not hardware-verified.";
    }
    @Override public String address() { return address; }
    @Override public synchronized String name() { return radioName; }
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
            return new ArrayList<>(found.values());
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
        try {
            if (((int) Api.ENABLE_DISCOVERY.invokeExact(radio, 1)) == 0
                    || ((int) Api.ENABLE_INCOMING.invokeExact(radio, 1)) == 0)
                throw new IOException("Windows refused to make Bluetooth discoverable/connectable");
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("could not change Bluetooth visibility: " + t, t);
        }
        advertising = true;
    }

    @Override
    public synchronized void stopAdvertising() throws IOException {
        if (!advertising) return;
        try {
            int a = (int) Api.ENABLE_DISCOVERY.invokeExact(radio, wasDiscoverable ? 1 : 0);
        } catch (Throwable t) {
            throw new IOException("could not restore Bluetooth visibility: " + t, t);
        }
        advertising = false;
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
