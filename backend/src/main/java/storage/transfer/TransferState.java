package storage.transfer;

/** Life cycle of an outgoing transfer. DELIVERED means the receiver verified and stored the file. */
public enum TransferState {
    /** Waiting for the receiver to accept the offer (a route may still be discovered). */
    OFFERING,
    /** Chunks are being sent and acknowledged. */
    SENDING,
    /** The receiver confirmed the SHA-256 and stored the file. */
    DELIVERED,
    /** Rejected, or nothing was acknowledged for the stall timeout. A failed transfer can be resumed. */
    FAILED,
    /** Pending bytes are retained; forwarding resumes when contact becomes available. */
    WAITING
}
