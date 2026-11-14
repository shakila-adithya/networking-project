package storage.transport;

import java.net.Socket;

/** Task that serves one accepted TCP connection on its own thread. It owns the socket once started. */
final class ClientHandler implements Runnable {
    private final PeerServer server;
    private final Socket client;

    ClientHandler(PeerServer server, Socket client) {
        this.server = server;
        this.client = client;
    }

    @Override
    public void run() {
        server.handleClient(client);
    }
}
