# Source layout

```
backend/    Java node: src/main|test/java/storage, pom.xml, mvnw, config/, storage-data/
frontend/   Next.js UI; `npm run build` exports to frontend/dist, served by the backend
docs/  scripts/
```

Backend root package `storage`, one sub-package per responsibility:

| Package | Contents |
|---|---|
| `app` | `PeerNode` (entry point, console loop), `ManagedPeer` (node lifecycle in setup mode) |
| `config` | `PeerConfiguration`, `SetupConfigStore`, `LocalNetworks` |
| `transport` | `PeerServer` (`ServerSocket`), `PeerClient` (`Socket`), `Protocol` (framing); `Transport`/`Link` boundary with `TcpTransport` and the `InMemoryTransport` simulation |
| `discovery` | `DiscoveryManager` (UDP multicast, `DatagramSocket`), `Candidate` |
| `device` | `DeviceProfile` (display name + visibility), `DeviceProfileStore`, `DeviceProfileService` |
| `link` | `HeartbeatManager` (neighbour liveness), `TopologyController` (see [topology.md](topology.md)) |
| `messaging` | `Message`/`MessageCodec`, `MeshNode` (neighbours, relay, dedupe), `ChatService` (ack + bounded retry) |
| `transfer` | `MeshFileService`: chunked, acknowledged, resumable file transfer over routes (see [file-transfer.md](file-transfer.md)) |
| `bluetooth` | `NativeRfcomm` (RFCOMM kernel sockets via FFM), `BluetoothTransport`, `BlueZAdapter`, `BluetoothScanner` (see [bluetooth.md](bluetooth.md)) |
| `routing` | `AodvRouter`, `RoutingTable`, `LinkCost` (see [routing.md](routing.md)) |
| `cluster` | `PeerRegistry`, `ReplicationManager`, `SelfHealingManager`, `ReconciliationManager` |
| `files` | `FileStore`, `FileMetadata`, inventory/metadata JSON, hashing |
| `web` | `RestApiServer`, `SetupAgent` (local UI API, no Internet needed) |

Tests mirror these packages under `backend/src/test/java/storage/`.

Backend folders: `backend/config/examples/` (sample peer configs; `backend/config/local-setup*.json` is
generated and git-ignored). The UI talks to the backend only through `/api` (setup agent on port 6080 proxies
to the node's REST API), so the two parts can be developed separately.

Still planned: file sharing over routes, security, and the Bluetooth transport.

## How the frontend connects to the backend
| UI call | Backend |
|---|---|
| `GET /api/health`, `/api/files`, `POST /api/files?recipient=`, `GET /api/files/{id}/download` | `RestApiServer` (storage) |
| `GET /api/mesh` (device id, transport, neighbours, **routes**, inbox) | `RestApiServer` → `MeshNode`, `AodvRouter` |
| `POST /api/mesh/chat`, `GET /api/mesh/chat/{id}` | `ChatService` (ack + bounded retry) |
| `POST /api/mesh/connect` (this laptop only) | `MeshNode.connectTo` |
| `GET /api/mesh/transfers[/{id}]`, `POST /api/mesh/transfers/{id}/resume` | `MeshFileService` (progress, resume) |
| `bluetooth` object in `GET /api/mesh` | `MeshRuntime.bluetoothStatus` (adapter, nearby devices) |
| `GET/POST /api/device` (display name, visibility), `GET /api/nearby`, `POST /api/bluetooth/scan`, `POST /api/mesh/connect {deviceId}` | `DeviceApi` implemented by `MeshRuntime`; profile saved by `device.DeviceProfileStore` |
| `/api/setup/*` (Settings page) | `SetupAgent` |

The setup agent (port 6080) serves `frontend/dist` and proxies every other `/api/` call to the running node.
In live mode the people list is the union of heartbeat peers, mesh neighbours and routed destinations
("Via A · 2 hops"). The transport label is the real one (`TCP`, `Bluetooth`, `TCP + Bluetooth`).
