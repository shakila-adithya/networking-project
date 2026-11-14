package storage.security;

import storage.transport.*;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;

/** Authenticates before exposing a carrier to mesh HELLO/routing. Bounded concurrent handshakes. */
public final class SecureTransport implements Transport {
    private final Transport carrier;
    private final MeshSecurity security;
    private final Set<Link> links = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;
    public SecureTransport(Transport carrier, MeshSecurity security) { this.carrier = carrier; this.security = security; }
    public String kind() { return carrier.kind(); }
    public String localAddress() { return carrier.localAddress(); }
    public boolean handles(String address) { return carrier.handles(address); }
    public void start(LinkListener listener) throws IOException {
        carrier.start(raw -> {
            if (closed || links.size() >= 64 || !slots.tryAcquire()) { raw.close(); return; }
            links.add(raw);
            try { workers.submit(new AuthenticationTask(raw, listener)); }
            catch (RejectedExecutionException e) { links.remove(raw); slots.release(); raw.close(); }
        });
    }
    private final class AuthenticationTask implements Runnable {
        private final Link raw;
        private final LinkListener listener;
        AuthenticationTask(Link raw, LinkListener listener) { this.raw = raw; this.listener = listener; }
        public void run() {
            try { listener.onIncomingLink(authenticate(raw, false)); }
            catch (IOException | RuntimeException e) { raw.close(); links.remove(raw); System.err.println(e.getMessage()); }
            finally { slots.release(); }
        }
    }
    private Link authenticate(Link raw, boolean client) throws IOException {
        Link encrypted = security.authenticate(raw, client, "mesh");
        if (closed) { raw.close(); links.remove(raw); throw new IOException("mesh is closed"); }
        return new Link() {
            public String kind() { return raw.kind(); }
            public String remoteAddress() { return encrypted.remoteAddress(); }
            public boolean isOpen() { return encrypted.isOpen() && raw.isOpen(); }
            public void send(byte[] frame) throws IOException { encrypted.send(frame); }
            public byte[] receive() throws IOException { return encrypted.receive(); }
            public void close() { encrypted.close(); links.remove(raw); }
        };
    }
    public Link connect(String address) throws IOException {
        if (closed || !slots.tryAcquire()) throw new IOException("Authentication busy; retry shortly");
        Link raw = null;
        try {
            raw = carrier.connect(address); links.add(raw);
            if (closed) throw new IOException("mesh is closed");
            return authenticate(raw, true);
        } catch (IOException | RuntimeException e) {
            if (raw != null) { raw.close(); links.remove(raw); }
            throw e;
        } finally { slots.release(); }
    }
    public void close() {
        closed = true;
        carrier.close();
        links.forEach(Link::close); links.clear();
        workers.shutdownNow();
    }
}
