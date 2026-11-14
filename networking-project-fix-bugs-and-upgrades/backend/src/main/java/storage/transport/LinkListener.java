package storage.transport;

/** Receives links accepted from neighbours. Called on the transport's accept thread: do not block. */
public interface LinkListener {
    void onIncomingLink(Link link);
}
