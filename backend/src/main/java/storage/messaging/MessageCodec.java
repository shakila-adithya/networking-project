package storage.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Binary wire format for {@link Message}. Decoding validates every field; malformed frames throw IOException. */
public final class MessageCodec {
    private MessageCodec() {
    }

    public static byte[] encode(Message message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(message.id().getMostSignificantBits());
            out.writeLong(message.id().getLeastSignificantBits());
            writeId(out, message.source());
            writeId(out, message.destination());
            out.writeByte(message.type().ordinal());
            out.writeByte(message.ttl());
            out.writeLong(message.timestampMillis());
            if (message.payload().length > Message.MAX_PAYLOAD) {
                throw new IOException("payload too large");
            }
            out.writeInt(message.payload().length);
            out.write(message.payload());
        }
        return bytes.toByteArray();
    }

    public static Message decode(byte[] frame) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame))) {
            UUID id = new UUID(in.readLong(), in.readLong());
            String source = readId(in);
            String destination = readId(in);
            int typeOrdinal = in.readUnsignedByte();
            if (typeOrdinal >= MessageType.values().length) {
                throw new IOException("unknown message type " + typeOrdinal);
            }
            int ttl = in.readUnsignedByte();
            long timestamp = in.readLong();
            int length = in.readInt();
            if (length < 0 || length > Message.MAX_PAYLOAD || length > in.available()) {
                throw new IOException("invalid payload length " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            if (in.available() != 0) {
                throw new IOException("trailing bytes in frame");
            }
            return new Message(id, source, destination, MessageType.values()[typeOrdinal], ttl, timestamp, payload);
        }
    }

    private static void writeId(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > Message.MAX_ID_LENGTH) {
            throw new IOException("device id must be 1.." + Message.MAX_ID_LENGTH + " bytes");
        }
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readId(DataInputStream in) throws IOException {
        int length = in.readUnsignedByte();
        if (length == 0 || length > Message.MAX_ID_LENGTH || length > in.available()) {
            throw new IOException("invalid device id length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
