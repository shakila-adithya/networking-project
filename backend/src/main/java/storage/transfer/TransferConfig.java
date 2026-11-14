package storage.transfer;

import java.time.Duration;

/**
 * Tuning for routed file transfer.
 *
 * @param chunkSize    bytes of file content per message (must fit one mesh message)
 * @param window       chunks that may be unacknowledged at once
 * @param chunkTimeout how long to wait for a chunk's acknowledgement before sending it again
 * @param stallTimeout give up (FAILED, resumable) when nothing was acknowledged for this long
 * @param chunkInterval pause between chunk sends, a simple rate limit for slow radios (zero = none)
 * @param offerRetry   how often the offer / final result request is repeated while waiting
 */
public record TransferConfig(int chunkSize, int window, Duration chunkTimeout, Duration stallTimeout,
                             Duration chunkInterval, Duration offerRetry) {
    public static final int MAX_CHUNK_SIZE = 28 * 1024;

    public TransferConfig {
        if (chunkSize < 1024 || chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("chunkSize must be between 1024 and " + MAX_CHUNK_SIZE);
        }
        if (window < 1 || window > 64) {
            throw new IllegalArgumentException("window must be between 1 and 64");
        }
    }

    public static TransferConfig defaults() {
        return new TransferConfig(16 * 1024, 8, Duration.ofSeconds(2), Duration.ofSeconds(45),
                Duration.ZERO, Duration.ofSeconds(2));
    }
}
