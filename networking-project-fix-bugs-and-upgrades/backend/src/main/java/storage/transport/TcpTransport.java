package storage.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** IP transport: a ServerSocket accept loop plus Sockets, with 4-byte length-prefixed frames. */
public final class TcpTransport implements Transport {
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private final String bindAddress;
    private final int requestedPort;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public TcpTransport(String bindAddress, int port) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
    }

    @Override
    public String kind() {
        return "tcp";
    }

    @Override
    public synchronized String localAddress() {
        int port = serverSocket == null ? requestedPort : serverSocket.getLocalPort();
        return bindAddress + ":" + port;
    }

    @Override
    public synchronized void start(LinkListener incoming) throws IOException {
        if (running) {
            throw new IllegalStateException("transport already started");
        }
        serverSocket = new ServerSocket(requestedPort, 50, InetAddress.getByName(bindAddress));
        running = true;
        acceptThread = new Thread(new TcpAcceptTask(this, serverSocket, incoming), "tcp-transport-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    boolean isRunning() {
        return running;
    }

    @Override
    public boolean handles(String address) {
        return !address.startsWith("bt:");
    }

    @Override
    public Link connect(String address) throws IOException {
        int split = address.lastIndexOf(':');
        if (split < 1) {
            throw new IOException("address must be host:port");
        }
        int port;
        try {
            port = Integer.parseInt(address.substring(split + 1));
        } catch (NumberFormatException e) {
            throw new IOException("invalid port in " + address);
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName(address.substring(0, split)), port),
                    CONNECT_TIMEOUT_MILLIS);
            return new TcpLink(socket, address);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("Could not close TCP transport: " + e.getMessage());
            }
        }
        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try {
                acceptThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A framed connection over one Socket. */
    static final class TcpLink implements Link {
        private final Socket socket;
        private final String remote;
        private final DataInputStream in;
        private final DataOutputStream out;

        TcpLink(Socket socket, String remote) throws IOException {
            this.socket = socket;
            this.remote = remote;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        }

        @Override
        public String remoteAddress() {
            return remote;
        }

        @Override
        public synchronized void send(byte[] frame) throws IOException {
            if (frame.length > MAX_FRAME_SIZE) {
                throw new IOException("frame too large: " + frame.length);
            }
            out.writeInt(frame.length);
            out.write(frame);
            out.flush();
        }

        @Override
        public byte[] receive() throws IOException {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException e) {
                return null;
            }
            if (length < 0 || length > MAX_FRAME_SIZE) {
                throw new IOException("invalid frame length " + length);
            }
            byte[] frame = new byte[length];
            in.readFully(frame);
            return frame;
        }

        @Override
        public boolean isOpen() {
            return !socket.isClosed();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Could not close link to " + remote + ": " + e.getMessage());
            }
        }
    }
}
