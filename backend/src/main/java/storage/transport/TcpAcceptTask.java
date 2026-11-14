package storage.transport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/** Accept loop for {@link TcpTransport}: wraps each accepted Socket in a Link for the listener. */
final class TcpAcceptTask implements Runnable {
    private final TcpTransport transport;
    private final ServerSocket serverSocket;
    private final LinkListener listener;

    TcpAcceptTask(TcpTransport transport, ServerSocket serverSocket, LinkListener listener) {
        this.transport = transport;
        this.serverSocket = serverSocket;
        this.listener = listener;
    }

    @Override
    public void run() {
        while (transport.isRunning()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (transport.isRunning()) {
                    System.err.println("TCP transport accept failed: " + e.getMessage());
                }
                continue;
            }
            try {
                Link link = new TcpTransport.TcpLink(client, client.getInetAddress().getHostAddress() + ":" + client.getPort());
                listener.onIncomingLink(link);
            } catch (IOException | RuntimeException failure) {
                // Ownership did not reach the listener, so release the socket here.
                System.err.println("Rejected incoming link: " + failure.getMessage());
                try {
                    client.close();
                } catch (IOException closeFailure) {
                    System.err.println("Could not close rejected socket: " + closeFailure.getMessage());
                }
            }
        }
    }
}
