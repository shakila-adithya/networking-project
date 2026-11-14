package storage.messaging;

import java.util.UUID;

/**
 * Envelope carried hop by hop through the mesh.
 *
 * @param id        unique per transmission; relays use it to suppress duplicates
 * @param source    id of the originating device
 * @param destination id of the target device, or {@link #BROADCAST}
 * @param ttl       remaining hops; a relay drops the message when this would reach zero
 */
public record Message(UUID id, String source, String destination, MessageType type,
                      int ttl, long timestampMillis, byte[] payload) {
    public static final String BROADCAST = "*";
    public static final int DEFAULT_TTL = 8;
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_PAYLOAD = 32 * 1024;

    public static Message create(String source, String destination, MessageType type, byte[] payload) {
        return new Message(UUID.randomUUID(), source, destination, type, DEFAULT_TTL,
                System.currentTimeMillis(), payload);
    }

    public Message withTtl(int newTtl) {
        return new Message(id, source, destination, type, newTtl, timestampMillis, payload);
    }

    public boolean isBroadcast() {
        return BROADCAST.equals(destination);
    }
}
