# Relay — offline messaging and file sharing

Relay connects devices so they can exchange messages and files over a local network or supported Bluetooth connections. Devices can relay messages and file chunks through intermediate peers when a direct connection is unavailable. Internet access is not required during normal use.

Files go only to their intended recipients. There is **no automatic backup replication, storage repair, redistribution, or delivery to devices that join later**. Network recovery still discovers replacement connections and routes when a physical path exists.

The complete cross-platform mesh product is **not finished**: Windows native radio adapters and real two-device radio interoperability are still outstanding. See [implementation and hardware verification status](docs/offline-mesh-status.md).

## Project layout

```text
backend/    Java 25 node, Maven wrapper, configuration and received files
frontend/   Next.js App Router, React and TypeScript interface
scripts/    Build/start and stop commands
docs/       Architecture, setup, routing, Bluetooth and transfer guides
```

The interface includes Network map, Messages, Share files, and Settings (including your device name). It starts in **Design preview**, which uses sample data and does not send to real devices. Select **Use live network** to use the Java API. The mode preference is saved in the browser. On the Network map, drag to move around, scroll or pinch to zoom, and click a device for details. Find me centers your device; Fit network restores the full view. Search results move the view to a device. With the map focused, use arrow keys to move, +/− to zoom, and 0 to fit. Map positions show connections, not physical locations.

## Build and start

Requirements: JDK 25 and Node.js 24. Maven and frontend dependencies may require internet access during the initial build. Once built, the Java backend serves the static frontend; a Node server is unnecessary.

From the repository root on Linux/macOS:

```sh
./scripts/run.sh
```

Open **http://localhost:6080**, switch to Live and use **Network map → Create mesh / Scan meshes / Join**. Settings also retains manual device setup. The launcher builds both parts, runs Java verification, and starts the setup agent. Keep the terminal running; Ctrl+C stops it.

For subsequent launches:

```sh
./scripts/run.sh --no-build
# Optional different UI port:
./scripts/run.sh --no-build -Dsetup.port=6085
```

The launcher preserves `backend/storage-data/` and `backend/config/local-setup.json`. If Wi-Fi changes, open Settings → Your network connection. Select Refresh connections after switching Wi-Fi, a phone hotspot or Ethernet. Choose your current connection and select Use this connection. If you left the mesh, join again from the Network map first. The saved mesh password and file location are kept.

Build and check separately:

