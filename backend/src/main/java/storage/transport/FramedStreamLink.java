package storage.transport;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Link} over any byte stream (Bluetooth RFCOMM, a serial line, ...): every frame is a 4-byte
 * length followed by that many bytes, so message boundaries survive the stream.
 */
public final class FramedStreamLink implements Link {
    private final String remote;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable closer;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FramedStreamLink(InputStream in, OutputStream out, Closeable closer, String remote) {
        this.in = new DataInputStream(in);
        this.out = new DataOutputStream(out);
        this.closer = closer;
        this.remote = remote;
    }

    @Override
    public String remoteAddress() {
        return remote;
    }

    @Override
    public synchronized void send(byte[] frame) throws IOException {
        if (closed.get()) {
            throw new IOException("link to " + remote + " is closed");
        }
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
        } catch (IOException e) {
            if (closed.get()) {
                return null; // we closed the link ourselves
            }
            throw e;
        }
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("invalid frame length " + length);
        }
        byte[] frame = new byte[length];
        try {
            in.readFully(frame);
        } catch (EOFException e) {
            return null;
        }
        return frame;
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                closer.close();
            } catch (IOException e) {
                System.err.println("Could not close link to " + remote + ": " + e.getMessage());
            }
        }
    }
}
