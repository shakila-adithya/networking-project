package storage.platform;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real Linux NetworkManager D-Bus operations. No shell, password arguments, saved profiles or root escalation.
 * Only profiles created by this instance are removed. Availability does not promise radio/driver interoperability.
 */
public final class NetworkManagerService implements AutoCloseable {
    private static final String BUS = "org.freedesktop.NetworkManager";
    private static final String ROOT = "/org/freedesktop/NetworkManager";
    private static final String DEVICE = BUS + ".Device";
    private static final String P2P = DEVICE + ".WifiP2P";
    private static final DBusPath NONE = new DBusPath("/");
    @DBusInterfaceName("org.freedesktop.NetworkManager")
    public interface Manager extends DBusInterface {
        List<DBusPath> GetDevices();
        DBusPath ActivateConnection(DBusPath profile, DBusPath device, DBusPath specificObject);
        void DeactivateConnection(DBusPath active);
    }
    @DBusInterfaceName("org.freedesktop.NetworkManager.Settings")
    public interface Settings extends DBusInterface {
        DBusPath AddConnectionUnsaved(Map<String, Map<String, Variant<?>>> settings);
    }
    @DBusInterfaceName("org.freedesktop.NetworkManager.Settings.Connection")
    public interface Connection extends DBusInterface { void Delete(); }
    @DBusInterfaceName("org.freedesktop.NetworkManager.Device.WifiP2P")
    public interface WifiP2P extends DBusInterface { void StartFind(Map<String, Variant<?>> options); void StopFind(); }
    public record Adapter(String path, String name, String type, boolean hotspotCapable) { }
    public record P2PPeer(String devicePath, String address, String name, int strength, boolean verified) { }
    private DBusConnection bus;
    private Manager manager;
    private String unavailable = "";
    private DBusPath ownedProfile;
    private DBusPath ownedActive;
    private DBusPath selectedDevice;
    private DBusPath previousProfile;
    private String mode = "";
    private String markedInterface = "";
    private String operationError = "";

