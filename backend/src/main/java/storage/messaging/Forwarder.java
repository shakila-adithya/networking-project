package storage.messaging;

import java.util.Set;

/**
 * Decides which neighbours receive a message that is not for this device (or is a broadcast).
 * Phase 3 uses {@link FloodForwarder}; routed forwarding can replace it without changing MeshNode.
 */
public interface Forwarder {
    /**
     * @param message   the message with its TTL already decremented
     * @param fromNeighbour neighbour it arrived from, or null if this device originated it
     * @param neighbours ids of currently connected neighbours
     * @return ids of neighbours to send to
     */
    Set<String> nextHops(Message message, String fromNeighbour, Set<String> neighbours);

    /** Called once when the owning node is created. Routers keep it to send control messages. */
    default void attach(MeshNode node) {
    }

    /**
     * Gives a routing protocol first look at control messages (RREQ/RREP/RERR) before duplicate
     * suppression and delivery. Return true if the message was consumed.
     */
    default boolean handleControl(Message message, String fromNeighbour) {
        return false;
    }

    /** A neighbour link was established ({@code up}) or lost. */
    default void neighbourChanged(String neighbourId, boolean up) {
    }

    /** Releases timers and other resources when the node closes. */
    default void close() {
    }
}
