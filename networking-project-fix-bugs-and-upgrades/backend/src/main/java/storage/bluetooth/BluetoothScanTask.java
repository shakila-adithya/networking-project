package storage.bluetooth;

/** Task for the Bluetooth scan thread: scan, report Relay devices, wait, repeat. */
final class BluetoothScanTask implements Runnable {
    private final BluetoothScanner scanner;

    BluetoothScanTask(BluetoothScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void run() {
        while (scanner.isRunning()) {
            scanner.scanOnce();
            if (!scanner.pause()) {
                return;
            }
        }
    }
}