    public NetworkManagerService() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            unavailable = "This build has no Windows WinRT Wi-Fi Direct/Mobile Hotspot adapter. LAN remains available; native Windows support is not implemented.";
            return;
        }
        try {
            bus = DBusConnectionBuilder.forSystemBus().withShared(false).build();
            manager = remote(ROOT, Manager.class); manager.GetDevices();
        } catch (Exception e) {
            unavailable = "NetworkManager system D-Bus is unavailable. Install/start NetworkManager and check local D-Bus permissions.";
            closeBus();
        }
    }
    private <T extends DBusInterface> T remote(String path, Class<T> type) throws Exception { return bus.getRemoteObject(BUS, path, type); }
    private Map<String, Variant<?>> properties(String path, String type) throws Exception { return remote(path, Properties.class).GetAll(type); }
    private static Object value(Map<String, Variant<?>> props, String name) { return props.containsKey(name) ? props.get(name).getValue() : null; }
    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    public synchronized List<Adapter> adapters() throws Exception {
        if (bus == null) return List.of();
        List<Adapter> result = new ArrayList<>();
        for (DBusPath path : manager.GetDevices()) {
            var p = properties(path.getPath(), DEVICE);
            int type = number(value(p, "DeviceType"));
            if (type != 2 && type != 30) continue;
            boolean ap = type == 2 && (number(value(properties(path.getPath(), DEVICE + ".Wireless"), "WirelessCapabilities")) & 0x40) != 0;
            result.add(new Adapter(path.getPath(), Objects.toString(value(p, "Interface"), ""), type == 30 ? "wifi-direct" : "wifi", ap));
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    public synchronized List<P2PPeer> peers() throws Exception {
        List<P2PPeer> result = new ArrayList<>();
        for (Adapter device : adapters()) {
            if (!device.type().equals("wifi-direct")) continue;
            Object paths = value(properties(device.path(), P2P), "Peers");
            if (!(paths instanceof List<?> list)) continue;
            for (Object item : list) {
                var p = properties(((DBusPath)item).getPath(), BUS + ".WifiP2PPeer");
                result.add(new P2PPeer(device.path(), Objects.toString(value(p, "HwAddress"), ""),
                        Objects.toString(value(p, "Name"), "Unnamed P2P device"), number(value(p, "Strength")), false));
            }
        }
        return result;
    }
    public synchronized Map<String, Object> status() {
        try {
            var adapters = adapters();
            String state = "STOPPED";
            if (ownedActive != null) {
                int nativeState = number(value(properties(ownedActive.getPath(), BUS + ".Connection.Active"), "State"));
                state = nativeState == 2 ? "ACTIVE" : nativeState == 1 ? "STARTING" : "FAILED";
                if (state.equals("FAILED")) operationError = "The OS did not activate this connection. Check pairing prompts, adapter/driver support, DHCP and NetworkManager authorization; stop the Relay radio connection before retrying.";
                if (nativeState == 2 && selectedDevice != null) {
                    String name = Objects.toString(value(properties(selectedDevice.getPath(), DEVICE), "IpInterface"), "");
                    if (!name.isBlank()) { markedInterface = name; IpLinkKinds.mark(name, mode); }
                }
            }
            return Map.of("available", bus != null, "reason", unavailable, "adapters", adapters, "peers", peers(),
                    "state", state, "mode", mode, "error", operationError,
                    "warning", "Starting or stopping a hotspot or Wi-Fi Direct link may disconnect the current Wi-Fi network. Accept any operating-system pairing prompts.");
        } catch (Exception e) {
            return Map.of("available", false, "reason", "Cannot read NetworkManager devices; check D-Bus permissions and driver support.",
                    "adapters", List.of(), "peers", List.of(), "state", "ERROR", "error", operationError);
        }
    }
    public synchronized void scan() throws IOException {
        requireAvailable();
        try {
            boolean found = false;
            for (Adapter adapter : adapters()) if (adapter.type().equals("wifi-direct")) {
                found = true;
                startFind(remote(adapter.path(), WifiP2P.class));
            }
            if (!found) throw new IOException("No Wi-Fi Direct adapter was reported by NetworkManager.");
            operationError = "";
        } catch (Exception e) { throw failure("Wi-Fi Direct scan failed: " + scanFailureReason(e), e); }
    }
    /** NetworkManager's StartFind contract requires a signed int32 variant ('i'), not UInt32 ('u'). */
    static void startFind(WifiP2P radio) {
        radio.StartFind(Map.of("timeout", new Variant<>(15)));
    }
    /** Preserve the OS explanation instead of blaming hardware or permissions for every failure. */
    static String scanFailureReason(Exception failure) {
        Throwable root = failure;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 8; depth++) root = root.getCause();
        String detail = root.getMessage();
        if (detail == null || detail.isBlank()) return "NetworkManager did not provide a reason. Check the Wi-Fi radio and local scan permissions.";
        detail = detail.replaceAll("[\\p{Cntrl}]", " ").strip();
        return detail.length() > 300 ? detail.substring(0, 300) : detail;
    }
    public synchronized void startHotspot(String devicePath, String ssid, String password, boolean confirmed) throws IOException {
        requireConfirmed(confirmed); requireAvailable();
        if (ssid == null || ssid.isBlank() || ssid.getBytes(StandardCharsets.UTF_8).length > 32
                || password == null || !password.matches("[\\x20-\\x7E]{8,63}"))
            throw new IllegalArgumentException("Use a 1–32 byte hotspot name and an 8–63 character ASCII hotspot password.");
        try {
            Adapter adapter = select(devicePath, "wifi");
            if (!adapter.hotspotCapable()) throw new IOException("This Wi-Fi adapter/driver does not report access-point support.");
            Map<String, Map<String, Variant<?>>> profile = profile("802-11-wireless");
            profile.put("802-11-wireless", settings("ssid", ssid.getBytes(StandardCharsets.UTF_8), "mode", "ap"));
            profile.put("802-11-wireless-security", settings("key-mgmt", "wpa-psk", "psk", password));
            activate(adapter, profile, "hotspot");
        } catch (Exception e) { throw failure("Hotspot could not start. Check AP driver support, DHCP/dnsmasq and NetworkManager/polkit authorization.", e); }
    }
    public synchronized void connectP2P(String devicePath, String address, boolean confirmed) throws IOException {
        requireConfirmed(confirmed); requireAvailable();
        try {
            Adapter adapter = select(devicePath, "wifi-direct");
            if (peers().stream().noneMatch(p -> p.devicePath().equals(devicePath) && p.address().equals(address)))
                throw new IllegalArgumentException("Select a peer from a fresh Wi-Fi Direct scan.");
            var profile = profile("wifi-p2p");
            profile.put("wifi-p2p", settings("peer", address));
            activate(adapter, profile, "wifi-direct");
        } catch (Exception e) { throw failure("Wi-Fi Direct could not connect. Check peer pairing prompts, radio/driver support and local authorization.", e); }
    }
    private Adapter select(String path, String type) throws Exception {
        return adapters().stream().filter(a -> a.path().equals(path) && a.type().equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select a detected compatible adapter."));
    }
    private static Map<String, Variant<?>> settings(Object... values) {
        Map<String, Variant<?>> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String)values[i], new Variant<>(values[i + 1]));
        return result;
    }
    private static Map<String, Map<String, Variant<?>>> profile(String type) {
        Map<String, Map<String, Variant<?>>> profile = new LinkedHashMap<>();
        profile.put("connection", settings("id", "Relay offline", "uuid", UUID.randomUUID().toString(), "type", type, "autoconnect", false));
        profile.put("ipv4", settings("method", type.equals("wifi-p2p") ? "auto" : "shared"));
        profile.put("ipv6", settings("method", "disabled"));
        return profile;
    }
    private void activate(Adapter adapter, Map<String, Map<String, Variant<?>>> profile, String kind) throws Exception {
        if (ownedProfile != null) throw new IllegalArgumentException("Stop the current Relay radio connection first.");
        selectedDevice = new DBusPath(adapter.path());
        DBusPath active = (DBusPath)value(properties(adapter.path(), DEVICE), "ActiveConnection");
        previousProfile = active == null || active.getPath().equals("/") ? null
                : (DBusPath)value(properties(active.getPath(), BUS + ".Connection.Active"), "Connection");
        try { ownedProfile = remote(ROOT + "/Settings", Settings.class).AddConnectionUnsaved(profile); }
        catch (Exception e) { selectedDevice = null; previousProfile = null; throw e; }
        try {
            ownedActive = manager.ActivateConnection(ownedProfile, selectedDevice, NONE);
            mode = kind; operationError = "";
        } catch (Exception e) { cleanup(); throw e; }
    }
    public synchronized void stop(boolean confirmed) throws IOException {
        requireConfirmed(confirmed);
        try { cleanup(); operationError = ""; }
        catch (Exception e) { throw failure("Could not stop/restore the Relay radio connection; reconnect your previous Wi-Fi in system settings.", e); }
    }
    private void cleanup() throws Exception {
        Exception failure = null;
        boolean restorePrevious = false;
        if (previousProfile != null && selectedDevice != null) {
            try {
                DBusPath current = (DBusPath)value(properties(selectedDevice.getPath(), DEVICE), "ActiveConnection");
                restorePrevious = mayRestorePrevious(current == null ? "/" : current.getPath(),
                        ownedActive == null ? "" : ownedActive.getPath());
            } catch (Exception e) { failure = e; }
        }
        if (ownedActive != null) {
            try { manager.DeactivateConnection(ownedActive); } catch (Exception e) { failure = e; }
            ownedActive = null;
        }
        if (ownedProfile != null) {
            try { remote(ownedProfile.getPath(), Connection.class).Delete(); } catch (Exception e) { failure = e; }
            ownedProfile = null;
        }
        if (!markedInterface.isEmpty()) IpLinkKinds.remove(markedInterface);
        markedInterface = "";
        if (previousProfile != null && restorePrevious) {
            try { manager.ActivateConnection(previousProfile, selectedDevice, NONE); } catch (Exception e) { failure = e; }
        }
        previousProfile = null;
        mode = ""; selectedDevice = null;
        if (failure != null) throw failure;
    }
    /** Respect a network the user selected in system settings after Relay started its radio connection. */
    static boolean mayRestorePrevious(String current, String owned) { return "/".equals(current) || current.equals(owned); }
    private void requireAvailable() throws IOException { if (bus == null) throw new IOException(unavailable); }
    public static void requireConfirmed(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("Confirm that this operation may disconnect the current Wi-Fi network.");
    }
    private IOException failure(String message, Exception cause) {
        operationError = message; return new IOException(message, cause);
    }
    private void closeBus() {
        if (bus != null) { try { bus.close(); } catch (IOException e) { System.err.println("NetworkManager D-Bus close failed: " + e.getMessage()); } bus = null; }
    }
    public synchronized void close() {
        if (bus != null) {
            try { cleanup(); } catch (Exception e) { System.err.println("Relay radio cleanup failed; restore Wi-Fi in system settings: " + e.getMessage()); }
            closeBus();
        }
    }
}
