package storage.discovery;

/** Task for the UDP discovery thread: receives multicast datagrams until the manager stops. */
final class DiscoveryReceiver implements Runnable {
    private final DiscoveryManager manager;

    DiscoveryReceiver(DiscoveryManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        manager.receiveLoop();
    }
}
