package storage.transport;

import java.io.IOException;

/**
 * The boundary between the mesh logic and whatever carries bytes between devices.
 * Implementations: {@link TcpTransport} (IP link, uses Socket/ServerSocket),
 * {@link InMemoryTransport} (simulation for tests) and, later, a real Bluetooth adapter.
 * Code above this interface must not assume which one it is using.
 */
public interface Transport extends AutoCloseable {
    /** Short label such as "tcp" or "memory". Never label an IP transport as Bluetooth. */
    String kind();

    /** Address other devices use to reach this one through this transport. */
    String localAddress();

    /** Starts accepting incoming links and reports them to the listener. */
    void start(LinkListener incoming) throws IOException;

    /** True if {@link #connect} understands this address format (used to pick a transport by address). */
    default boolean handles(String address) {
        return true;
    }

    /** Opens a link to a neighbour. Throws IOException if it is unreachable. */
    Link connect(String address) throws IOException;

    @Override
    void close();
}
