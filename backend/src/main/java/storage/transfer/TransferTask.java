package storage.transfer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.Duration;

/**
 * The sending thread of one transfer: offer, then a sliding window of chunks with timed retransmits,
 * then wait for the receiver's verified result. A broken route needs no special handling here: sends go
 * through the router, which rediscovers a path, and unacknowledged chunks are simply sent again.
 */
final class TransferTask implements Runnable {
    private final MeshFileService service;
    private final OutgoingTransfer transfer;
    private final TransferConfig config;

    TransferTask(MeshFileService service, OutgoingTransfer transfer, TransferConfig config) {
        this.service = service;
        this.transfer = transfer;
        this.config = config;
    }

    @Override
    public void run() {
        try (RandomAccessFile file = new RandomAccessFile(transfer.staging.toFile(), "r")) {
            if (!offer()) {
                return;
            }
            if (!sendChunks(file)) {
                return;
            }
            awaitResult();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transfer.pause("paused after restart; sender bytes are retained");
        } catch (IOException e) {
            transfer.fail("cannot read the file to send: " + e.getMessage());
        } catch (RuntimeException e) {
            transfer.fail("internal error: " + e);
            System.err.println("Transfer " + transfer.transferId + " failed unexpectedly: " + e);
        } finally {
            service.transferEnded(transfer);
        }
    }

    private boolean stalled() {
        return transfer.millisSinceProgress() > config.stallTimeout().toMillis();
    }

    private boolean failIfStalled(String phase) {
        if (stalled()) {
            String reason = "no response from " + transfer.to + " for " + config.stallTimeout().toSeconds()
                    + " s while " + phase + " (route unavailable); the transfer can be resumed";
            if (service.durable()) transfer.pause(reason); else transfer.fail(reason);
            return true;
        }
        return false;
    }

    /** Repeats the offer until the receiver answers. Returns false if the transfer ended. */
    private boolean offer() throws InterruptedException {
        long retryMillis = Math.max(50, config.offerRetry().toMillis());
        while (!transfer.isAccepted()) {
            if (transfer.state() == TransferState.FAILED) {
                return false;
            }
            if (failIfStalled("offering the file")) {
                return false;
            }
            service.sendOffer(transfer);
            transfer.awaitChange(retryMillis);
        }
        return transfer.state() != TransferState.FAILED;
    }

    private boolean sendChunks(RandomAccessFile file) throws IOException, InterruptedException {
        long timeoutMillis = Math.max(50, config.chunkTimeout().toMillis());
        long[] sentAt = new long[transfer.totalChunks];
        byte[] buffer = new byte[transfer.chunkSize];
        while (!transfer.allAcked()) {
            if (transfer.state() == TransferState.FAILED || transfer.isFinished()) {
                break;
            }
            if (failIfStalled("sending chunks")) {
                return false;
            }
            long now = System.nanoTime() / 1_000_000;
            int inflight = 0;
            int next = -1;
            for (int i = transfer.nextUnacked(0); i < transfer.totalChunks; i = transfer.nextUnacked(i + 1)) {
                boolean outstanding = sentAt[i] != 0 && now - sentAt[i] < timeoutMillis;
                if (outstanding) {
                    if (++inflight >= config.window()) {
                        break;
                    }
                } else {
                    next = i;
                    break;
                }
            }
            if (next >= 0 && inflight < config.window()) {
                int length = transfer.chunkLength(next);
                file.seek((long) next * transfer.chunkSize);
                file.readFully(buffer, 0, length);
                service.sendChunk(transfer, next, java.util.Arrays.copyOf(buffer, length));
                sentAt[next] = now == 0 ? 1 : now;
                transfer.chunksSent.incrementAndGet();
                pause(config.chunkInterval());
            } else {
                transfer.awaitChange(20);
            }
        }
        return transfer.state() != TransferState.FAILED;
    }

    private void pause(Duration interval) throws InterruptedException {
        if (!interval.isZero()) {
            Thread.sleep(interval.toMillis());
        }
    }

    /** All chunks acknowledged: wait until the receiver confirms it verified and stored the file. */
    private void awaitResult() throws InterruptedException {
        long retryMillis = Math.max(50, config.offerRetry().toMillis());
        while (!transfer.isFinished()) {
            if (transfer.state() == TransferState.FAILED) {
                return;
            }
            if (transfer.isAlreadyHave() && transfer.allAcked()) {
                break;
            }
            if (failIfStalled("waiting for the receiver to verify the file")) {
                return;
            }
            transfer.awaitChange(retryMillis);
            if (!transfer.isFinished()) {
                service.sendOffer(transfer); // makes the receiver re-check and repeat its result
            }
        }
        if (transfer.isFinished() && !transfer.finishedOk()) {
            transfer.fail("receiver refused the file: " + transfer.finishReason());
        } else {
            transfer.markDelivered();
        }
    }
}
