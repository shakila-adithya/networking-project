package storage.bluetooth;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/** One connected RFCOMM stream pair. Closing it unblocks a read that is waiting in another thread. */
public interface RfcommConnection extends Closeable {
    /** Address of the remote device, "AA:BB:CC:DD:EE:FF". */
    String remoteAddress();

    InputStream input();

    OutputStream output();

    @Override
    void close();
}
