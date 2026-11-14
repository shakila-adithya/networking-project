# Topology control (growing and healing the neighbour graph)

Code: `storage.link.TopologyController`, `TopologyPolicy`, `TopologyTask` (a named `Runnable`).

Discovery code reports nearby devices as `Candidate(deviceId, address, signal)`:
configured peers are offered permanently, UDP-discovered peers expire after 30 s unless re-announced.
The Bluetooth scanner calls the same `offer(...)` with the Bluetooth address and RSSI-based signal.

| Rule | Why |
|---|---|
| Never exceed `maxLinks` own dials (default 5); dials still connecting count toward the cap | Bluetooth radios hold only a few connections |
| Candidates not known to be reachable are **bridges** and may use any free slot | joins separated parts of the mesh |
| Candidates already reachable are **redundancy** links, added only below `targetLinks` (default 3) | keeps a slot free for bridging while giving alternate paths |
| Weak candidates (signal < 20) are skipped unless the device has fewer than `minLinks` (default 2) | avoid wasting slots on poor links, but never stay isolated |
| A lost neighbour wakes the task immediately | replacement starts without waiting for the next interval |
| Failing addresses back off 1 s, 2 s, 4 s ... up to 60 s | stops hammering absent devices |

`topology` (console) shows each candidate, whether it is connected, and its last error.

## Limitations
- "Reachable" comes from the routing table, so a device never contacted before looks like a bridge and may
  cause one extra link; the reserved slot bounds this.
- The cap applies to links this device dials. Incoming links from other devices are accepted regardless
  (the radio stack would enforce its own limit).
- Healthy carriers remain open. Primary selection uses cost hysteresis; a failed primary immediately promotes a surviving transport. Unknown IP signal is not treated as measured RSSI.
- Automated recovery uses real loopback TCP and explicitly simulated alternate carriers. Two-device Bluetooth and cross-platform radio interoperability remain unverified. See [implementation status](offline-mesh-status.md).
