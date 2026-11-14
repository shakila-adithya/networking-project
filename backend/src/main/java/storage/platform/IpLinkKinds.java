package storage.platform;

import java.net.*;
import java.util.concurrent.ConcurrentHashMap;

/** Only native adapters may label an IP interface as a hotspot or Wi-Fi Direct link. */
public final class IpLinkKinds {
    private static final ConcurrentHashMap<String, String> KINDS = new ConcurrentHashMap<>();
    private IpLinkKinds() { }
    static void mark(String name, String kind) { KINDS.put(name, kind); }
    static void remove(String name) { KINDS.remove(name); }
    public static String forAddress(InetAddress local) {
        try {
            NetworkInterface nic = NetworkInterface.getByInetAddress(local);
            return nic == null ? "tcp" : KINDS.getOrDefault(nic.getName(), "tcp");
        } catch (SocketException e) { return "tcp"; }
    }
}
