package storage.security;

import storage.transport.Link;
import java.io.*;
import java.util.Arrays;

/** Adapts framed carriers to a stream for TLS; no plaintext leaves the TLS layer. */
public final class LinkStreams {
    private LinkStreams() { }
    public static InputStream input(Link link) {
        return new InputStream() {
            private byte[] frame = new byte[0];
            private int offset;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int start, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(start, length, bytes.length);
                if (length == 0) return 0;
                while (offset == frame.length) {
                    frame = link.receive(); offset = 0;
                    if (frame == null) return -1;
                }
                int count = Math.min(length, frame.length - offset);
                System.arraycopy(frame, offset, bytes, start, count); offset += count;
                return count;
            }
            @Override public void close() { link.close(); }
        };
    }
    public static OutputStream output(Link link) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                while (length > 0) {
                    int count = Math.min(length, Link.MAX_FRAME_SIZE);
                    link.send(Arrays.copyOfRange(bytes, offset, offset + count));
                    offset += count; length -= count;
                }
            }
            @Override public void close() { link.close(); }
        };
    }
}
