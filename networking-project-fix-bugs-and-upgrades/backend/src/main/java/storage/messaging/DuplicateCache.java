package storage.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, thread-safe memory of recently seen ids (oldest evicted first) for duplicate suppression. */
public final class DuplicateCache {
    private final Map<UUID, Boolean> seen;

    public DuplicateCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.seen = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Returns true the first time an id is offered, false for every repeat while it is remembered. */
    public synchronized boolean firstSeen(UUID id) {
        return seen.put(id, Boolean.TRUE) == null;
    }
}
