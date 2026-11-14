package storage.bluetooth;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;

/** A listening RFCOMM endpoint. */
public interface RfcommServer extends Closeable {
    /** Waits up to the timeout for a remote device to connect. Returns null if nobody did. */
    RfcommConnection accept(Duration timeout) throws IOException;

    @Override
    void close();
}
