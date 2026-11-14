package storage.transfer;

import storage.files.FileMetadata;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.UUID;

/** State of one incoming transfer: a sparse partial file plus the set of chunks received so far. */
final class IncomingTransfer {
    final UUID transferId;
    final String from;
    final FileMetadata metadata;
    final int chunkSize;
    final int totalChunks;
    final Path part;

    record Journal(UUID transferId, String from, FileMetadata metadata, int chunkSize, byte[] received) { }
    private Path journal() { return part.resolveSibling(transferId + ".json"); }
    synchronized void save() throws IOException { storage.files.AtomicJson.save(journal(), new Journal(transferId, from, metadata, chunkSize, received.toByteArray())); }
    synchronized void restore(byte[] bits) throws IOException {
        BitSet saved = BitSet.valueOf(bits);
        if (saved.length() > totalChunks) throw new IOException("Invalid received-chunk journal");
        received.or(saved);
    }
    private final BitSet received = new BitSet();
    private RandomAccessFile file;
    private long lastActivityNanos = System.nanoTime();
    private boolean finishing;
    private boolean completed;
    private boolean completedOk;
    private String completedReason = "";

    IncomingTransfer(UUID transferId, String from, FileMetadata metadata, int chunkSize, Path part) throws IOException {
        this.transferId = transferId;
        this.from = from;
        this.metadata = metadata;
        this.chunkSize = chunkSize;
        this.totalChunks = (int) ((metadata.size() + chunkSize - 1) / chunkSize);
        this.part = part;
        this.file = new RandomAccessFile(part.toFile(), "rw");
        this.file.setLength(metadata.size());
    }

    int expectedLength(int index) {
        long offset = (long) index * chunkSize;
        return (int) Math.min(chunkSize, metadata.size() - offset);
    }

    /** Stores a chunk. Returns false (and ignores it) if it is out of range or has the wrong length. */
    synchronized boolean writeChunk(int index, byte[] data) throws IOException {
        touch();
        if (completed || file == null || index < 0 || index >= totalChunks || data.length != expectedLength(index)) {
            return completed && index >= 0 && index < totalChunks;
        }
        if (!received.get(index)) {
            file.seek((long) index * chunkSize);
            file.write(data);
            file.getChannel().force(true);
            received.set(index);
            try { save(); } catch (IOException e) { received.clear(index); throw e; }
        }
        return true;
    }

    synchronized BitSet receivedChunks() {
        return (BitSet) received.clone();
    }

    synchronized boolean allReceived() {
        return received.cardinality() >= totalChunks;
    }

    /** Returns true exactly once, for the thread that should verify and store the file. */
    synchronized boolean beginFinish() {
        if (finishing || completed) {
            return false;
        }
        finishing = true;
        return true;
    }

    synchronized void complete(boolean ok, String reason) {
        completed = true;
        completedOk = ok;
        completedReason = reason;
        closeFile();
    }

    synchronized boolean isCompleted() {
        return completed;
    }

    synchronized boolean completedOk() {
        return completedOk;
    }

    synchronized String completedReason() {
        return completedReason;
    }

    synchronized void touch() {
        lastActivityNanos = System.nanoTime();
    }

    synchronized long idleMillis() {
        return (System.nanoTime() - lastActivityNanos) / 1_000_000;
    }

    synchronized void closeFile() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                System.err.println("Could not close partial file " + part + ": " + e.getMessage());
            }
            file = null;
        }
    }
}
