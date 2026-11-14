package storage.transport;

/** Task for the server's accept thread: keeps calling accept() until the server is closed. */
final class AcceptLoop implements Runnable {
    private final PeerServer server;

    AcceptLoop(PeerServer server) {
        this.server = server;
    }

    @Override
    public void run() {
        server.acceptLoop();
    }
}
