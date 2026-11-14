package storage.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import storage.messaging.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Bounded authenticated adjacency gossip and shortest-known-path queries for custody handoffs and the map. */
public final class MeshTopology implements AutoCloseable {
    private record Advertisement(long sequence, Map<String, Integer> links, Map<String, String> transports) { }
    private record Seen(Advertisement advertisement, long at) { }
    private final MeshNode node;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Seen> graph = new HashMap<>();
    private long sequence = System.currentTimeMillis();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mesh-topology-gossip"); t.setDaemon(true); return t;
    });
    public MeshTopology(MeshNode node) {
        this.node = node;
        node.setHandler(MessageType.TOPOLOGY, this::receive);
        node.onNeighbourChange(id -> publish(), id -> publish());
        node.attachTopology(this);
        timer.scheduleWithFixedDelay(new PublishTask(), 1, 5, TimeUnit.SECONDS);
    }
    private final class PublishTask implements Runnable { public void run() { publish(); } }
    private synchronized void publish() {
        Map<String, Integer> links = new TreeMap<>(); node.neighbourIds().stream().limit(64).forEach(id -> links.put(id, node.routeCost(id)));
        try { node.originate(Message.create(node.localId(), Message.BROADCAST, MessageType.TOPOLOGY, json.writeValueAsBytes(new Advertisement(++sequence, links, node.neighbourKinds())))); }
        catch (IOException | RuntimeException e) { System.err.println("Could not publish topology: " + e.getMessage()); }
    }
    private synchronized void receive(Message message) {
        if (message.source().equals(node.localId()) || message.payload().length > 8192) return;
        try {
            Advertisement a = json.readValue(message.payload(), Advertisement.class);
            if (a.links() == null || a.links().size() > 64 || a.links().entrySet().stream().anyMatch(e -> e.getKey().length() > 64 || e.getValue() == null || e.getValue() < 1 || e.getValue() > 100000)) return;
            Seen previous = graph.get(message.source());
            if (previous != null && previous.advertisement().sequence() >= a.sequence()) return;
            reap(); if (graph.size() >= 256 && !graph.containsKey(message.source())) return;
            graph.put(message.source(), new Seen(a, System.currentTimeMillis()));
        } catch (IOException | RuntimeException invalid) { System.err.println("Dropped malformed topology record"); }
    }
    private void reap() { graph.values().removeIf(s -> System.currentTimeMillis() - s.at() > 20000); }
    public synchronized String nextHop(String destination) {
        reap();
        Map<String, Map<String, Integer>> edges = new HashMap<>(); graph.forEach((id, seen) -> edges.put(id, seen.advertisement().links()));
        Map<String, Integer> local = new HashMap<>(); node.neighbourIds().forEach(id -> local.put(id, node.routeCost(id))); edges.put(node.localId(), local);
        Map<String, Long> cost = new HashMap<>(); Map<String, String> first = new HashMap<>(); Set<String> visited = new HashSet<>();
        cost.put(node.localId(), 0L);
        while (visited.size() <= 256) {
            String current = cost.keySet().stream().filter(id -> !visited.contains(id)).min(Comparator.comparingLong(cost::get)).orElse(null);
            if (current == null) return null;
            if (current.equals(destination)) return first.get(current);
            visited.add(current);
            for (var link : edges.getOrDefault(current, Map.of()).entrySet()) {
                long proposed = cost.get(current) + link.getValue();
                if (proposed < cost.getOrDefault(link.getKey(), Long.MAX_VALUE)) {
                    cost.put(link.getKey(), proposed); first.put(link.getKey(), current.equals(node.localId()) ? link.getKey() : first.get(current));
                }
            }
        }
        return null;
    }
    public synchronized List<Map<String, Object>> snapshot() {
        reap(); List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : new ArrayList<>(graph.entrySet())) { String id = entry.getKey(); Seen seen = entry.getValue(); result.add(Map.of("deviceId", id, "links", seen.advertisement().links(), "lastSeen", seen.at(), "reachable", nextHop(id) != null, "transports", seen.advertisement().transports() == null ? Map.of() : seen.advertisement().transports())); }
        return result;
    }
    @Override public void close() { timer.shutdownNow(); }
}
