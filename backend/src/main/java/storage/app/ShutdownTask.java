package storage.app;

/** Task run by a JVM shutdown hook thread: closes a component on Ctrl+C, SIGTERM or terminal close. */
final class ShutdownTask implements Runnable {
    private final AutoCloseable target;

    ShutdownTask(AutoCloseable target) {
        this.target = target;
    }

    @Override
    public void run() {
        try {
            target.close();
        } catch (Exception e) {
            System.err.println("Shutdown cleanup failed: " + e.getMessage());
        }
    }
}
