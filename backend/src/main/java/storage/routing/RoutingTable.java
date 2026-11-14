package storage.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Per-destination next hop, cost and destination sequence number (AODV rules). A newer sequence
 * number always wins; at the same sequence number only a strictly lower cost wins, which keeps routes
 * loop-free. Invalidated routes keep an incremented sequence number so stale replies are ignored.
 */
public final class RoutingTable {
    /** Read-only snapshot of one route. */
    public record Route(String destination, String nextHop, int cost, int hops, long sequence, boolean valid) { }

    /** A route that just became invalid, with the neighbours that were relying on it. */
    public record Invalidated(String destination, long sequence, Set<String> precursors) { }

    private static final class Entry {
        String nextHop;
        int cost;
        int hops;
        long sequence;
        boolean valid;
        long expiresAt;
        long invalidatedAt;
        final Set<String> precursors = new HashSet<>();
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final long lifetimeMillis;
    private final LongSupplier clock;

    public RoutingTable(long lifetimeMillis) {
        this(lifetimeMillis, System::currentTimeMillis);
    }

    RoutingTable(long lifetimeMillis, LongSupplier clock) {
        this.lifetimeMillis = lifetimeMillis;
        this.clock = clock;
    }

    /**
     * True once a route has been unusable (invalidated or expired) for longer than the retention period.
     * Until then it is kept so its sequence number still rejects stale replies; after that the device is
     * forgotten, so departed devices do not linger in the table (and the UI) forever.
     */
    private boolean forgotten(Entry entry) {
        long unusableSince = entry.valid ? entry.expiresAt : entry.invalidatedAt;
        return !usable(entry) && clock.getAsLong() - unusableSince >= lifetimeMillis;
    }

    private void purge() {
        entries.values().removeIf(this::forgotten);
    }

    private boolean usable(Entry entry) {
        return entry.valid && clock.getAsLong() < entry.expiresAt;
    }

    /** Offers a route. Returns true if it replaced or created the stored one. */
    public synchronized boolean update(String destination, String nextHop, long sequence, int cost, int hops) {
        purge();
        Entry entry = entries.get(destination);
        boolean accept;
        if (entry == null || sequence > entry.sequence) {
            accept = true;
        } else if (sequence == entry.sequence) {
            accept = !usable(entry) || cost < entry.cost;
        } else {
            accept = false;
        }
        if (!accept) {
            return false;
        }
        if (entry == null) {
            entry = new Entry();
            entries.put(destination, entry);
        }
        entry.nextHop = nextHop;
        entry.cost = cost;
        entry.hops = hops;
        entry.sequence = sequence;
        entry.valid = true;
        entry.expiresAt = clock.getAsLong() + lifetimeMillis;
        return true;
    }

    /** Returns the route if it is valid and not expired, otherwise null. */
    public synchronized Route get(String destination) {
        Entry entry = entries.get(destination);
        return entry != null && usable(entry) ? snapshot(destination, entry) : null;
    }

    /** Extends the lifetime of a route that is being used. */
    public synchronized void touch(String destination) {
        Entry entry = entries.get(destination);
        if (entry != null && usable(entry)) {
            entry.expiresAt = clock.getAsLong() + lifetimeMillis;
        }
    }

    /** Latest sequence number known for a destination, or -1. Sent in route requests. */
    public synchronized long lastKnownSequence(String destination) {
        Entry entry = entries.get(destination);
        return entry == null ? -1 : entry.sequence;
    }

    public synchronized void addPrecursor(String destination, String neighbourId) {
        Entry entry = entries.get(destination);
        if (entry != null) {
            entry.precursors.add(neighbourId);
        }
    }

    /** Invalidates every valid route that goes through a lost neighbour. */
    public synchronized List<Invalidated> invalidateVia(String nextHop) {
        List<Invalidated> result = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.valid && entry.nextHop.equals(nextHop)) {
                result.add(invalidate(item.getKey(), entry, entry.sequence + 1));
            }
        }
        return result;
    }

    /** Applies a route error from {@code via}. Returns the invalidation, or null if it did not apply. */
    public synchronized Invalidated invalidate(String destination, long sequence, String via) {
        Entry entry = entries.get(destination);
        if (entry == null || !entry.valid || !entry.nextHop.equals(via) || entry.sequence > sequence) {
            return null;
        }
        return invalidate(destination, entry, Math.max(entry.sequence, sequence));
    }

    private Invalidated invalidate(String destination, Entry entry, long newSequence) {
        entry.valid = false;
        entry.invalidatedAt = clock.getAsLong();
        entry.sequence = newSequence;
        Invalidated result = new Invalidated(destination, newSequence, new HashSet<>(entry.precursors));
        entry.precursors.clear();
        return result;
    }

    public synchronized List<Route> snapshot() {
        purge();
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            routes.add(snapshot(item.getKey(), item.getValue()));
        }
        routes.sort(java.util.Comparator.comparing(Route::destination));
        return routes;
    }

    private Route snapshot(String destination, Entry entry) {
        return new Route(destination, entry.nextHop, entry.cost, entry.hops, entry.sequence, usable(entry));
    }
}
