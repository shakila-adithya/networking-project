package storage;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Protocol {
   public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    public static final int MAX_METADATA_SIZE = 64 * 1024;
    public static final int MAX_INVENTORY_ENTRIES = 10_000;
    public static final int MAX_INVENTORY_SIZE = 4 * 1024 * 1024;
    public static final int MAX_LINE_SIZE = 8 * 1024;
    public static final int TRANSFER_BUFFER_SIZE = 64 * 1024;

    private Protocol() {
    }

    public static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= MAX_LINE_SIZE) {
            int value = input.read();
            if (value == -1) {
                if (line.size() == 0) {
                    return null;
                }
                throw new EOFException("connection ended before command line was complete");
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            line.write(value);
        }
        throw new ProtocolException("LINE_TOO_LONG", "command line exceeds " + MAX_LINE_SIZE + " bytes");
    }

    public static void writeLine(OutputStream output, String line) throws IOException {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("protocol lines cannot contain line breaks");
        }
        output.write(line.getBytes(StandardCharsets.UTF_8));
        output.write('\n');
        output.flush();
    }

    public static byte[] readExactly(InputStream input, int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length cannot be negative");
        }
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count == -1) {
                throw new EOFException("connection ended after " + offset + " of " + length + " bytes");
            }
            offset += count;
        }
        return bytes;
    }

    public static void copyExactly(InputStream input, OutputStream output, long length,
                                   MessageDigest digest) throws IOException {
        byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int count = input.read(buffer, 0, requested);
            if (count == -1) {
                throw new EOFException("connection ended with " + remaining + " file bytes remaining");
            }
            output.write(buffer, 0, count);
            if (digest != null) {
                digest.update(buffer, 0, count);
            }
            remaining -= count;
        }
    }

    public static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unspecified error";
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    public static final class ProtocolException extends IOException {
        private final String code;

        public ProtocolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
