package storage.transfer;

import storage.files.FileMetadata;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** State of one outgoing transfer, shared between the sending thread and the message handlers. */
final class OutgoingTransfer {
    final UUID transferId;
    final String to;
    final FileMetadata metadata;
    final Path staging;
    final int chunkSize;
    final int totalChunks;
    final AtomicInteger chunksSent = new AtomicInteger();

    private final BitSet acked = new BitSet();
    private TransferState state = TransferState.OFFERING;
    private String error = "";
    private boolean accepted;
    private boolean alreadyHave;
    private boolean finished;
    private boolean finishedOk;
    private String finishReason = "";
    private long lastProgressNanos = System.nanoTime();
    private Thread thread;

    OutgoingTransfer(UUID transferId, String to, FileMetadata metadata, Path staging, int chunkSize) {
        this.transferId = transferId;
        this.to = to;
        this.metadata = metadata;
        this.staging = staging;
        this.chunkSize = chunkSize;
        this.totalChunks = (int) ((metadata.size() + chunkSize - 1) / chunkSize);
    }

    int chunkLength(int index) {
        long offset = (long) index * chunkSize;
        return (int) Math.min(chunkSize, metadata.size() - offset);
    }

    // ---- updates from message handlers (link reader threads) ----

    synchronized void onAccept(TransferCodec.Accept accept) {
        if (accept.code() == TransferCodec.REJECTED) {
            fail("rejected by " + to + ": " + accept.reason());
        } else {
            accepted = true;
            alreadyHave = accept.code() == TransferCodec.ALREADY_HAVE;
            for (int i = accept.received().nextSetBit(0); i >= 0 && i < totalChunks;
                 i = accept.received().nextSetBit(i + 1)) {
                acked.set(i);
            }
            if (state == TransferState.OFFERING) {
                state = TransferState.SENDING;
            }
            progress();
        }
        notifyAll();
    }

    synchronized void onAck(int index) {
        if (index >= 0 && index < totalChunks && !acked.get(index)) {
            acked.set(index);
            progress();
        }
        notifyAll();
    }

    synchronized void onDone(boolean ok, String reason) {
        finished = true;
        finishedOk = ok;
        finishReason = reason;
        progress();
        notifyAll();
    }

    // ---- used by the sending thread ----

    synchronized void progress() {
        lastProgressNanos = System.nanoTime();
    }

    synchronized boolean isAccepted() {
        return accepted;
    }

    synchronized boolean isAlreadyHave() {
        return alreadyHave;
    }

    synchronized boolean isFinished() {
        return finished;
    }

    synchronized boolean finishedOk() {
        return finishedOk;
    }

    synchronized String finishReason() {
        return finishReason;
    }

    synchronized boolean allAcked() {
        return acked.cardinality() >= totalChunks;
    }

    synchronized int ackedCount() {
        return Math.min(acked.cardinality(), totalChunks);
    }

    synchronized boolean isAcked(int index) {
        return acked.get(index);
    }

    /** Index of the first unacknowledged chunk at or after {@code from}, or {@code totalChunks}. */
    synchronized int nextUnacked(int from) {
        int next = acked.nextClearBit(from);
        return Math.min(next, totalChunks);
    }

    synchronized long millisSinceProgress() {
        return (System.nanoTime() - lastProgressNanos) / 1_000_000;
    }

    synchronized void awaitChange(long millis) throws InterruptedException {
        wait(millis);
    }

    synchronized void markDelivered() {
        state = TransferState.DELIVERED;
        error = "";
        notifyAll();
    }

    synchronized void fail(String reason) {
        if (state != TransferState.DELIVERED) {
            state = TransferState.FAILED;
            error = reason;
        }
        notifyAll();
    }

    synchronized void restart() {
        state = TransferState.OFFERING;
        error = "";
        accepted = false;
        alreadyHave = false;
        finished = false;
        finishedOk = false;
        finishReason = "";
        progress();
    }

    synchronized TransferState state() {
        return state;
    }

    synchronized void setThread(Thread thread) {
        this.thread = thread;
    }

    synchronized Thread thread() {
        return thread;
    }

    synchronized TransferStatus status(long stalledAfterMillis) {
        int done = Math.min(acked.cardinality(), totalChunks);
        int percent = totalChunks == 0 ? (state == TransferState.DELIVERED ? 100 : 0)
                : (int) (100L * done / totalChunks);
        if (state == TransferState.DELIVERED) {
            percent = 100;
        }
        boolean stalled = (state == TransferState.OFFERING || state == TransferState.SENDING)
                && (System.nanoTime() - lastProgressNanos) / 1_000_000 > stalledAfterMillis;
        return new TransferStatus(transferId, metadata.fileId(), metadata.fileName(), to, state, metadata.size(),
                totalChunks, done, percent, stalled, chunksSent.get(), error);
    }
}
