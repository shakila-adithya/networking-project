package storage.messaging;

/** Kinds of mesh message. The wire value is the ordinal, so only append new constants. */
public enum MessageType {
    /** First frame on every link: tells the neighbour who we are. Never forwarded. */
    HELLO,
    /** Text chat from one device to another, or to everyone (destination "*"). */
    CHAT,
    /** Delivery acknowledgement for a CHAT, addressed back to the sender. */
    ACK,
    /** Route request (flooded while looking for a path). Handled by the router, not delivered to apps. */
    RREQ,
    /** Route reply, unicast back along the reverse path. */
    RREP,
    /** Route error: tells upstream nodes that destinations became unreachable. */
    RERR,
    /** File transfer: sender offers a file (metadata + chunk size). */
    FILE_OFFER,
    /** File transfer: receiver accepts or rejects an offer and reports which chunks it already holds. */
    FILE_ACCEPT,
    /** File transfer: one chunk of file content. */
    FILE_CHUNK,
    /** File transfer: receiver acknowledges one chunk. */
    FILE_CHUNK_ACK,
    /** File transfer: final result after the receiver verified and stored the file. */
    FILE_DONE,
    /** A device announces its display name. Broadcast (hop-limited) so devices several hops away learn it too. */
    PRESENCE
}
