package storage.transport;

import java.io.IOException;

/**
 * One established connection to a directly reachable neighbour. Frames are whole messages:
 * the carrier (TCP, Bluetooth, simulation) is responsible for keeping their boundaries.
 */
public interface Link extends AutoCloseable {
    /** Largest frame a link will carry, in bytes. */
    int MAX_FRAME_SIZE = 64 * 1024;

    /** Address of the neighbour as understood by the transport that created this link. */
    String remoteAddress();

    /** Sends one frame. Throws IOException if the link is closed or the write fails. */
    void send(byte[] frame) throws IOException;

    /** Blocks until a frame arrives. Returns null when the neighbour closed the link (EOF). */
    byte[] receive() throws IOException;

    boolean isOpen();

    @Override
    void close();
}
