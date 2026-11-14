package storage.routing;

/**
 * Cost of using the direct link to a neighbour; lower is better. Routes are chosen by the smallest
 * total cost, so this is where link quality (loss, delay, signal strength) steers traffic away from
 * weak links. The default counts hops.
 */
@FunctionalInterface
public interface LinkCost {
    int cost(String neighbourId);

    LinkCost HOP_COUNT = neighbourId -> 1;
}
