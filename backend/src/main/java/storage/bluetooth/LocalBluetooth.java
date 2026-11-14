package storage.bluetooth;

import java.io.IOException;

/** The local Bluetooth radio as the mesh sees it. Implemented by {@link BlueZAdapter} (Linux) and {@link WindowsBluetoothAdapter}. */
public interface LocalBluetooth extends BluetoothDiscovery, AutoCloseable {
    /** Local adapter address, "AA:BB:CC:DD:EE:FF". */
    String address();

    /** Name other devices currently see. */
    String name();

    boolean isAdvertising();

    /** Partial platform support must be reported even when the radio itself is available. */
    default String discoveryLimitation() { return ""; }

    /** Makes this device visible with the public Relay/mesh tags where the platform allows it. */
    void advertise(String deviceId, String displayName, String meshId) throws IOException;

    void stopAdvertising() throws IOException;

    @Override
    void close();
}
