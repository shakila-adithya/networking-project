package storage.messaging;

/** Outcome of a chat send. Delivery over a mesh is never assumed, only confirmed by an ACK. */
public enum DeliveryStatus {
    /** Sent to neighbours, waiting for the recipient's ACK (may be retried). */
    PENDING,
    /** The recipient's ACK arrived. */
    DELIVERED,
    /** No ACK after all retries, or no neighbour could be reached. */
    FAILED,
    /** Broadcast chat: handed to neighbours; no acknowledgement exists for broadcasts. */
    SENT
}
