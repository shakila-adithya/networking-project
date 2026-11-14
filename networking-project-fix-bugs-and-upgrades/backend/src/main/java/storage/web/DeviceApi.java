package storage.web;

import java.io.IOException;
import java.util.Map;

/** What the REST layer needs from the running device: its name and visibility, nearby devices, scan and connect. */
public interface DeviceApi {
    /** Device id, display name, visibility, transport and Bluetooth state. */
    Map<String, Object> deviceStatus();

    /**
     * Changes the display name and/or visibility ({@code null} keeps the current value) and returns the new status.
     * Throws IllegalArgumentException with a user-readable message for an invalid name.
     */
    Map<String, Object> updateDevice(String displayName, Boolean visible) throws IOException;

    /** Bluetooth devices from the last scan and Relay peers on the LAN, with state and signal. */
    Map<String, Object> nearby();

    /** Starts a Bluetooth scan now. Returns false if Bluetooth is not in use. */
    boolean scanNow();

    /** Connects to a nearby device found by a scan or by LAN discovery. Throws IOException with a readable reason. */
    void connectDevice(String deviceId) throws IOException;
}
