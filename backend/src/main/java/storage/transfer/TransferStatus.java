package storage.transfer;

import java.util.UUID;

/**
 * Snapshot of one outgoing transfer for display.
 *
 * @param percent  share of chunks acknowledged by the receiver (0-100)
 * @param stalled  true while chunks are outstanding but nothing was acknowledged recently (no route)
 * @param chunksSent number of chunk transmissions, including retransmits
 */
public record TransferStatus(UUID transferId, UUID fileId, String fileName, String to, TransferState state,
                             long size, int totalChunks, int ackedChunks, int percent, boolean stalled,
                             int chunksSent, String error) {
}
