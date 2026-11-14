package storage.web;

import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.Set;

/** Loopback plus Host/Origin checks prevent LAN callers, DNS rebinding and cross-site requests. */
public final class LocalUserGuard {
    private LocalUserGuard() { }
    public static boolean allowed(HttpExchange exchange) {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site)) return false;
        try {
            URI uri = URI.create("http://" + host);
            if (uri.getRawUserInfo() != null || !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())) return false;
            return origin == null || origin.equals("http://" + host);
        } catch (RuntimeException e) { return false; }
    }
}
