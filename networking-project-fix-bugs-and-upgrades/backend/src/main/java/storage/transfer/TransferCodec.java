package storage.transfer;

import storage.files.FileMetadata;
import storage.files.MetadataJson;
import storage.transport.Protocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.UUID;

/** Payload formats of the file-transfer messages. Every decoder validates sizes and throws IOException. */
final class TransferCodec {
    static final int ACCEPTED = 0;
    static final int ALREADY_HAVE = 1;
    static final int REJECTED = 2;
    private static final int MAX_REASON = 200;
    private static final int MAX_BITMAP_BYTES = 8 * 1024;

    private TransferCodec() {
    }

    record Offer(UUID transferId, int chunkSize, FileMetadata metadata) { }

    record Accept(UUID transferId, int code, String reason, BitSet received) { }

    record Chunk(UUID transferId, int index, byte[] data) { }

    record Ack(UUID transferId, int index) { }

    record Done(UUID transferId, boolean ok, String reason) { }

    static byte[] encode(Offer offer) throws IOException {
        byte[] meta = MetadataJson.encode(offer.metadata());
        if (meta.length > Protocol.MAX_METADATA_SIZE) {
            throw new IOException("metadata too large");
        }
        return write(out -> {
            writeUuid(out, offer.transferId());
            out.writeInt(offer.chunkSize());
            out.writeInt(meta.length);
            out.write(meta);
        });
    }

    static Offer decodeOffer(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            UUID id = readUuid(in);
            int chunkSize = in.readInt();
            int length = in.readInt();
            if (length < 1 || length > Protocol.MAX_METADATA_SIZE || length > in.available()) {
                throw new IOException("invalid metadata length");
            }
            byte[] meta = new byte[length];
            in.readFully(meta);
            noTrailing(in);
            try {
                return new Offer(id, chunkSize, MetadataJson.decode(meta));
            } catch (Protocol.ProtocolException | IllegalArgumentException e) {
                throw new IOException("invalid file metadata: " + e.getMessage(), e);
            }
        }
    }

    static byte[] encode(Accept accept) throws IOException {
        byte[] bitmap = accept.received().toByteArray();
        if (bitmap.length > MAX_BITMAP_BYTES) {
            throw new IOException("bitmap too large");
        }
        return write(out -> {
            writeUuid(out, accept.transferId());
            out.writeByte(accept.code());
            writeReason(out, accept.reason());
            out.writeInt(bitmap.length);
            out.write(bitmap);
        });
    }

    static Accept decodeAccept(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            UUID id = readUuid(in);
            int code = in.readUnsignedByte();
            if (code > REJECTED) {
                throw new IOException("unknown accept code " + code);
            }
            String reason = readReason(in);
            int length = in.readInt();
            if (length < 0 || length > MAX_BITMAP_BYTES || length > in.available()) {
                throw new IOException("invalid bitmap length");
            }
            byte[] bitmap = new byte[length];
            in.readFully(bitmap);
            noTrailing(in);
            return new Accept(id, code, reason, BitSet.valueOf(bitmap));
        }
    }

    static byte[] encode(Chunk chunk) throws IOException {
        return write(out -> {
            writeUuid(out, chunk.transferId());
            out.writeInt(chunk.index());
            out.write(chunk.data());
        });
    }

    static Chunk decodeChunk(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            UUID id = readUuid(in);
            int index = in.readInt();
            byte[] data = in.readAllBytes();
            return new Chunk(id, index, data);
        }
    }

    static byte[] encode(Ack ack) throws IOException {
        return write(out -> {
            writeUuid(out, ack.transferId());
            out.writeInt(ack.index());
        });
    }

    static Ack decodeAck(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            Ack ack = new Ack(readUuid(in), in.readInt());
            noTrailing(in);
            return ack;
        }
    }

    static byte[] encode(Done done) throws IOException {
        return write(out -> {
            writeUuid(out, done.transferId());
            out.writeByte(done.ok() ? 1 : 0);
            writeReason(out, done.reason());
        });
    }

    static Done decodeDone(byte[] payload) throws IOException {
        try (DataInputStream in = open(payload)) {
            UUID id = readUuid(in);
            int ok = in.readUnsignedByte();
            if (ok > 1) {
                throw new IOException("invalid result flag");
            }
            Done done = new Done(id, ok == 1, readReason(in));
            noTrailing(in);
            return done;
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

    private static void noTrailing(DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new IOException("trailing bytes");
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeReason(DataOutputStream out, String reason) throws IOException {
        String safe = reason == null ? "" : reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
        out.writeUTF(safe);
    }

    private static String readReason(DataInputStream in) throws IOException {
        String reason = in.readUTF();
        if (reason.length() > MAX_REASON) {
            throw new IOException("reason too long");
        }
        return reason;
    }
}
