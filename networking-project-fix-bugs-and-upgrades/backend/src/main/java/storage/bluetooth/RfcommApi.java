package storage.bluetooth;

import java.io.IOException;
import java.time.Duration;

/**
 * Bluetooth Classic RFCOMM streams (serial-port style connections). {@link NativeRfcomm} implements it with
 * the Linux kernel Bluetooth stack; tests use an in-memory fake. Nothing above this interface needs to know.
 */
public interface RfcommApi {
    /** Starts listening on an RFCOMM channel (1-30) of the local adapter. */
    RfcommServer listen(int channel) throws IOException;

    /** Connects to a remote device. Throws IOException if it is out of range, off, or not listening. */
    RfcommConnection connect(String bluetoothAddress, int channel, Duration timeout) throws IOException;
}
