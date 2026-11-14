package storage.transfer;

/** Verifies and stores a completely received file off the link-reader thread (hashing can be slow). */
final class FinishTask implements Runnable {
    private final MeshFileService service;
    private final IncomingTransfer transfer;

    FinishTask(MeshFileService service, IncomingTransfer transfer) {
        this.service = service;
        this.transfer = transfer;
    }

    @Override
    public void run() {
        service.finishIncoming(transfer);
    }
}
