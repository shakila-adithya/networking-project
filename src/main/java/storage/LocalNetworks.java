package storage;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Enumerates actual multicast-capable LAN addresses; selection stays explicit. */
public final class LocalNetworks {
    public record Candidate(String address, String label, boolean privateLan) { }
    public static boolean suitable(InetAddress address, boolean up, boolean loopback,
                                   boolean virtual, boolean multicast) {
        return up && !loopback && !virtual && multicast && address instanceof Inet4Address
                && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isAnyLocalAddress() && !address.isMulticastAddress();
    }
    public static List<Candidate> detect() throws IOException {
        List<Candidate> result = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (suitable(address, network.isUp(), network.isLoopback(), network.isVirtual(), network.supportsMulticast())) {
                    result.add(new Candidate(address.getHostAddress(), network.getDisplayName()
                            + " — " + address.getHostAddress(), address.isSiteLocalAddress()));
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(Candidate::privateLan).reversed()
                .thenComparing(Candidate::label)).toList();
    }
    private LocalNetworks() { }
}
