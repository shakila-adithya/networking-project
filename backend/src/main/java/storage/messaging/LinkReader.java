package storage.messaging;

import storage.transport.Link;
import java.io.IOException;

/** Task for one neighbour link: exchange HELLO, then read frames until EOF and hand them to the node. */
final class LinkReader implements Runnable {
    private final MeshNode node;
    private final Link link;
    private final boolean initiatedLocally;
    private final String expectedId;
    private final java.util.concurrent.CompletableFuture<String> outcome;
    private final java.util.concurrent.ScheduledFuture<?> deadline;

    LinkReader(MeshNode node, Link link, boolean initiatedLocally, String expectedId,
               java.util.concurrent.CompletableFuture<String> outcome, java.util.concurrent.ScheduledFuture<?> deadline) {
        this.expectedId = expectedId; this.outcome = outcome; this.deadline = deadline;
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
            if (expectedId != null && !expectedId.equals(hello.source()))
                throw new IOException("Unverified discovery claimed " + expectedId + " but authenticated peer identifies as " + hello.source());
            if (!node.register(hello.source(), link, initiatedLocally, node.helloName(hello.source(), hello.payload()))) {
                if (node.neighbourIds().contains(hello.source())) outcome.complete(hello.source());
                return; // duplicate link lost the tie-break, or node is closing
            }
            remoteId = hello.source();
            deadline.cancel(false);
            outcome.complete(remoteId);
            byte[] frame;
            while ((frame = link.receive()) != null) {
                try {
                    node.receive(MessageCodec.decode(frame), remoteId, link);
                } catch (IOException malformed) {
                    System.err.println("Dropped malformed frame from " + remoteId + ": " + malformed.getMessage());
                }
            }
        } catch (IOException e) {
            outcome.completeExceptionally(e);
            if (link.isOpen()) {
                System.err.println("Link " + link.remoteAddress() + " ended: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            System.err.println("Link reader failed: " + e);
        } finally {
            deadline.cancel(false);
            outcome.completeExceptionally(new IOException("Link closed before HELLO completed"));
            if (remoteId != null) {
                node.unregister(remoteId, link);
            }
            link.close();
        }
    }
}
