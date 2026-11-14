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
| `discovery` | `MeshDirectory` (unverified public UDP advertisements), legacy `DiscoveryManager`, `Candidate` |
| `custody` | Atomic AES-GCM spool, OS-protected key, bounded handoffs and storage policy |
| `security` | Bouncy Castle J-PAKE authentication, purpose-separated HKDF, TLS AES-GCM carriers |
| `platform` | Linux NetworkManager D-Bus Wi-Fi Direct/hotspot control and verified IP interface kinds |
| `device` | `DeviceProfile` (display name + visibility), `DeviceProfileStore`, `DeviceProfileService` |
| `link` | `HeartbeatManager` (neighbour liveness), `TopologyController` (see [topology.md](topology.md)) |
| `messaging` | `Message`/`MessageCodec`, `MeshNode` (neighbours, relay, dedupe), `ChatService` (ack + bounded retry) |
| `transfer` | `MeshFileService`: chunked, acknowledged, resumable file transfer over routes (see [file-transfer.md](file-transfer.md)) |
| `bluetooth` | `NativeRfcomm` (RFCOMM kernel sockets via FFM), `BluetoothTransport`, `BlueZAdapter`, `BluetoothScanner` (see [bluetooth.md](bluetooth.md)) |
| `routing` | `AodvRouter`, `RoutingTable`, `LinkCost`, `MeshTopology` (authenticated bounded adjacency gossip) (see [routing.md](routing.md)) |
| `cluster` | `PeerRegistry` (membership), `PeerEndpoint` (shared peer addresses) |
| `files` | `FileStore`, `FileMetadata`, inventory/metadata JSON, hashing |
| `web` | `RestApiServer`, `SetupAgent` (local UI API, no Internet needed) |

Tests mirror these packages under `backend/src/test/java/storage/`.

Backend folders: `backend/config/examples/` (sample peer configs; `backend/config/local-setup*.json` is
generated and git-ignored). The UI talks to the backend only through `/api` (setup agent on port 6080 proxies
to the node's REST API), so the two parts can be developed separately.

File sharing supports direct TCP and routed mesh delivery. Bluetooth Classic support targets Linux/BlueZ; see the hardware verification limits in the Bluetooth guide. Application entry points protect both carrier and direct file-service traffic with J-PAKE and TLS. See [implementation status and remaining platform gaps](offline-mesh-status.md).

There is no automatic backup replication or storage repair. Files remain only on intentional recipients; deleting through REST affects only local files. Existing received files and old metadata are preserved. Network route and connection recovery still run.

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
| `/api/discovery`, `GET/POST /api/visibility`, `GET/POST /api/relay-storage` | `SetupAgent`: pre-membership discovery, hide/leave lifecycle and local storage policy |
| `/api/setup/*` (Settings page) | `SetupAgent` |
| `/api/membership`, `/api/membership/{scan,create,join,leave}` | `SetupAgent`, public LAN/Bluetooth directory, authenticated membership |
| `/api/radio`, `/api/radio/{scan,connect,hotspot,stop}` | `NetworkManagerService` (local user + disconnection acknowledgement) |

The setup agent (port 6080) serves `frontend/dist` and proxies every other `/api/` call to the running node.
In live mode the people list is the union of heartbeat peers, mesh neighbours and routed destinations
("Via A · 2 hops"). The transport label is the real one (`TCP`, `Bluetooth`, `TCP + Bluetooth`).
