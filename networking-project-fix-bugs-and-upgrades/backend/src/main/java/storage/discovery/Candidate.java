package storage.discovery;

/**
 * A device that was seen nearby and could become a neighbour.
 *
 * @param deviceId mesh id the device advertises
 * @param address  transport address to connect to (host:port for TCP; a Bluetooth address later)
 * @param signal   link quality hint from 0 (unusable) to 100 (excellent); use 50 when the carrier has none
 */
public record Candidate(String deviceId, String address, int signal) {
    public Candidate {
        if (deviceId == null || deviceId.isBlank() || address == null || address.isBlank()) {
            throw new IllegalArgumentException("deviceId and address are required");
        }
        if (signal < 0 || signal > 100) {
            throw new IllegalArgumentException("signal must be between 0 and 100");
        }
    }
}
