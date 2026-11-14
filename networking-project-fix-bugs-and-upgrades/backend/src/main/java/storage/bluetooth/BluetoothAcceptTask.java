package storage.bluetooth;

import storage.transport.LinkListener;
import java.io.IOException;
import java.time.Duration;

/** Accept loop of {@link BluetoothTransport}: waits for remote devices and hands each link to the listener. */
final class BluetoothAcceptTask implements Runnable {
    private static final Duration POLL = Duration.ofMillis(500);
    private final BluetoothTransport transport;
    private final RfcommServer server;
    private final LinkListener listener;

    BluetoothAcceptTask(BluetoothTransport transport, RfcommServer server, LinkListener listener) {
        this.transport = transport;
        this.server = server;
        this.listener = listener;
    }

    @Override
    public void run() {
        while (transport.isRunning()) {
            RfcommConnection connection;
            try {
                connection = server.accept(POLL);
            } catch (IOException e) {
                if (transport.isRunning()) {
                    System.err.println("Bluetooth accept failed: " + e.getMessage());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            if (connection == null) {
                continue;
            }
            try {
                listener.onIncomingLink(BluetoothTransport.link(connection));
            } catch (RuntimeException failure) {
                // Ownership did not reach the listener, so release the connection here.
                System.err.println("Rejected incoming Bluetooth link: " + failure);
                connection.close();
            }
        }
    }
}
