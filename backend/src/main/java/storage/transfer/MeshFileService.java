package storage.transfer;

import storage.files.FileMetadata;
import storage.files.FileStore;
import storage.files.HashUtils;
import storage.messaging.MeshNode;
import storage.messaging.Message;
import storage.messaging.MessageType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * File sharing over the mesh. A file travels as acknowledged chunks along routes found by the router,
 * so recipients need not be directly connected. Per transfer:
 * <ol>
 *   <li>OFFER (metadata, chunk size) -> ACCEPT (which chunks the receiver already has);</li>
 *   <li>CHUNK / CHUNK_ACK with a sliding window and timed retransmits;</li>
 *   <li>the receiver checks SHA-256, commits the file into its {@link FileStore} and sends DONE.</li>
 * </ol>
 * A transfer that stalls (no route for a while) ends FAILED but keeps its staged file, and
 * {@link #resume} continues from the chunks the receiver already holds.
 */
public final class MeshFileService implements AutoCloseable {
    private static final int MAX_CONCURRENT_INCOMING = 8;
    private static final int MAX_CHUNKS = 65_536;
    private static final long INCOMING_IDLE_EXPIRY_MILLIS = 24 * 60 * 60_000L;
    private static final int HISTORY_LIMIT = 200;
    private static final int COMPLETED_MEMORY = 200;

    private final MeshNode node;
    private final FileStore store;
    private final TransferConfig config;
    private final Path outgoingDirectory;
    private final Path incomingDirectory;
    private final Map<UUID, OutgoingTransfer> outgoing = new ConcurrentHashMap<>();
    private final Map<UUID, IncomingTransfer> incoming = new LinkedHashMap<>();
    private volatile Consumer<ReceivedFile> receivedListener = file -> { };
    private volatile boolean closed;
    private final java.util.concurrent.ScheduledExecutorService retryTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "file-contact-retry"); t.setDaemon(true); return t;
    });
    private final class RetryTask implements Runnable { public void run() { if (!node.neighbourIds().isEmpty()) resumePending(); } }

    public MeshFileService(MeshNode node, FileStore store, TransferConfig config) throws IOException {
        this.node = node;
        this.store = store;
        this.config = config;
        Path work = store.root().resolve("mesh-transfers");
        this.outgoingDirectory = work.resolve("outgoing");
        this.incomingDirectory = work.resolve("incoming");
        Files.createDirectories(outgoingDirectory);
        Files.createDirectories(incomingDirectory);
        // Journals, staging and bitmaps survive restarts. Never erase unacknowledged source bytes.
        restoreTransfers();
        node.setHandler(MessageType.FILE_OFFER, this::onOffer);
        node.setHandler(MessageType.FILE_ACCEPT, this::onAccept);
        node.setHandler(MessageType.FILE_CHUNK, this::onChunk);
        node.setHandler(MessageType.FILE_CHUNK_ACK, this::onAck);
        node.setHandler(MessageType.FILE_DONE, this::onDone);
        node.onNeighbourChange(peer -> resumePending(), peer -> { });
        retryTimer.scheduleWithFixedDelay(new RetryTask(), 10, 30, java.util.concurrent.TimeUnit.SECONDS);
    }
    private record OutgoingJournal(UUID transferId, String to, FileMetadata metadata, int chunkSize) { }
    private Path journal(OutgoingTransfer t) { return outgoingDirectory.resolve(t.transferId + ".json"); }
    private void save(OutgoingTransfer t) throws IOException { storage.files.AtomicJson.save(journal(t), new OutgoingJournal(t.transferId, t.to, t.metadata, t.chunkSize)); }
    private void restoreTransfers() throws IOException {
        try (var list = Files.list(outgoingDirectory)) {
            for (Path path : list.filter(p -> p.getFileName().toString().endsWith(".json")).limit(HISTORY_LIMIT).toList()) {
                OutgoingJournal j = storage.files.AtomicJson.read(path, OutgoingJournal.class);
                if (!path.getFileName().toString().equals(j.transferId() + ".json") || j.chunkSize() < 1024 || j.chunkSize() > TransferConfig.MAX_CHUNK_SIZE) throw new IOException("Invalid outgoing transfer journal");
                Path bytes = outgoingDirectory.resolve(j.transferId() + ".bin");
                if (!Files.isRegularFile(bytes) || Files.size(bytes) != j.metadata().size()) throw new IOException("Pending source bytes are missing or changed: " + j.transferId());
                var t = new OutgoingTransfer(j.transferId(), j.to(), j.metadata(), bytes, j.chunkSize());
                t.fail("paused after restart; waiting for a connection"); outgoing.put(j.transferId(), t);
            }
        }
        try (var list = Files.list(incomingDirectory)) {
            for (Path path : list.filter(p -> p.getFileName().toString().endsWith(".json")).limit(MAX_CONCURRENT_INCOMING).toList()) {
                var j = storage.files.AtomicJson.read(path, IncomingTransfer.Journal.class);
                if (!path.getFileName().toString().equals(j.transferId() + ".json") || j.chunkSize() < 1024 || j.chunkSize() > TransferConfig.MAX_CHUNK_SIZE) throw new IOException("Invalid incoming transfer journal");
                Path bytes = incomingDirectory.resolve(j.transferId() + ".part");
                if (!Files.isRegularFile(bytes)) continue;
                var t = new IncomingTransfer(j.transferId(), j.from(), j.metadata(), j.chunkSize(), bytes); t.restore(j.received()); incoming.put(j.transferId(), t);
            }
        }
    }
    public void resumePending() {
        if (closed) return;
        int budget = 8;
        for (OutgoingTransfer t : outgoing.values()) {
            String reason = t.status(1000).error();
            if ((t.state() == TransferState.WAITING || t.state() == TransferState.FAILED)
                    && (reason.contains("route unavailable") || reason.startsWith("paused after restart"))) {
                if (budget-- <= 0) break;
                resume(t.transferId);
            }
        }
    }

    public void onReceived(Consumer<ReceivedFile> listener) {
        this.receivedListener = listener;
    }

    // ================= sending =================

    /**
     * Starts sending {@code source} to a device. The content is copied to a private staging file, so the
     * caller may delete {@code source} immediately. Returns at once; follow progress with {@link #status}.
     */
    public TransferStatus send(String to, FileMetadata metadata, Path source) throws IOException {
        if (closed) {
            throw new IllegalStateException("file service is closed");
        }
        if (to == null || !to.matches("[A-Za-z0-9_-]{1,40}") || to.equals(node.localId())) {
            throw new IllegalArgumentException("invalid recipient");
        }
        if (metadata.recipients() != null && !metadata.recipients().contains(to)) {
            throw new IllegalArgumentException("recipient is not in the file's recipient list");
        }
        if (Files.size(source) != metadata.size()) {
            throw new IllegalArgumentException("file size does not match its metadata");
        }
        trimHistory();
        long pendingBytes = outgoing.values().stream().filter(t -> t.state() != TransferState.DELIVERED).mapToLong(t -> t.metadata.size()).sum();
        if (outgoing.size() >= HISTORY_LIMIT || metadata.size() > 4L * 1024 * 1024 * 1024 - pendingBytes) throw new IOException("Pending-file storage limit reached. Existing pending files were kept.");
        UUID id = UUID.randomUUID();
        Path staging = outgoingDirectory.resolve(id + ".bin");
        Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
        OutgoingTransfer transfer = new OutgoingTransfer(id, to, metadata, staging, config.chunkSize());
        if (transfer.totalChunks > MAX_CHUNKS) {
            Files.deleteIfExists(staging);
            throw new IllegalArgumentException("file needs too many chunks; use a larger chunk size");
        }
        save(transfer);
        outgoing.put(id, transfer);
        trimHistory();
        start(transfer);
        return transfer.status(stallDisplayMillis());
    }

    /** Continues a FAILED transfer from the chunks the receiver already holds. Returns false if impossible. */
    public boolean resume(UUID transferId) {
        OutgoingTransfer transfer = outgoing.get(transferId);
        if (closed || transfer == null || transfer.state() != TransferState.FAILED && transfer.state() != TransferState.WAITING
                || !Files.isRegularFile(transfer.staging)) {
            return false;
        }
        Thread old = transfer.thread();
        if (old != null && old.isAlive()) {
            return false;
        }
        transfer.restart();
        start(transfer);
        return true;
    }

    private synchronized void start(OutgoingTransfer transfer) {
        if (closed) return;
        long active = outgoing.values().stream().filter(t -> t.thread() != null && t.thread().isAlive()).count();
        if (active >= 8) { transfer.pause("paused after restart; waiting for a connection or worker slot"); return; }
        Thread worker = new Thread(new TransferTask(this, transfer, config), "file-send-" + transfer.transferId);
        worker.setDaemon(true);
        transfer.setThread(worker);
        worker.start();
    }

    public Optional<TransferStatus> status(UUID transferId) {
        OutgoingTransfer transfer = outgoing.get(transferId);
        return transfer == null ? Optional.empty() : Optional.of(transfer.status(stallDisplayMillis()));
    }

    public List<TransferStatus> transfers() {
        List<TransferStatus> result = new ArrayList<>();
        for (OutgoingTransfer transfer : outgoing.values()) {
            result.add(transfer.status(stallDisplayMillis()));
        }
        result.sort(Comparator.comparing(TransferStatus::fileName).thenComparing(TransferStatus::transferId));
        return result;
    }

    /** Receiver progress is separate from sender delivery acknowledgements and never exposes partial content. */
    public record IncomingStatus(UUID transferId, UUID fileId, String fileName, String from,
                                 long size, int percent, String state, String error) { }
    public List<IncomingStatus> incomingTransfers() {
        synchronized (incoming) {
            List<IncomingStatus> result = new ArrayList<>();
            for (IncomingTransfer transfer : incoming.values()) {
                synchronized (transfer) {
                    int count = transfer.receivedChunks().cardinality();
                    int percent = transfer.totalChunks == 0 ? 100 : (int)((long)count * 100 / transfer.totalChunks);
                    String state = transfer.isCompleted() ? (transfer.completedOk() ? "RECEIVED" : "FAILED")
                            : transfer.idleMillis() > stallDisplayMillis() ? "WAITING" : "RECEIVING";
                    result.add(new IncomingStatus(transfer.transferId, transfer.metadata.fileId(), transfer.metadata.fileName(),
                            transfer.from, transfer.metadata.size(), percent, state, transfer.completedReason()));
                }
            }
            return result;
        }
    }

    /** A transfer is shown as "waiting for a route" after a few unanswered chunk timeouts. */
    boolean durable() { return node.durable(); }
    private long stallDisplayMillis() {
        return Math.max(1_000, config.chunkTimeout().toMillis() * 2);
    }

    void transferEnded(OutgoingTransfer transfer) {
        if (transfer.state() == TransferState.DELIVERED) {
            deleteQuietly(journal(transfer));
            if (node.custody() != null) node.custody().completedTransfer(transfer.transferId, transfer.to);
            deleteQuietly(transfer.staging);
        }
    }

    private void trimHistory() {
        if (outgoing.size() < HISTORY_LIMIT) {
            return;
        }
        for (OutgoingTransfer transfer : new ArrayList<>(outgoing.values())) {
            if (outgoing.size() < HISTORY_LIMIT) {
                return;
            }
            if (transfer.state() == TransferState.DELIVERED) {
                outgoing.remove(transfer.transferId);
                deleteQuietly(transfer.staging);
            }
        }
    }

    void sendOffer(OutgoingTransfer transfer) {
        try {
            node.originate(Message.create(node.localId(), transfer.to, MessageType.FILE_OFFER,
                    TransferCodec.encode(new TransferCodec.Offer(transfer.transferId, transfer.chunkSize,
                            transfer.metadata))));
        } catch (IOException e) {
            transfer.fail("cannot encode offer: " + e.getMessage());
        }
    }

    void sendChunk(OutgoingTransfer transfer, int index, byte[] data) {
        try {
            node.originate(Message.create(node.localId(), transfer.to, MessageType.FILE_CHUNK,
                    TransferCodec.encode(new TransferCodec.Chunk(transfer.transferId, index, data))));
        } catch (IOException e) {
            transfer.fail("cannot encode chunk: " + e.getMessage());
        }
    }

    private OutgoingTransfer outgoingFor(Message message, UUID transferId) {
        OutgoingTransfer transfer = outgoing.get(transferId);
        return transfer != null && transfer.to.equals(message.source()) && !message.isBroadcast() ? transfer : null;
    }

    private void onAccept(Message message) {
        try {
            TransferCodec.Accept accept = TransferCodec.decodeAccept(message.payload());
            OutgoingTransfer transfer = outgoingFor(message, accept.transferId());
            if (transfer != null) {
                transfer.onAccept(accept);
            }
        } catch (IOException e) {
            System.err.println("Dropped malformed FILE_ACCEPT: " + e.getMessage());
        }
    }

    private void onAck(Message message) {
        try {
            TransferCodec.Ack ack = TransferCodec.decodeAck(message.payload());
            OutgoingTransfer transfer = outgoingFor(message, ack.transferId());
            if (transfer != null) {
                transfer.onAck(ack.index());
            }
        } catch (IOException e) {
            System.err.println("Dropped malformed FILE_CHUNK_ACK: " + e.getMessage());
        }
    }

    private void onDone(Message message) {
        try {
            TransferCodec.Done done = TransferCodec.decodeDone(message.payload());
            OutgoingTransfer transfer = outgoingFor(message, done.transferId());
            if (transfer != null) {
                transfer.onDone(done.ok(), done.reason());
                if (done.ok()) { transfer.markDelivered(); transferEnded(transfer); }
            }
        } catch (IOException e) {
            System.err.println("Dropped malformed FILE_DONE: " + e.getMessage());
        }
    }

    // ================= receiving =================

    private void reply(String to, MessageType type, byte[] payload) {
        node.originate(Message.create(node.localId(), to, type, payload));
    }

    private void replyAccept(String to, TransferCodec.Accept accept) {
        try {
            reply(to, MessageType.FILE_ACCEPT, TransferCodec.encode(accept));
        } catch (IOException e) {
            System.err.println("Could not encode FILE_ACCEPT: " + e.getMessage());
        }
    }

    private void replyDone(String to, UUID transferId, boolean ok, String reason) {
        try {
            reply(to, MessageType.FILE_DONE, TransferCodec.encode(new TransferCodec.Done(transferId, ok, reason)));
        } catch (IOException e) {
            System.err.println("Could not encode FILE_DONE: " + e.getMessage());
        }
    }

    private void onOffer(Message message) {
        if (message.isBroadcast() || closed) {
            return;
        }
        TransferCodec.Offer offer;
        try {
            offer = TransferCodec.decodeOffer(message.payload());
        } catch (IOException e) {
            System.err.println("Dropped malformed FILE_OFFER: " + e.getMessage());
            return;
        }
        String from = message.source();
        UUID id = offer.transferId();
        FileMetadata metadata = offer.metadata();
        BitSet nothing = new BitSet();

        if (metadata.recipients() != null && !metadata.recipients().contains(node.localId())) {
            replyAccept(from, new TransferCodec.Accept(id, TransferCodec.REJECTED,
                    "this file is not addressed to " + node.localId(), nothing));
            return;
        }
        if (offer.chunkSize() < 1024 || offer.chunkSize() > TransferConfig.MAX_CHUNK_SIZE
                || (metadata.size() + offer.chunkSize() - 1) / offer.chunkSize() > MAX_CHUNKS) {
            replyAccept(from, new TransferCodec.Accept(id, TransferCodec.REJECTED, "unsupported chunk size", nothing));
            return;
        }

        IncomingTransfer transfer;
        synchronized (incoming) {
            reapIdle();
            transfer = incoming.get(id);
            if (transfer != null && !transfer.from.equals(from)) {
                replyAccept(from, new TransferCodec.Accept(id, TransferCodec.REJECTED, "transfer id in use", nothing));
                return;
            }
            if (transfer == null) {
                if (alreadyStored(metadata)) {
                    BitSet all = new BitSet();
                    all.set(0, (int) ((metadata.size() + offer.chunkSize() - 1) / offer.chunkSize()));
                    replyAccept(from, new TransferCodec.Accept(id, TransferCodec.ALREADY_HAVE, "", all));
                    replyDone(from, id, true, "already stored");
                    return;
                }
                if (activeIncomingCount() >= MAX_CONCURRENT_INCOMING) {
                    replyAccept(from, new TransferCodec.Accept(id, TransferCodec.REJECTED,
                            "too many incoming transfers; try again later", nothing));
                    return;
                }
                try {
                    transfer = new IncomingTransfer(id, from, metadata, offer.chunkSize(),
                            incomingDirectory.resolve(id + ".part"));
                    transfer.save();
                } catch (IOException e) {
                    replyAccept(from, new TransferCodec.Accept(id, TransferCodec.REJECTED,
                            "cannot create the partial file: " + e.getMessage(), nothing));
                    return;
                }
                incoming.put(id, transfer);
            }
            transfer.touch();
        }
        replyAccept(from, new TransferCodec.Accept(id, TransferCodec.ACCEPTED, "", transfer.receivedChunks()));
        if (transfer.isCompleted()) {
            replyDone(from, id, transfer.completedOk(), transfer.completedReason()); // sender missed our result
        } else if (transfer.allReceived() && transfer.beginFinish()) {
            launchFinish(transfer); // also covers empty files
        }
    }

    private void onChunk(Message message) {
        if (message.isBroadcast() || closed) {
            return;
        }
        TransferCodec.Chunk chunk;
        try {
            chunk = TransferCodec.decodeChunk(message.payload());
        } catch (IOException e) {
            System.err.println("Dropped malformed FILE_CHUNK: " + e.getMessage());
            return;
        }
        IncomingTransfer transfer;
        synchronized (incoming) {
            transfer = incoming.get(chunk.transferId());
        }
        if (transfer == null || !transfer.from.equals(message.source())) {
            if (node.durable()) throw new IllegalStateException("Waiting for the file's offer metadata");
            return; // unknown transfer: the sender's offer retries will establish it
        }
        try {
            if (!transfer.writeChunk(chunk.index(), chunk.data())) {
                return;
            }
        } catch (IOException e) {
            System.err.println("Could not write chunk " + chunk.index() + ": " + e.getMessage());
            if (node.durable()) throw new java.io.UncheckedIOException(e);
            return;
        }
        try {
            reply(transfer.from, MessageType.FILE_CHUNK_ACK,
                    TransferCodec.encode(new TransferCodec.Ack(transfer.transferId, chunk.index())));
        } catch (IOException e) {
            System.err.println("Could not encode FILE_CHUNK_ACK: " + e.getMessage());
        }
        if (transfer.isCompleted()) {
            replyDone(transfer.from, transfer.transferId, transfer.completedOk(), transfer.completedReason());
        } else if (transfer.allReceived() && transfer.beginFinish()) {
            launchFinish(transfer);
        }
    }

    private void launchFinish(IncomingTransfer transfer) {
        try {
            Thread worker = new Thread(new FinishTask(this, transfer), "file-finish-" + transfer.transferId);
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | Error failure) {
            transfer.complete(false, "internal error");
            throw failure;
        }
    }

    /** Verifies the SHA-256 of the assembled file, commits it into the store and tells the sender. */
    void finishIncoming(IncomingTransfer transfer) {
        boolean ok = false;
        String reason;
        try {
            transfer.closeFile();
            String actual = HashUtils.sha256(transfer.part);
            if (!actual.equals(transfer.metadata.sha256())) {
                reason = "SHA-256 mismatch after assembling the file";
            } else {
                reason = store(transfer.metadata, transfer.part);
                ok = reason.isEmpty();
            }
        } catch (IOException e) {
            reason = "could not verify the file: " + e.getMessage();
        }
        deleteQuietly(transfer.part);
        deleteQuietly(incomingDirectory.resolve(transfer.transferId + ".json"));
        transfer.complete(ok, reason);
        synchronized (incoming) {
            trimCompleted();
        }
        if (ok) {
            try {
                receivedListener.accept(new ReceivedFile(transfer.metadata, transfer.from));
            } catch (RuntimeException e) {
                System.err.println("File listener failed: " + e);
            }
        }
        replyDone(transfer.from, transfer.transferId, ok, ok ? "stored" : reason);
    }

    /** Commits the file into the store. Returns "" on success or a reason. */
    private String store(FileMetadata metadata, Path content) throws IOException {
        try (FileStore.UploadSession session = store.reserveUpload(metadata);
             InputStream input = Files.newInputStream(content)) {
            session.receive(input);
            return "";
        } catch (FileStore.StorageException e) {
            if ("DUPLICATE_FILE_ID".equals(e.code()) && alreadyStored(metadata)) {
                return "";
            }
            return e.code() + ": " + e.getMessage();
        }
    }

    private boolean alreadyStored(FileMetadata metadata) {
        try {
            return store.open(metadata.fileId()).metadata().sha256().equals(metadata.sha256());
        } catch (IOException | FileStore.StorageException e) {
            return false;
        }
    }

    private int activeIncomingCount() {
        int count = 0;
        for (IncomingTransfer transfer : incoming.values()) {
            if (!transfer.isCompleted()) {
                count++;
            }
        }
        return count;
    }

    /** Drops partial transfers that have been idle for too long. Caller holds the incoming lock. */
    private void reapIdle() {
        incoming.values().removeIf(transfer -> {
            if (!transfer.isCompleted() && transfer.idleMillis() > INCOMING_IDLE_EXPIRY_MILLIS) {
                transfer.closeFile();
                deleteQuietly(transfer.part);
                deleteQuietly(incomingDirectory.resolve(transfer.transferId + ".json"));
                System.err.println("Partial received transfer expired: " + transfer.transferId + "; sender retains original.");
                return true;
            }
            return false;
        });
    }

    private void trimCompleted() {
        long completed = incoming.values().stream().filter(IncomingTransfer::isCompleted).count();
        if (completed <= COMPLETED_MEMORY) {
            return;
        }
        var iterator = incoming.values().iterator();
        while (iterator.hasNext() && completed > COMPLETED_MEMORY) {
            if (iterator.next().isCompleted()) {
                iterator.remove();
                completed--;
            }
        }
    }

    // ================= housekeeping =================

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("Could not delete " + path + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        retryTimer.shutdownNow();
        for (OutgoingTransfer transfer : outgoing.values()) {
            Thread worker = transfer.thread();
            if (worker != null) {
                worker.interrupt();
            }
        }
        synchronized (incoming) {
            for (IncomingTransfer transfer : incoming.values()) {
                transfer.closeFile();
            }
            incoming.clear();
        }
    }
}
