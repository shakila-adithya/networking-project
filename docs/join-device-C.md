# Join another Relay device

All members must run the updated backend with JDK 25. Build once with Maven in `backend/` and Node.js 24/npm in `frontend/`; Internet is not required after dependencies and the frontend are built. Do not copy another laptop's managed configuration or storage directory.

## Join from Network map

1. Start the setup agent and open `http://localhost:6080` on the device being configured. Switch to Live.
2. Start by scanning for existing meshes. If none are found, or you want a separate group, on the first device select Create mesh, choose the local network (or the Bluetooth option when no LAN address exists), and enter a mesh ID and password. Creating/joining makes the device visible over Bluetooth; visibility remains adjustable on the map.
3. On another device, select Scan meshes. LAN and Bluetooth names are unverified until password authentication succeeds. Select the desired mesh, choose a detected Wi-Fi/LAN or Bluetooth connection, enter its password, and select Authenticate and join. Wi-Fi/LAN joining also asks which local network to use. Bluetooth joining uses the selected Bluetooth advertisement without requiring a Wi-Fi selection. Extra IP addresses may still support other links in the same mixed-transport mesh; the map reports actual active transports. For another physical link, use Need another connection? to open Wi-Fi Direct or hotspot setup; establish that link first, refresh connections, then scan for the Relay mesh again. No per-member approval is required. Accept OS pairing prompts when required.
4. Join authenticates the selected advertisement before saving settings. A wrong password leaves the previous saved membership intact. Watch the connection status after startup; CONNECTED requires an authenticated neighbour. A newly created isolated mesh can be running without any neighbours.
5. Leave the current mesh before joining a different one. Leaving closes local mesh/file services and writes a marker preventing automatic rejoin. Received files, device identity and storage paths remain intact.

Mesh passwords contain 16–256 characters. Mesh IDs use letters, numbers, dots, underscores and hyphens. The browser never saves passwords in localStorage/sessionStorage and APIs do not return saved credentials.

Linux hotspot/Wi-Fi Direct controls are in Network map. Radio changes require acknowledging that the current Wi-Fi may disconnect. Wi-Fi Direct currently lists OS P2P devices: connect the radio group first, then scan/join the Relay mesh over that IP network. Windows native radio adapters remain unimplemented; Windows LAN uses the Java IP transport. See [exact implementation and hardware limits](offline-mesh-status.md).

## Start the local host

Linux, from the repository root:

```sh
./scripts/run.sh
```

Windows PowerShell, after installing JDK 25 and Node.js 24:

```powershell
cd frontend
npm ci
npm run build
cd ../backend
.\mvnw.cmd compile
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=--setup'
```

Keep the terminal open. Ctrl+C stops the agent and its owned resources. Add `-Dsetup.port=6085` if port 6080 is occupied, then open localhost on that port.

## Advanced Settings

Settings lets you change your device name even before joining, understand your connection, and repair a changed Wi-Fi/Ethernet address without re-entering your mesh password. Connections are loaded once when the page opens. After switching Wi-Fi, a phone hotspot or Ethernet, select Refresh connections in Settings or in Network map → Wi-Fi / LAN (also available in the create/join form). The connection list is not polled or refreshed on window focus or online events. Refresh only reads the available connections; it does not join a mesh or interrupt transfers. Before joining or after leaving, Settings keeps the detected connections and manual refresh available. Create or join meshes from the Network map. For an existing mesh, choose your current connection and select Use this connection to apply it. Use this connection restarts Relay services; it does not switch the operating system’s Wi-Fi. Received files, mesh credentials, other saved peer addresses and ports are preserved. After leaving a mesh, join again from the Network map.

For manual setup, open Advanced settings → Set up a mesh manually. Enter the Device ID, mesh name/password and local network. Port numbers are collapsed because most people should keep their existing values. Save manual settings and reconnect validates, saves atomically, and restarts Relay in one action. Restarting interrupts active transfers. Changing the device ID preserves the existing storage directory. Unlike Network map Join, manual settings can intentionally configure an isolated mesh. A failed startup leaves the setup UI reachable. All devices still require the same password, and old unauthenticated devices must update.

The existing `clusterId`/`discoverySecret` JSON keys remain readable and represent the mesh ID/password. Old custom multicast settings remain parseable for configuration compatibility, but Relay v2 uses public `239.255.42.100:45556` advertisements and authenticates the actual carriers. There is no clock-skew prerequisite for these public advertisements.

The agent preserves the existing managed peer ID and storage directory when switching mesh membership. Existing files, including metadata without recipient lists, remain readable. No old files are automatically sent to joining devices; no backup replication or storage repair runs.

## Connectivity and firewall

Use localhost on the device you are managing. Device, membership, radio, mesh and file APIs reject foreign Hosts/Origins and non-loopback callers, including through the setup proxy. The setup page may be served on the LAN, but remote devices cannot use its proxy to read local files or change this laptop. The internal REST service binds loopback.

Allow peer TCP, mesh TCP (peer port + 2000) and UDP discovery 45556 on the intended private subnet. Do not disable the firewall. Guest-network/client isolation, VLANs, VPNs and multicast restrictions can prevent discovery or paths. Configure static peers/connect by address when suitable; authenticated connections are still required. No route is possible across a physical partition.

Verification details and the untested Linux/Windows hardware combinations are recorded in [offline-mesh-status.md](offline-mesh-status.md). Tests using loopback or fake radios do not demonstrate physical interoperability.
