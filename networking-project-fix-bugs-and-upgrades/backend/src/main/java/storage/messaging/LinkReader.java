package storage.messaging;

import storage.transport.Link;
import java.io.IOException;

/** Task for one neighbour link: exchange HELLO, then read frames until EOF and hand them to the node. */
final class LinkReader implements Runnable {
    private final MeshNode node;
    private final Link link;
    private final boolean initiatedLocally;

    LinkReader(MeshNode node, Link link, boolean initiatedLocally) {
        this.node = node;
        this.link = link;
        this.initiatedLocally = initiatedLocally;
    }

    @Override
    public void run() {
        String remoteId = null;
        try {
            node.sendHello(link);
            byte[] first = link.receive();
            if (first == null) {
                return;
            }
            Message hello = MessageCodec.decode(first);
            if (hello.type() != MessageType.HELLO) {
                throw new IOException("expected HELLO first");
            }
            if (!node.register(hello.source(), link, initiatedLocally, MeshNode.decodeName(hello.payload()))) {
                return; // duplicate link lost the tie-break, or node is closing
            }
            remoteId = hello.source();
            byte[] frame;
            while ((frame = link.receive()) != null) {
                try {
                    node.receive(MessageCodec.decode(frame), remoteId);
                } catch (IOException malformed) {
                    System.err.println("Dropped malformed frame from " + remoteId + ": " + malformed.getMessage());
                }
            }
        } catch (IOException e) {
            if (link.isOpen()) {
                System.err.println("Link " + link.remoteAddress() + " ended: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            System.err.println("Link reader failed: " + e);
        } finally {
            if (remoteId != null) {
                node.unregister(remoteId, link);
            }
            link.close();
        }
    }
}
