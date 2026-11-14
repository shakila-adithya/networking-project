package storage.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs several transports at once (for example TCP over Wi-Fi and Bluetooth). Incoming links from any of
 * them reach the same listener; {@link #connect} picks the transport whose address format matches.
 */
public final class CompositeTransport implements Transport {
    private final List<Transport> transports;

    public CompositeTransport(List<Transport> transports) {
        if (transports.isEmpty()) {
            throw new IllegalArgumentException("at least one transport is required");
        }
        this.transports = List.copyOf(transports);
    }

    @Override
    public String kind() {
        return String.join("+", transports.stream().map(Transport::kind).toList());
    }

    @Override
    public String localAddress() {
        return String.join(" ", transports.stream().map(Transport::localAddress).toList());
    }

    @Override
    public void start(LinkListener incoming) throws IOException {
        List<Transport> started = new ArrayList<>();
        try {
            for (Transport transport : transports) {
                transport.start(incoming);
                started.add(transport);
            }
        } catch (IOException | RuntimeException e) {
            for (Transport transport : started) {
                transport.close();
            }
            throw e;
        }
    }

    @Override
    public boolean handles(String address) {
        return transports.stream().anyMatch(transport -> transport.handles(address));
    }

    @Override
    public Link connect(String address) throws IOException {
        for (Transport transport : transports) {
            if (transport.handles(address)) {
                return transport.connect(address);
            }
        }
        throw new IOException("no transport can connect to " + address);
    }

    @Override
    public void close() {
        for (Transport transport : transports) {
            transport.close();
        }
    }
}
