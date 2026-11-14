package storage.messaging;

import storage.transport.Link;
import java.util.Set;
import java.util.UUID;

/** At most one pending health write per carrier; closing the link unblocks a stalled socket write. */
final class LinkPingTask implements Runnable {
    private final MeshNode node;
    private final String neighbour;
    private final Link link;
    private final Set<Link> pending;
    LinkPingTask(MeshNode node, String neighbour, Link link, Set<Link> pending) {
        this.node = node; this.neighbour = neighbour; this.link = link; this.pending = pending;
    }
    @Override public void run() {
        try {
            node.writeCarrier(link, new Message(UUID.randomUUID(), node.localId(), neighbour, MessageType.LINK_PING,
                    1, System.currentTimeMillis(), java.nio.ByteBuffer.allocate(8).putLong(System.nanoTime()).array()));
        } finally { pending.remove(link); }
    }
}
