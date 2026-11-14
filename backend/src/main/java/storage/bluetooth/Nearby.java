package storage.bluetooth;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Bluetooth device seen during a scan.
 *
 * @param rssi signal strength in dBm, or null when BlueZ did not report one
 */
public record Nearby(String address, String name, Integer rssi, boolean paired) {
    /** Relay devices advertise {@code relay:<deviceId>} as their Bluetooth name while the app runs. */
    public static final String NAME_PREFIX = "relay:";
    /** "Maya's laptop [relay:C]": a friendly name followed by the id in brackets (the name part is optional). */
    private static final Pattern NAMED = Pattern.compile("^(.*?)\\s*\\[relay:([A-Za-z0-9_-]{1,40})\\]$");
    private static final int MAX_FRIENDLY_LENGTH = 32;

    /**
     * The Bluetooth name a Relay device advertises: {@code "<display name> [relay:<id>]"}, or {@code "relay:<id>"} when no
     * display name was chosen. Brackets and control characters are removed from the display name so the id stays parseable.
     */
    public static String advertisedName(String displayName, String deviceId) {
        String clean = displayName == null ? "" : displayName.replaceAll("[\\[\\]\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (clean.length() > MAX_FRIENDLY_LENGTH) {
            clean = clean.substring(0, MAX_FRIENDLY_LENGTH).strip();
        }
        return clean.isEmpty() ? NAME_PREFIX + deviceId : clean + " [" + NAME_PREFIX + deviceId + "]";
    }

    /** The mesh device id if this is a Relay device, otherwise empty (headphones, phones, ...). */
    public Optional<String> relayDeviceId() {
        if (name == null) {
            return Optional.empty();
        }
        Matcher named = NAMED.matcher(name);
        if (named.matches()) {
            return Optional.of(named.group(2));
        }
        if (name.startsWith(NAME_PREFIX)) { // legacy form: just "relay:<id>"
            String id = name.substring(NAME_PREFIX.length());
            return id.matches("[A-Za-z0-9_-]{1,40}") ? Optional.of(id) : Optional.empty();
        }
        return Optional.empty();
    }

    /** The friendly name of a Relay device ("Maya's laptop"), or empty if it advertises only its id. */
    public Optional<String> friendlyName() {
        if (name == null) {
            return Optional.empty();
        }
        Matcher named = NAMED.matcher(name);
        return named.matches() && !named.group(1).isBlank() ? Optional.of(named.group(1)) : Optional.empty();
    }

    /** Maps dBm (about -100 weak ... -40 strong) to the 0-100 scale used by the topology controller. */
    public int signal() {
        if (rssi == null) {
            return 50;
        }
        return Math.max(0, Math.min(100, (rssi + 100) * 100 / 60));
    }
}
