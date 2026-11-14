package storage.device;

/**
 * What the user chose for this device: the name other people see and whether the device is visible to nearby
 * devices. The device id (for example "C") is separate: it is the stable internal address used by the mesh.
 *
 * @param displayName friendly name such as "Maya's laptop"; empty means "show the id"
 * @param visible     whether nearby Bluetooth devices may find this one (hidden by default)
 */
public record DeviceProfile(String displayName, boolean visible) {
    public static final int MAX_NAME_LENGTH = 32;
    public static final DeviceProfile DEFAULT = new DeviceProfile("", false);

    public DeviceProfile {
        displayName = normalize(displayName);
    }

    /** Trims and validates a display name. Throws IllegalArgumentException with a message fit for the user. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The device name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The device name cannot contain control characters.");
        }
        return trimmed;
    }

    /** The name to show: the chosen one, or the device id when none was chosen. */
    public String effectiveName(String deviceId) {
        return displayName.isEmpty() ? deviceId : displayName;
    }

    public DeviceProfile withDisplayName(String name) {
        return new DeviceProfile(name, visible);
    }

    public DeviceProfile withVisible(boolean value) {
        return new DeviceProfile(displayName, value);
    }
}
