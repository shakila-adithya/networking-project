package storage.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RoutingTableTest {
    @Test
    void newerSequenceWinsEvenIfCostlier() {
        RoutingTable table = new RoutingTable(60_000);
        assertTrue(table.update("D", "B", 5, 2, 2));
        assertTrue(table.update("D", "C", 6, 9, 4));
        assertEquals("C", table.get("D").nextHop());
    }

    @Test
    void sameSequenceNeedsStrictlyLowerCost() {
        RoutingTable table = new RoutingTable(60_000);
        table.update("D", "B", 5, 4, 2);
        assertFalse(table.update("D", "C", 5, 4, 2));
        assertFalse(table.update("D", "C", 5, 7, 1));
        assertTrue(table.update("D", "C", 5, 3, 3));
        assertEquals("C", table.get("D").nextHop());
    }

    @Test
    void olderSequenceIsIgnored() {
        RoutingTable table = new RoutingTable(60_000);
        table.update("D", "B", 5, 9, 2);
        assertFalse(table.update("D", "C", 4, 1, 1));
    }

    @Test
    void invalidatingViaLostNeighbourBumpsSequenceAndReportsPrecursors() {
        RoutingTable table = new RoutingTable(60_000);
        table.update("D", "B", 5, 2, 2);
        table.update("E", "C", 1, 1, 1);
        table.addPrecursor("D", "A");
        List<RoutingTable.Invalidated> result = table.invalidateVia("B");
        assertEquals(1, result.size());
        assertEquals("D", result.get(0).destination());
        assertEquals(6, result.get(0).sequence());
        assertEquals(java.util.Set.of("A"), result.get(0).precursors());
        assertNull(table.get("D"));
        assertNotNull(table.get("E"));
        assertEquals(6, table.lastKnownSequence("D"));
        assertFalse(table.update("D", "C", 5, 1, 1), "a reply older than the invalidation is stale");
        assertTrue(table.update("D", "C", 7, 5, 3));
    }

    @Test
    void routeErrorOnlyAppliesToRoutesThroughThatNeighbour() {
        RoutingTable table = new RoutingTable(60_000);
        table.update("D", "B", 5, 2, 2);
        assertNull(table.invalidate("D", 5, "C"));
        assertNotNull(table.invalidate("D", 5, "B"));
        assertNull(table.get("D"));
    }

    @Test
    void routesExpireUnlessTouched() {
        AtomicLong now = new AtomicLong(1000);
        RoutingTable table = new RoutingTable(100, now::get);
        table.update("D", "B", 1, 1, 1);
        now.addAndGet(60);
        table.touch("D");
        now.addAndGet(60);
        assertNotNull(table.get("D"));
        now.addAndGet(200);
        assertNull(table.get("D"));
        assertTrue(table.update("D", "C", 1, 5, 2), "expired route can be replaced at the same sequence");
    }

    @Test
    void routesToDepartedDevicesAreForgottenAfterRetention() {
        AtomicLong now = new AtomicLong(1000);
        RoutingTable table = new RoutingTable(100, now::get);
        table.update("GONE", "B", 4, 1, 1);
        table.update("IDLE", "B", 2, 1, 1);
        table.invalidateVia("B");                      // both become invalid at t=1000 ...
        table.update("LIVE", "C", 1, 1, 1);
        now.addAndGet(50);
        assertEquals(3, table.snapshot().size(), "kept briefly so stale replies are still rejected");
        assertFalse(table.update("GONE", "C", 4, 1, 1), "stale reply (same sequence as before the loss)");
        now.addAndGet(60);                              // t=1110: invalid for >= 100 ms
        List<RoutingTable.Route> routes = table.snapshot();
        assertEquals(1, routes.size());
        assertEquals("LIVE", routes.get(0).destination());
        assertEquals(-1, table.lastKnownSequence("GONE"));
    }
}
