package storage.messaging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Chat on top of {@link MeshNode}. Each chat has a stable chat id; every transmission attempt uses a
 * fresh envelope id (so relays do not discard retries as duplicates) while the receiver de-duplicates
 * by chat id and re-sends the ACK. Retries are bounded; the final state is DELIVERED or FAILED.
 */
public final class ChatService implements AutoCloseable {
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int INBOX_LIMIT = 500;

    private final MeshNode node;
    private final int maxAttempts;
    private final Duration retryInterval;
    private final ScheduledExecutorService retryTimer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chat-retry-" + node().localId());
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final DuplicateCache deliveredChats = new DuplicateCache(4096);
    private final Deque<ReceivedChat> inbox = new ArrayDeque<>();
    private volatile Consumer<ReceivedChat> listener = chat -> { };

    private static final class Pending {
        final String to;
        final byte[] payload;
        volatile DeliveryStatus status = DeliveryStatus.PENDING;
        int attempts;

        Pending(String to, byte[] payload) {
            this.to = to;
            this.payload = payload;
        }
    }

    public ChatService(MeshNode node, int maxAttempts, Duration retryInterval) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.node = node;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
        node.setHandler(MessageType.CHAT, this::onChat);
        node.setHandler(MessageType.ACK, this::onAck);
    }

    private MeshNode node() {
        return node;
    }

    public void onReceive(Consumer<ReceivedChat> listener) {
        this.listener = listener;
    }

    /** Sends text to a device id, or to everyone with {@link Message#BROADCAST}. Returns the chat id. */
    public UUID send(String to, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length == 0 || textBytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("text must be 1.." + MAX_TEXT_BYTES + " bytes");
        }
        UUID chatId = UUID.randomUUID();
        Pending entry = new Pending(to, encodeChat(chatId, textBytes));
        pending.put(chatId, entry);
        attempt(chatId, entry);
        return chatId;
    }

    public DeliveryStatus status(UUID chatId) {
        Pending entry = pending.get(chatId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown chat id");
        }
        return entry.status;
    }

    public synchronized List<ReceivedChat> inbox() {
        return new ArrayList<>(inbox);
    }

    private void attempt(UUID chatId, Pending entry) {
        synchronized (entry) {
            if (entry.status != DeliveryStatus.PENDING) {
                return;
            }
            entry.attempts++;
            int handedOver = node.originate(Message.create(node.localId(), entry.to, MessageType.CHAT, entry.payload));
            boolean broadcast = Message.BROADCAST.equals(entry.to);
            if (broadcast) {
                entry.status = handedOver > 0 ? DeliveryStatus.SENT : DeliveryStatus.FAILED;
                return;
            }
            if (entry.attempts >= maxAttempts) {
                retryTimer.schedule(() -> expire(entry), retryInterval.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                retryTimer.schedule(() -> attempt(chatId, entry), retryInterval.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private void expire(Pending entry) {
        synchronized (entry) {
            if (entry.status == DeliveryStatus.PENDING) {
                entry.status = DeliveryStatus.FAILED;
            }
        }
    }

    private void onChat(Message message) {
        UUID chatId;
        String text;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message.payload()))) {
            chatId = new UUID(in.readLong(), in.readLong());
            byte[] textBytes = in.readNBytes(MAX_TEXT_BYTES + 1);
            if (textBytes.length == 0 || textBytes.length > MAX_TEXT_BYTES) {
                return;
            }
            text = new String(textBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Dropped malformed chat: " + e.getMessage());
            return;
        }
        if (deliveredChats.firstSeen(chatId)) {
            ReceivedChat chat = new ReceivedChat(chatId, message.source(), message.destination(), text,
                    message.timestampMillis());
            synchronized (this) {
                inbox.addLast(chat);
                while (inbox.size() > INBOX_LIMIT) {
                    inbox.removeFirst();
                }
            }
            listener.accept(chat);
        }
        if (!message.isBroadcast()) { // always re-ACK: the earlier ACK may have been lost
            ByteArrayOutputStream ack = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(ack)) {
                out.writeLong(chatId.getMostSignificantBits());
                out.writeLong(chatId.getLeastSignificantBits());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            node.originate(Message.create(node.localId(), message.source(), MessageType.ACK, ack.toByteArray()));
        }
    }

    private void onAck(Message message) {
        if (message.payload().length != 16) {
            return;
        }
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (message.payload()[i] & 0xFF);
            low = (low << 8) | (message.payload()[8 + i] & 0xFF);
        }
        Pending entry = pending.get(new UUID(high, low));
        if (entry != null && entry.to.equals(message.source())) {
            synchronized (entry) {
                if (entry.status == DeliveryStatus.PENDING) {
                    entry.status = DeliveryStatus.DELIVERED;
                }
            }
        }
    }

    private static byte[] encodeChat(UUID chatId, byte[] textBytes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(chatId.getMostSignificantBits());
            out.writeLong(chatId.getLeastSignificantBits());
            out.write(textBytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public void close() {
        retryTimer.shutdownNow();
    }
}
