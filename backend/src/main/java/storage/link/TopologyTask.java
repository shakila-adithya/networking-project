package storage.link;

/** Task for the topology thread: evaluate, then sleep until the next interval or until woken. */
final class TopologyTask implements Runnable {
    private final TopologyController controller;

    TopologyTask(TopologyController controller) {
        this.controller = controller;
    }

    @Override
    public void run() {
        while (controller.isRunning()) {
            try {
                controller.tick();
            } catch (RuntimeException e) {
                System.err.println("Topology evaluation failed: " + e);
            }
            try {
                controller.awaitNextTick();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
