package storage.messaging;

/** Scheduled neighbour-health evaluation; blocked writes run separately so deadlines still fire. */
final class LinkHealthTask implements Runnable {
    private final MeshNode node;
    LinkHealthTask(MeshNode node) { this.node = node; }
    @Override public void run() { node.checkLinkHealth(); }
}
