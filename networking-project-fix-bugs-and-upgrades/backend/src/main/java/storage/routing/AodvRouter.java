package storage.routing;

import storage.messaging.Forwarder;
import storage.messaging.MeshNode;
import storage.messaging.Message;
import storage.messaging.MessageType;
import storage.routing.RoutingCodec.RouteReply;
import storage.routing.RoutingCodec.RouteRequest;
import storage.routing.RoutingCodec.Unreachable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-demand distance-vector routing (AODV) with a pluggable link-cost metric, in the style of HWMP.
 *
 * <ul>
 *   <li>No route to a unicast destination: buffer the message and flood a route request (RREQ).
 *       Every relay records a reverse route and re-floods only when it learns a strictly cheaper path.</li>
 *   <li>The destination answers with a route reply (RREP) along the reverse path; each hop installs the
 *       forward route. Sequence numbers keep routes loop-free and stop stale replies.</li>
 *   <li>A lost neighbour invalidates routes through it and sends a route error (RERR) to the neighbours
 *       that used them. A relay that receives data for a broken route first tries to repair it locally
 *       by discovering a new path, and only reports an error if that fails.</li>
 *   <li>Broadcasts are still flooded (bounded by TTL and the node's duplicate cache).</li>
 * </ul>
 * Only the destination replies, so a stale cached route can never answer for it.
 */
public final class AodvRouter implements Forwarder {
    private static final int MAX_BUFFERED_PER_DESTINATION = 64;
    private static final int MAX_REMEMBERED_REQUESTS = 2048;

    private final RoutingTable table;
    private final LinkCost linkCost;
    private final Duration discoveryTimeout;
    private final int maxDiscoveryAttempts;
    private final AtomicInteger requestsSent = new AtomicInteger();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "aodv-discovery-timer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile MeshNode node;
    private volatile String self = "?";
    private long ownSequence;
    private long nextRequestId;
    private final Map<String, RequestState> requests = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RequestState> eldest) {
            return size() > MAX_REMEMBERED_REQUESTS;
        }
    };
    private final Map<String, Discovery> pending = new HashMap<>();

    private static final class RequestState {
        int bestCost;
        long replySequence = -1;

        RequestState(int bestCost) {
            this.bestCost = bestCost;
        }
    }

    private record Buffered(Message message, String from) { }

    private static final class Discovery {
        int attempts;
        final List<Buffered> queue = new ArrayList<>();
        ScheduledFuture<?> timeout;
    }

    public AodvRouter(LinkCost linkCost, Duration discoveryTimeout, int maxDiscoveryAttempts, Duration routeLifetime) {
        if (maxDiscoveryAttempts < 1) {
            throw new IllegalArgumentException("maxDiscoveryAttempts must be at least 1");
        }
        this.linkCost = linkCost;
        this.discoveryTimeout = discoveryTimeout;
        this.maxDiscoveryAttempts = maxDiscoveryAttempts;
        this.table = new RoutingTable(routeLifetime.toMillis());
    }

    @Override
    public void attach(MeshNode node) {
        this.node = node;
        this.self = node.localId();
    }

    /** Snapshot of the routing table, for display and tests. */
    public List<RoutingTable.Route> routes() {
        return table.snapshot();
    }

    /** Number of route requests this device has originated. */
    public int requestsSent() {
        return requestsSent.get();
    }

    // ---- forwarding ----

    @Override
    public Set<String> nextHops(Message message, String from, Set<String> neighbours) {
        if (message.isBroadcast()) {
            Set<String> hops = new HashSet<>(neighbours);
            hops.remove(from);
            return hops;
        }
        String destination = message.destination();
        RoutingTable.Route route = table.get(destination);
        if (route != null && neighbours.contains(route.nextHop()) && !route.nextHop().equals(from)) {
            table.touch(destination);
            return Set.of(route.nextHop());
        }
        synchronized (this) {
            Discovery discovery = pending.computeIfAbsent(destination, id -> new Discovery());
            if (discovery.queue.size() < MAX_BUFFERED_PER_DESTINATION) {
                discovery.queue.add(new Buffered(message, from));
            }
            if (discovery.attempts == 0) {
                startAttempt(destination, discovery);
            }
        }
        return Set.of();
    }

    private void startAttempt(String destination, Discovery discovery) {
        discovery.attempts++;
        ownSequence++;
        long id = nextRequestId++;
        RouteRequest request = new RouteRequest(id, ownSequence, destination,
                table.lastKnownSequence(destination), 0, 0);
        requests.put(self + "#" + id, new RequestState(0));
        try {
            Message message = Message.create(self, Message.BROADCAST, MessageType.RREQ, RoutingCodec.encode(request));
            requestsSent.incrementAndGet();
            for (String neighbour : node.neighbourIds()) {
                node.sendTo(neighbour, message);
            }
        } catch (IOException e) {
            System.err.println("Could not encode route request: " + e.getMessage());
        }
        discovery.timeout = timer.schedule(() -> onDiscoveryTimeout(destination),
                discoveryTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void onDiscoveryTimeout(String destination) {
        Discovery discovery = pending.get(destination);
        if (discovery == null) {
            return;
        }
        if (table.get(destination) != null) {
            flush(destination);
        } else if (discovery.attempts < maxDiscoveryAttempts) {
            startAttempt(destination, discovery);
        } else {
            pending.remove(destination);
            // Messages that originated here are simply dropped (the sender's own retries/status report it).
            // Messages we were relaying get an error so the upstream node stops using this path.
            Map<String, List<Unreachable>> errors = new HashMap<>();
            long sequence = Math.max(table.lastKnownSequence(destination), 0);
            for (Buffered buffered : discovery.queue) {
                if (buffered.from() != null) {
                    errors.computeIfAbsent(buffered.from(), id -> new ArrayList<>())
                            .add(new Unreachable(destination, sequence));
                }
            }
            sendErrors(errors);
        }
    }

    /** Sends buffered messages now that a route exists. Caller holds the router lock. */
    private void flush(String destination) {
        Discovery discovery = pending.remove(destination);
        RoutingTable.Route route = table.get(destination);
        if (discovery == null || route == null) {
            return;
        }
        if (discovery.timeout != null) {
            discovery.timeout.cancel(false);
        }
        for (Buffered buffered : discovery.queue) {
            node.sendTo(route.nextHop(), buffered.message());
        }
    }

    // ---- control messages ----

    @Override
    public boolean handleControl(Message message, String from) {
        try {
            switch (message.type()) {
                case RREQ -> onRequest(message, from);
                case RREP -> onReply(message, from);
                case RERR -> onError(message, from);
                default -> {
                    return false;
                }
            }
        } catch (IOException e) {
            System.err.println("Dropped malformed " + message.type() + " from " + from + ": " + e.getMessage());
        }
        return true;
    }

    private synchronized void onRequest(Message message, String from) throws IOException {
        RouteRequest request = RoutingCodec.decodeRequest(message.payload());
        String origin = message.source();
        if (origin.equals(self)) {
            return; // our own flood echoed back
        }
        int cost = request.cost() + linkCost.cost(from);
        int hops = request.hops() + 1;
        String key = origin + "#" + request.requestId();
        RequestState state = requests.get(key);
        if (state != null && cost >= state.bestCost) {
            return; // not cheaper than a copy we already handled
        }
        if (state == null) {
            state = new RequestState(cost);
            requests.put(key, state);
        } else {
            state.bestCost = cost;
        }
        table.update(origin, from, request.originSequence(), cost, hops);

        if (request.destination().equals(self)) {
            if (state.replySequence < 0) {
                ownSequence = Math.max(ownSequence, request.destinationSequence()) + 1;
                state.replySequence = ownSequence;
            }
            RoutingTable.Route back = table.get(origin);
            if (back != null) {
                RouteReply reply = new RouteReply(self, state.replySequence, origin, 0, 0);
                node.sendTo(back.nextHop(), Message.create(self, origin, MessageType.RREP, RoutingCodec.encode(reply)));
            }
        } else if (message.ttl() > 1) {
            RouteRequest forwarded = new RouteRequest(request.requestId(), request.originSequence(),
                    request.destination(), request.destinationSequence(), cost, hops);
            Message copy = new Message(message.id(), origin, Message.BROADCAST, MessageType.RREQ,
                    message.ttl() - 1, message.timestampMillis(), RoutingCodec.encode(forwarded));
            for (String neighbour : node.neighbourIds()) {
                if (!neighbour.equals(from)) {
                    node.sendTo(neighbour, copy);
                }
            }
        }
    }

    private synchronized void onReply(Message message, String from) throws IOException {
        RouteReply reply = RoutingCodec.decodeReply(message.payload());
        int cost = reply.cost() + linkCost.cost(from);
        int hops = reply.hops() + 1;
        if (!table.update(reply.destination(), from, reply.destinationSequence(), cost, hops)) {
            return; // stale or not cheaper: do not forward, which also ends any loop
        }
        if (pending.containsKey(reply.destination())) {
            flush(reply.destination());
        }
        if (reply.origin().equals(self) || message.ttl() <= 1) {
            return;
        }
        RoutingTable.Route back = table.get(reply.origin());
        if (back == null) {
            return;
        }
        table.addPrecursor(reply.destination(), back.nextHop());
        table.addPrecursor(reply.origin(), from);
        RouteReply forwarded = new RouteReply(reply.destination(), reply.destinationSequence(), reply.origin(),
                cost, hops);
        node.sendTo(back.nextHop(), new Message(message.id(), message.source(), message.destination(),
                MessageType.RREP, message.ttl() - 1, message.timestampMillis(), RoutingCodec.encode(forwarded)));
    }

    private synchronized void onError(Message message, String from) throws IOException {
        List<RoutingTable.Invalidated> changed = new ArrayList<>();
        for (Unreachable item : RoutingCodec.decodeErrors(message.payload())) {
            RoutingTable.Invalidated invalidated = table.invalidate(item.destination(), item.sequence(), from);
            if (invalidated != null) {
                changed.add(invalidated);
            }
        }
        propagate(changed, from);
    }

    @Override
    public synchronized void neighbourChanged(String neighbourId, boolean up) {
        if (!up) {
            propagate(table.invalidateVia(neighbourId), neighbourId);
        }
    }

    /** Tells every neighbour that relied on the invalidated routes (except the one that told us). */
    private void propagate(List<RoutingTable.Invalidated> changed, String except) {
        Map<String, List<Unreachable>> errors = new HashMap<>();
        for (RoutingTable.Invalidated item : changed) {
            for (String precursor : item.precursors()) {
                if (!precursor.equals(except)) {
                    errors.computeIfAbsent(precursor, id -> new ArrayList<>())
                            .add(new Unreachable(item.destination(), item.sequence()));
                }
            }
        }
        sendErrors(errors);
    }

    private void sendErrors(Map<String, List<Unreachable>> errors) {
        for (Map.Entry<String, List<Unreachable>> entry : errors.entrySet()) {
            List<Unreachable> list = entry.getValue();
            for (int start = 0; start < list.size(); start += RoutingCodec.MAX_ERROR_ENTRIES) {
                List<Unreachable> chunk = list.subList(start,
                        Math.min(list.size(), start + RoutingCodec.MAX_ERROR_ENTRIES));
                try {
                    node.sendTo(entry.getKey(), Message.create(self, entry.getKey(), MessageType.RERR,
                            RoutingCodec.encodeErrors(chunk)));
                } catch (IOException e) {
                    System.err.println("Could not encode route error: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
