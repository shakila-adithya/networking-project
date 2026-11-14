package storage.messaging;

import java.util.HashSet;
import java.util.Set;

/** Bounded managed flooding: send to every neighbour except the one it came from. TTL and duplicate cache bound it. */
public final class FloodForwarder implements Forwarder {
    @Override
    public Set<String> nextHops(Message message, String fromNeighbour, Set<String> neighbours) {
        Set<String> hops = new HashSet<>(neighbours);
        if (fromNeighbour != null) {
            hops.remove(fromNeighbour);
        }
        return hops;
    }
}
