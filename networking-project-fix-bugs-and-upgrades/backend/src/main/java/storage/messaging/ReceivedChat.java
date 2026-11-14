package storage.messaging;

import java.util.UUID;

/** A chat message delivered to this device. */
public record ReceivedChat(UUID chatId, String from, String to, String text, long timestampMillis) {
}
