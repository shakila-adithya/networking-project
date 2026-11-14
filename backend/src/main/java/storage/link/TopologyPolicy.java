package storage.link;

import java.time.Duration;

/**
 * Limits and preferences for building the neighbour graph.
 *
 * @param minLinks    try to keep at least this many neighbours so one failure cannot isolate the device
 * @param targetLinks stop adding redundancy links beyond this many neighbours
 * @param maxLinks    hard cap from the radio (Bluetooth supports only a few connections); links to devices
 *                    that bridge to unknown parts of the mesh may use the slots between target and max
 * @param minSignal   candidates weaker than this are ignored unless the device has fewer than minLinks
 */
public record TopologyPolicy(int minLinks, int targetLinks, int maxLinks, int minSignal,
                             Duration candidateTtl, Duration retryBase, Duration retryMax, Duration interval) {
    public TopologyPolicy {
        if (minLinks < 1 || minLinks > targetLinks || targetLinks >= maxLinks) {
            throw new IllegalArgumentException("need 1 <= minLinks <= targetLinks < maxLinks "
                    + "(one slot is kept free for bridging)");
        }
    }

    public static TopologyPolicy defaults() {
        return new TopologyPolicy(2, 3, 5, 20, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(60), Duration.ofSeconds(2));
    }
}