```sh
(cd backend && ./mvnw clean verify)
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

On Windows, run Maven commands from `backend/` using `.\mvnw.cmd`. Use the frontend npm commands from the root or `frontend/`. See [frontend guide](frontend/README.md) for development and browser tests.

## Configure devices

Each device needs a unique peer ID, its own reachable advertised address, a TCP file-service port, and a local storage directory. Mesh members use a shared mesh ID and password. Public discovery is unverified; every application carrier authenticates with J-PAKE and TLS. See [joining through Settings](docs/join-device-C.md).

You can also start a console node using JSON configuration, from `backend/`:

```sh
./mvnw exec:java -Dexec.mainClass=storage.app.PeerNode -Dexec.args=config/examples/localhost-A.json
```

Example configuration:

```json
{
  "localPeerId": "A",
  "bindAddress": "0.0.0.0",
  "advertisedAddress": "192.168.1.10",
  "port": 5000,
  "storageDirectory": "storage-data/A",
  "clusterId": "my-mesh",
  "discoverySecret": "replace-this-with-your-own-mesh-password",
  "peers": [
    { "id": "A", "address": "192.168.1.10", "port": 5000 },
    { "id": "B", "address": "192.168.1.11", "port": 5000 }
  ]
}
```

Replace addresses with the devices' actual LAN addresses. `bindAddress` selects listening interfaces; `advertisedAddress` must be reachable by other peers and cannot be a wildcard. A peer list containing only the local device is valid, even with discovery disabled. It can receive connections and local saves, but needs another reachable device to communicate.

Validation rejects duplicate IDs/endpoints, invalid ports, missing local entries, mismatched local addresses and unknown JSON fields. Configuration keys and existing storage paths remain compatible.

Legacy localhost startup requires the same `RELAY_MESH_PASSWORD` environment variable on each device, with A/B/C on ports 5000/5001/5002:

```sh
./mvnw exec:java -Dexec.mainClass=storage.app.PeerNode '-Dexec.args=A 127.0.0.1 5000'
```

Start B and C in separate terminals using their corresponding IDs and ports.

## Connections and discovery

A device runs separate services:

| Service | Default |
|---|---|
| File-service TCP | Configured peer port, e.g. 5000 |
| Local REST API | Peer port + 1000; setup mode uses its saved REST port |
| Mesh TCP | Peer port + 2000 |
| Browser/setup host | 6080 |
| Public mesh discovery | UDP multicast 239.255.42.100:45556 |
| Bluetooth Classic | RFCOMM channel 22 on supported Linux/BlueZ systems |

LAN announcements and Bluetooth mesh names are unverified hints. Password authentication gates application links and the separate TCP file service. Discovered candidates do not authorize membership. `PeerRegistry` tracks authenticated peers and heartbeat states; discovery never sends previously received files to new peers.

Heartbeat checks are scheduled every 2 seconds with a 5-second probe timeout and a threshold of 5 consecutive failures. Failed checks progress through `SUSPECTED` to `DEAD`; a successful check returns the peer to `ALIVE`. Detection time depends on network timeouts and completed probes.

The topology controller maintains direct neighbours. AODV routing finds paths as needed and invalidates paths through lost links. Live delivery requires a physical path. With secure temporary storage available, pending data can also cross separate contacts using durable custody; the sender retains its original until final delivery. See [topology](docs/topology.md) and [routing](docs/routing.md).

Bluetooth is enabled alongside TCP by default, with TCP fallback if Bluetooth is unavailable. Visibility starts off. Choose a display name in Settings and use the visibility switch above the Network map. Hiding also leaves the mesh; showing again does not rejoin. See [Bluetooth setup and hardware limitations](docs/bluetooth.md).

## Messages and files

Chat uses acknowledgements and bounded retries. Broadcast chat reports handing messages to neighbours rather than confirmed receipt by everyone. When OS-protected storage is available, chat history and pending delivery state survive restart in an encrypted journal.

File sharing supports one or multiple chosen recipients. The API also supports `recipient=all`, taking a snapshot of registered `ALIVE` peers plus this device. New arrivals are not retroactive recipients. Each recipient keeps their received file; sending to another single device does not keep a permanent received-file copy on the sender.

The backend tries direct TCP delivery to registered peers, with mesh fallback. Routed transfers use acknowledged chunks, whole-file SHA-256 verification and progress/status reporting. Source staging, recipient chunk bitmaps and transfer journals survive restart, with bounded automatic resume when contacts return and a manual Resume control. This is temporary transfer state, not permanent backup storage. See [file sharing](docs/file-sharing.md) and [mesh transfer](docs/file-transfer.md).

Deleting a file through the REST API deletes **only the local device's file**. Other recipients keep theirs. Local files can be downloaded by ID; the Files list describes local files, not a global storage inventory.

## TCP file protocol

After J-PAKE and TLS authentication, each connection handles one request. UTF-8 control lines end with a newline and are bounded to 8 KiB. Metadata is UTF-8 JSON, bounded to 64 KiB, containing UUID, filename, byte size, SHA-256, creation time, and optional selected recipient IDs. Explicit byte lengths frame metadata and file contents.

| Request | Behaviour |
|---|---|
| `PING` | Reply `PONG` |
| `PUT <metadata-length>` | Read metadata; reply `READY`; receive exact file bytes; reply `OK STORED <id>` |
| `GET <id>` | Reply `FILE <metadata-length>`, metadata, then exact file bytes |
| `LIST_FILES` | Reply `FILES <length>` and bounded inventory JSON |
| `DELETE <id>` | Delete only on the addressed peer; reply `OK DELETED <id>` |
| `REPLICA_PUT <metadata-length>` | Legacy alias for `PUT`, with identical recipient and no-overwrite checks |

New clients use `PUT` for all direct deliveries. Neither upload command forwards or replicates a received file. Invalid requests receive `ERROR <code> <message>`; unknown commands receive `ERROR unsupported request`.

Control messages and arbitrary binary bytes share explicitly framed streams. Incoming files remain temporary until length/hash validation succeeds. UUID collisions and destination overwrites are rejected. Mesh transfer additionally limits chunks and concurrent incoming transfers.

The core protocol and REST backend currently allow up to 2 GiB; the browser file picker limits individual sends to 100 MiB. Mesh transfers also have a 65,536-chunk bound (1 GiB with the default 16 KiB chunks). These bounds are separate.

Received data is stored as:

```text
backend/storage-data/<peer>/files/<uuid>.bin
backend/storage-data/<peer>/metadata/<uuid>.json
backend/storage-data/<peer>/tmp/
```

Old metadata without recipient lists remains readable. Existing files, including previously created backup copies, are preserved; the application no longer manages additional copies.

## Console demonstration

With two or three configured peers running:

```text
status
peers
ping 127.0.0.1 5001
put 127.0.0.1 5001 sample.txt
get 127.0.0.1 5001 <file-id> downloaded.txt
inventory
neighbours
routes
chat B hello from A
send-file B sample.txt
transfers
quit
```

`put` sends only to the addressed peer. `inventory` observes reachable inventories without changing files. `send-file` uses mesh routing. Files can be transferred while heartbeat monitoring and other client sessions continue.

## Verification and limitations

Tests cover local-only uploads, chosen recipients, intentional fan-out, local deletion, no redistribution across peer failure/restart, integrity and duplicate checks, TCP timeouts, concurrent clients, discovery, routing, mesh transfer/resume and shutdown. Multicast tests skip on hosts without loopback multicast. Bluetooth hardware checks require appropriate hardware; fake-radio tests do not prove physical interoperability.

Application entry points protect mesh traffic and direct TCP file traffic using J-PAKE and TLS authenticated encryption. Local browser APIs check loopback, Host and Origin. Encryption is hop by hop; mesh members are trusted. Linux native Wi-Fi Direct/hotspot controls use NetworkManager; Windows native radio support is still missing. See [security, migration and exact hardware limits](docs/offline-mesh-status.md).

Upgrade every device together. An older backend can still create automatic copies when receiving ordinary `PUT` uploads. The retained upload-command alias works inside authenticated sessions. Old unauthenticated peers must update before connecting. REST responses no longer include `replicaCount` or `replicationStatus`; sharing retains `deliveredTo`, `failedPeers`, `transfers`, and `shareStatus`.

Ctrl+C shuts down the node and releases sockets. Console nodes also accept `quit`. If an abnormal exit leaves a process running, use `./scripts/stop.sh` (`--force` only when necessary). Existing user files are not removed on shutdown.
