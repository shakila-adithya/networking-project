package storage.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.BitSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import storage.files.FileMetadata;

class TransferCodecTest {
    private static final String SHA = "a".repeat(64);

    @Test
    void everyMessageRoundTrips() throws Exception {
        UUID id = UUID.randomUUID();
        FileMetadata meta = new FileMetadata(UUID.randomUUID(), "notes.txt", 1234, SHA,
                "2026-01-01T00:00:00Z", java.util.List.of("B"));
        TransferCodec.Offer offer = TransferCodec.decodeOffer(TransferCodec.encode(new TransferCodec.Offer(id, 4096, meta)));
        assertEquals(id, offer.transferId());
        assertEquals(4096, offer.chunkSize());
        assertEquals(meta, offer.metadata());

        BitSet have = new BitSet();
        have.set(0); have.set(5); have.set(200);
        TransferCodec.Accept accept = TransferCodec.decodeAccept(
                TransferCodec.encode(new TransferCodec.Accept(id, TransferCodec.ACCEPTED, "ok", have)));
        assertEquals(have, accept.received());
        assertEquals("ok", accept.reason());

        byte[] data = {1, 2, 3, 4};
        TransferCodec.Chunk chunk = TransferCodec.decodeChunk(TransferCodec.encode(new TransferCodec.Chunk(id, 7, data)));
        assertEquals(7, chunk.index());
        assertArrayEquals(data, chunk.data());

        assertEquals(new TransferCodec.Ack(id, 9), TransferCodec.decodeAck(TransferCodec.encode(new TransferCodec.Ack(id, 9))));
        assertEquals(new TransferCodec.Done(id, false, "bad"),
                TransferCodec.decodeDone(TransferCodec.encode(new TransferCodec.Done(id, false, "bad"))));
    }

    @Test
    void malformedPayloadsAreRejected() throws Exception {
        UUID id = UUID.randomUUID();
        assertThrows(IOException.class, () -> TransferCodec.decodeOffer(new byte[5]));
        assertThrows(IOException.class, () -> TransferCodec.decodeAck(new byte[3]));
        byte[] ack = TransferCodec.encode(new TransferCodec.Ack(id, 1));
        assertThrows(IOException.class, () -> TransferCodec.decodeAck(java.util.Arrays.copyOf(ack, ack.length + 1)));
        byte[] done = TransferCodec.encode(new TransferCodec.Done(id, true, ""));
        done[16] = 7; // result flag must be 0 or 1
        assertThrows(IOException.class, () -> TransferCodec.decodeDone(done));
        byte[] accept = TransferCodec.encode(new TransferCodec.Accept(id, TransferCodec.ACCEPTED, "", new BitSet()));
        accept[16] = 9; // unknown accept code
        assertThrows(IOException.class, () -> TransferCodec.decodeAccept(accept));
    }

    @Test
    void configRejectsUnusableSizes() {
        assertThrows(IllegalArgumentException.class, () -> new TransferConfig(10, 4, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferConfig(TransferConfig.MAX_CHUNK_SIZE + 1, 4, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new TransferConfig(4096, 0, null, null, null, null));
    }
}
