package storage.routing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Payload formats of the routing control messages. Decoding validates sizes and throws IOException. */
final class RoutingCodec {
    static final int MAX_ERROR_ENTRIES = 32;
    private static final int MAX_ID = 64;

    private RoutingCodec() {
    }

    /** RREQ payload. The originator is the envelope's source. */
    record RouteRequest(long requestId, long originSequence, String destination, long destinationSequence,
                        int cost, int hops) { }

    /** RREP payload. {@code origin} is who asked; the envelope destination is the same device. */
    record RouteReply(String destination, long destinationSequence, String origin, int cost, int hops) { }

    record Unreachable(String destination, long sequence) { }

    static byte[] encode(RouteRequest r) throws IOException {
        return write(out -> {
            out.writeLong(r.requestId());
            out.writeLong(r.originSequence());
            writeId(out, r.destination());
            out.writeLong(r.destinationSequence());
            out.writeInt(r.cost());
            out.writeInt(r.hops());
        });
    }

    static RouteRequest decodeRequest(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            RouteRequest request = new RouteRequest(in.readLong(), in.readLong(), readId(in), in.readLong(),
                    in.readInt(), in.readInt());
            finish(in, request.cost(), request.hops());
            return request;
        }
    }

    static byte[] encode(RouteReply r) throws IOException {
        return write(out -> {
            writeId(out, r.destination());
            out.writeLong(r.destinationSequence());
            writeId(out, r.origin());
            out.writeInt(r.cost());
            out.writeInt(r.hops());
        });
    }

    static RouteReply decodeReply(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            RouteReply reply = new RouteReply(readId(in), in.readLong(), readId(in), in.readInt(), in.readInt());
            finish(in, reply.cost(), reply.hops());
            return reply;
        }
    }

    static byte[] encodeErrors(List<Unreachable> errors) throws IOException {
        if (errors.isEmpty() || errors.size() > MAX_ERROR_ENTRIES) {
            throw new IOException("error list must hold 1.." + MAX_ERROR_ENTRIES + " entries");
        }
        return write(out -> {
            out.writeByte(errors.size());
            for (Unreachable item : errors) {
                writeId(out, item.destination());
                out.writeLong(item.sequence());
            }
        });
    }

    static List<Unreachable> decodeErrors(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            int count = in.readUnsignedByte();
            if (count == 0 || count > MAX_ERROR_ENTRIES) {
                throw new IOException("invalid error count " + count);
            }
            List<Unreachable> errors = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                errors.add(new Unreachable(readId(in), in.readLong()));
            }
            finish(in, 0, 0);
            return errors;
        }
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        }
        return bytes.toByteArray();
    }

    private static DataInputStream open(byte[] payload) {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static void finish(DataInputStream in, int cost, int hops) throws IOException {
        if (cost < 0 || hops < 0) {
            throw new IOException("negative cost or hops");
        }
        if (in.available() != 0) {
            throw new IOException("trailing bytes");
        }
    }

    private static void writeId(DataOutputStream out, String id) throws IOException {
        if (id.isEmpty() || id.length() > MAX_ID) {
            throw new IOException("invalid device id length");
        }
        out.writeUTF(id);
    }

    private static String readId(DataInputStream in) throws IOException {
        String id = in.readUTF();
        if (id.isEmpty() || id.length() > MAX_ID) {
            throw new IOException("invalid device id length");
        }
        return id;
    }
}
