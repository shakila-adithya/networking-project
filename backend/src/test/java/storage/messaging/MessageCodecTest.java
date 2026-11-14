package storage.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageCodecTest {
    @Test
    void roundTripPreservesEveryField() throws Exception {
        Message original = new Message(UUID.randomUUID(), "alice", "bob", MessageType.CHAT, 5, 12345L,
                "héllo wörld".getBytes(StandardCharsets.UTF_8));
        Message decoded = MessageCodec.decode(MessageCodec.encode(original));
        assertEquals(original.id(), decoded.id());
        assertEquals("alice", decoded.source());
        assertEquals("bob", decoded.destination());
        assertEquals(MessageType.CHAT, decoded.type());
        assertEquals(5, decoded.ttl());
        assertEquals(12345L, decoded.timestampMillis());
        assertArrayEquals(original.payload(), decoded.payload());
    }

    @Test
    void malformedFramesAreRejected() throws Exception {
        byte[] good = MessageCodec.encode(Message.create("a", "b", MessageType.CHAT, new byte[] {1, 2}));
        assertThrows(IOException.class, () -> MessageCodec.decode(new byte[0]));
        assertThrows(IOException.class, () -> MessageCodec.decode(java.util.Arrays.copyOf(good, good.length - 1)));
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(IOException.class, () -> MessageCodec.decode(trailing));
        byte[] badType = good.clone();
        badType[16 + 1 + 1 + 1 + 1] = 99; // type byte after id(16) + len+"a" + len+"b"
        assertThrows(IOException.class, () -> MessageCodec.decode(badType));
    }

    @Test
    void emptyOrOversizedDeviceIdsAreRejected() {
        assertThrows(IOException.class, () -> MessageCodec.encode(Message.create("", "b", MessageType.CHAT, new byte[0])));
        assertThrows(IOException.class, () -> MessageCodec.encode(
                Message.create("x".repeat(65), "b", MessageType.CHAT, new byte[0])));
    }

    @Test
    void duplicateCacheForgetsOldestWhenFull() {
        DuplicateCache cache = new DuplicateCache(2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertTrue(cache.firstSeen(a));
        assertFalse(cache.firstSeen(a));
        assertTrue(cache.firstSeen(b));
        assertTrue(cache.firstSeen(c)); // evicts a
        assertTrue(cache.firstSeen(a));
    }
}
