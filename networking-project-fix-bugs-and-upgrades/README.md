# Self-Healing Distributed Storage System

The frontend is now **Relay**, built with Next.js App Router, React 19, and TypeScript from the supplied design export. Open Network map, Messages, or Share files; use **Settings** in the footer for cluster setup. The default **Design preview** reproduces the supplied sample activity. Select **Use live network** to connect the interface to the Java API. See [frontend guide](frontend/README.md) for architecture, tests, and preview limitations.

## Project layout

```
backend/    Java 25 mesh node (Maven): src/, pom.xml, mvnw, config/, storage-data/
frontend/   Next.js UI (built to frontend/dist and served by the backend)
docs/       architecture, routing, topology, feature guides
scripts/    run.sh launcher (builds both, starts the backend)
```

Run **Maven and Java commands from `backend/`** (`cd backend`): `config/` and `storage-data/` below are relative to it, and the backend serves the built UI from `../frontend/dist` (override with `-Dui.dir=<path>`). The launcher does this for you.

## Quick start on Linux/macOS

Use JDK 25 and Node.js 24 (`nvm install` reads `.nvmrc`). The Java version is also recorded in `.java-version`.

```bash
./scripts/run.sh
```

This installs the locked frontend dependencies, builds the UI, runs the Java tests, and starts the setup agent. Open **http://localhost:6080** and use **Settings** to configure or join a cluster. Keep the terminal running; press Ctrl+C to stop.

For subsequent launches without rebuilding, use `./scripts/run.sh --no-build`. To use a different UI port, run `./scripts/run.sh --no-build -Dsetup.port=6085`.

If a saved configuration refers to an old Wi-Fi address, open Settings, refresh the detected networks, choose the current address, apply, and restart the node. Existing files in `backend/storage-data/` and saved settings in `backend/config/local-setup.json` are preserved by the launcher. A second running peer is required for replication; a single peer can serve the UI but cannot provide redundant storage.

To build and test without starting the app:

```bash
(cd backend && ./mvnw clean verify)
npm --prefix frontend ci
npm --prefix frontend run build
```

Multicast integration tests skip when the host does not support multicast on loopback. Static peer tests still exercise transfers, replication, failover, and recovery.

For UI-based onboarding of a third laptop, see [Join device C through Settings](docs/join-device-C.md). Start the setup agent with `PeerNode --setup`; it serves the UI before a cluster configuration exists and persists settings separately from existing JSON configurations.

Phase 6A adds validated external peer configuration for localhost and private LAN deployments. Phase 6B adds authenticated, optional LAN multicast discovery and dynamic membership. The JavaFX GUI remains deferred to later phases.

## Bluetooth and file sharing over the mesh

* **Bluetooth is on by default** (falls back to TCP if unavailable) and your device stays **hidden until you make it visible**: set your name on **My device**, then on the **Network map** select your own device to switch visibility, and use **Nearby devices** to scan and connect. Real Bluetooth (Linux/BlueZ): the device advertises itself, finds other Relay devices and connects to them. Details, hardware requirements, what changes on your adapter (and is restored), and how to test with two devices: [docs/bluetooth.md](docs/bluetooth.md).
* Files can be sent to devices that are only reachable through the mesh, with live progress, automatic re-routing and **resume**: [docs/file-transfer.md](docs/file-transfer.md).

## Stopping the app

Press **Ctrl+C** in the terminal running `scripts/run.sh` (or close the terminal; for a CLI node type `quit`). The JVM shuts the node down, releases every port and exits: this is covered by `ShutdownTest`, which starts the real app in a separate JVM and checks it exits and frees its ports. If a process survives an abnormal exit (crashed terminal, `kill -9` of the launcher), run `./scripts/stop.sh` to stop it gracefully (`--force` to kill).

## Requirements

- JDK 25 or newer
- Node.js 24 for building the React frontend
- PowerShell or another terminal

The project includes a Maven Wrapper, so a separate Maven installation is not required.

```powershell
java -version
.\mvnw.cmd -version
```

The wrapper downloads Maven on its first run.

## Build and automated tests

```powershell
.\mvnw.cmd clean test
```

Tests cover the Phase 1 `PING`/`PONG` behavior, all Phase 2 transfer and safety behavior, Phase 3 replication behavior, heartbeat responses, reachable and failed peer states, recovery to `ALIVE`, clean shutdown, Phase 5A inventory snapshots, conflict detection, bounded/malformed inventory responses, and compatibility between monitoring and replication.

## Run three peers in VS Code

First compile the project:

```powershell
.\mvnw.cmd compile
```

Open three terminals at the project root and leave all three processes running.

Terminal 1 - Peer A:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=A 127.0.0.1 5000'
```

Terminal 2 - Peer B:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=B 127.0.0.1 5001'
```

Terminal 3 - Peer C:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=C 127.0.0.1 5002'
```

Each peer owns an independent directory:

```text
storage-data/A/
storage-data/B/
storage-data/C/
```

Within each peer directory, file contents, JSON metadata, and incomplete temporary files are separated:

```text
files/<file-id>.bin
metadata/<file-id>.json
tmp/
```

### Phase 6A JSON configuration

The preferred startup form is:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=config\examples\localhost-A.json'
```

`PeerNode <config.json>` loads these fields:

```json
{
  "localPeerId": "A",
  "bindAddress": "0.0.0.0",
  "advertisedAddress": "192.168.1.10",
  "port": 5000,
  "storageDirectory": "storage-data/A",
  "peers": [
    { "id": "A", "address": "192.168.1.10", "port": 5000 },
    { "id": "B", "address": "192.168.1.11", "port": 5001 },
    { "id": "C", "address": "192.168.1.12", "port": 5002 }
  ]
}
```

`bindAddress` controls the local listening interface. `0.0.0.0` listens on all local interfaces, while `advertisedAddress` is the address other peers use and must be a real reachable host/IP, never `0.0.0.0` or `::`. Do not guess the advertised address: inspect the laptop's Wi-Fi adapter with `ipconfig` and choose its private IPv4 address. Every peer must use the same peer list, with its own local ID, advertised address, port, and storage directory changed as appropriate. The supplied `config\examples\localhost-A.json` preserves the three-peer localhost layout; copy it for B and C and update the local fields.

The legacy form remains supported for the original localhost setup:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=A 127.0.0.1 5000'
```

Configuration validation rejects duplicate peer IDs, duplicate address/port endpoints, invalid ports, missing local peer entries, local endpoint mismatches, wildcard advertised addresses, unknown JSON fields, and configurations with fewer than two peers. The configured peer endpoints are shared by replication, heartbeat, inventory, self-healing, and reconciliation; replication factor remains 2.

### Two-laptop LAN example

The files `config\examples\lan-laptop-A.json` and `config\examples\lan-laptop-B.json` illustrate two LAN peers. Replace `192.168.1.10` and `192.168.1.11` with the actual private Wi-Fi IPv4 addresses, and add a third peer entry if using the existing A/B/C topology. Start each laptop with its local configuration:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=config\examples\lan-laptop-A.json'
```

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=config\examples\lan-laptop-B.json'
```

Run these commands only on a trusted private LAN. The application does not perform discovery or authentication and should not be exposed to the public Internet.

### Safe Windows Firewall and connectivity checks

Allow only the configured TCP port from the trusted private subnet. Run PowerShell as Administrator and replace the subnet and port for the deployment; do not disable Windows Firewall and do not use `RemoteAddress Any`:

```powershell
New-NetFirewallRule -DisplayName "Self-Healing Storage 5000 LAN" -Direction Inbound -Protocol TCP -LocalPort 5000 -RemoteAddress 192.168.1.0/24 -Action Allow -Profile Private
```

On the other laptop, test the port before starting file operations:

```powershell
Test-NetConnection 192.168.1.10 -Port 5000
```

`TcpTestSucceeded` must be `True`. If it is false, verify both laptops are on the same private Wi-Fi network, the selected address belongs to the Wi-Fi adapter, Windows Network Profile is Private, the configured port is listening, and the firewall rule is limited to the correct subnet. Phase 6A does not change firewall settings automatically.

## Replication architecture

The replication factor is `2`: one primary copy and one replica on a different peer. `ReplicationManager` owns the peer list and deterministic ring placement:

```text
A -> B
B -> C
C -> A
```

A normal `PUT` is committed and SHA-256 verified on the receiving peer before replication begins. The upload reservation is released before any replica network transfer. The receiving peer then sends the same UUID, filename, size, SHA-256 hash, creation timestamp, and file bytes using `REPLICA_PUT`.

`REPLICA_PUT` uses the normal two-stage upload validation but never starts another replication, preventing loops. Replication is synchronous for Phase 3; there are no retries or background repair tasks.

## Heartbeat and failure-detection architecture

Each `PeerNode` owns one `HeartbeatManager`. It monitors only the other configured peers and reuses the existing TCP `PING`/`PONG` operation. Monitoring runs independently from the server's file-transfer executor and the replication connection, so a slow or unavailable peer does not block uploads or downloads.

Default configuration:

```text
Heartbeat interval:       2 seconds
TCP connection timeout:  1 second
Failure threshold:       3 consecutive failures
```

The defaults are configurable through the `HeartbeatManager` constructor. Peer state transitions are:

```text
Successful heartbeat                       -> ALIVE, failures = 0
First or second consecutive failed check   -> SUSPECTED
Third consecutive failed check             -> DEAD
Successful check after SUSPECTED or DEAD   -> ALIVE, failures = 0
```

The manager logs only state changes, not every successful heartbeat. It uses a dedicated scheduler plus virtual-thread workers and shuts both down when the peer exits.

## Manual testing

### Health check

In Peer A's terminal:

```text
ping 127.0.0.1 5001
ping 127.0.0.1 5002
```

Expected responses include `PONG`.

### Peer status

In any peer terminal:

```text
status
```

Example:

```text
Peer  Address              State       Failures
B     127.0.0.1:5001       ALIVE       0
C     127.0.0.1:5002       ALIVE       0
```

To demonstrate failure detection:

1. Keep Peers A and B running.
2. Type `quit` in Peer C's terminal.
3. Within about 2 seconds, `status` on Peer A shows C as `SUSPECTED`.
4. After three failed checks, about 6 seconds, it shows C as `DEAD`.
5. Restart Peer C with the original command.
6. After the next successful heartbeat, Peer A reports C as `ALIVE` with zero failures.

State-change messages are also printed when transitions occur.

### Replica inventory

In any peer terminal, type:

```text
inventory
```

The command lists the local snapshot and attempts `LIST_FILES` against every other endpoint in the same configured peer list. It displays each peer's heartbeat state, the number of files returned, and every UUID found on local or reachable peers. A failed request is shown as `unavailable` with its reason; it is never treated as an empty inventory. Copies are matched by UUID. If filename, size, SHA-256, or creation timestamp differs for the same UUID, the row is marked `CONFLICTING METADATA`.

Inventory is observational only. It does not copy, delete, redistribute, repair, or otherwise change files.

### Upload

Create a sample file in a fourth terminal:

```powershell
Set-Content -LiteralPath sample.txt -Value 'Phase 2 binary transfer test'
```

In Peer A's terminal, upload it to Peer B:

```text
put 127.0.0.1 5001 sample.txt
```

The successful response prints a stable UUID. Peer B stores the primary in `storage-data/B`, then deterministically stores the replica with the same UUID and metadata in `storage-data/C`.

Verify that both copies exist from the fourth terminal, replacing `<file-id>` with the printed UUID:

```powershell
Get-FileHash "storage-data\B\files\<file-id>.bin" -Algorithm SHA256
Get-FileHash "storage-data\C\files\<file-id>.bin" -Algorithm SHA256
Get-Content "storage-data\B\metadata\<file-id>.json"
Get-Content "storage-data\C\metadata\<file-id>.json"
```

Both hashes and both metadata documents must match.

### Download

In Peer C's terminal, replace `<file-id>` with the UUID printed by the upload:

```text
get 127.0.0.1 5001 <file-id> downloaded-sample.txt
```

Compare the files from the fourth terminal:

```powershell
Get-FileHash sample.txt -Algorithm SHA256
Get-FileHash downloaded-sample.txt -Algorithm SHA256
```

The hashes must match. Existing destination files are never overwritten.

Type `quit` in each peer terminal after testing.

## TCP protocol

Every connection carries exactly one request. Control lines are UTF-8 and end with a newline byte (`\n`). A control line is limited to 8 KiB. Metadata is UTF-8 JSON limited to 64 KiB. Files are limited to 100 MiB and are streamed in 64 KiB chunks.

The metadata JSON contains:

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "report.pdf",
  "size": 245760,
  "sha256": "64-lowercase-hexadecimal-characters",
  "createdAt": "2026-11-11T10:30:00Z"
}
```

### PING

```text
Client -> Server: PING\n
Server -> Client: PONG\n
```

This is unchanged from Phase 1.

The heartbeat manager sends this same request with a one-second connect/read timeout. Heartbeat traffic does not introduce another wire command.

### PUT

```text
Client -> Server: PUT <metadata-byte-length>\n
Client -> Server: <exactly metadata-byte-length JSON bytes>
Server -> Client: READY\n
Client -> Server: <exactly metadata.size raw file bytes>
Server -> Client: OK STORED <file-id>\n
```

The client does not send file bytes unless the server returns `READY`. Before `READY`, the server validates the JSON, UUID, filename, declared size, hash format, size limit, and duplicate status. After receiving the exact declared number of bytes, the server compares SHA-256 hashes before publishing the file.

After a normal `PUT` commits successfully, the receiving peer attempts one replica upload. A successful normal upload keeps the existing response:

```text
OK STORED <file-id>\n
```

If the primary commits but replication fails, the primary is retained and the server explicitly returns:

```text
OK STORED <file-id> REPLICATION_FAILED <code>: <reason>\n
```

The CLI reports the primary as stored and displays the replication warning. It never reports that replication succeeded when the destination rejected the UUID or could not be reached.

### REPLICA_PUT

```text
Source peer -> Replica peer: REPLICA_PUT <metadata-byte-length>\n
Source peer -> Replica peer: <exactly metadata-byte-length JSON bytes>
Replica peer -> Source peer: READY\n
Source peer -> Replica peer: <exactly metadata.size raw file bytes>
Replica peer -> Source peer: OK STORED <file-id>\n
```

The replica peer performs the same metadata, size, UUID, filename, SHA-256, temporary-file, and no-overwrite checks as a normal upload. It does not invoke `ReplicationManager` after storing the replica.

### GET

```text
Client -> Server: GET <file-id>\n
Server -> Client: FILE <metadata-byte-length>\n
Server -> Client: <exactly metadata-byte-length JSON bytes>
Server -> Client: <exactly metadata.size raw file bytes>
```

The client writes to a temporary file, verifies SHA-256, and then moves it to the requested destination.

### LIST_FILES

```text
Client -> Server: LIST_FILES\n
Server -> Client: FILES <inventory-byte-length>\n
Server -> Client: {"files":[<FileMetadata>, ...]} bytes
```

The inventory payload reuses the existing `FileMetadata` JSON shape, including UUID, filename, byte size, SHA-256, and ISO-8601 creation time. The payload is limited to 10,000 entries and 4 MiB; the client validates the header length, reads exactly that many bytes, rejects malformed JSON, rejects unknown fields, validates every `FileMetadata` record, and refuses oversized responses before allocating an unbounded buffer.

`FileStore.inventory()` only returns complete final content/metadata pairs. Metadata files are read in a deterministic snapshot, uploads marked in progress are skipped, and temporary `.part` files are never included. The operation does not expose files before their atomic publication.

### Errors

Protocol errors use:

```text
ERROR <code> <message>\n
```

Examples include `INVALID_METADATA`, `DUPLICATE_FILE_ID`, `HASH_MISMATCH`, `INCOMPLETE_TRANSFER`, and `NOT_FOUND`. Replication transport failures are reported as `REPLICATION_FAILED` after the primary has been stored. Unknown Phase 1 commands retain the original response `ERROR unsupported request`.

Message boundaries are never inferred from connection closure: line endings delimit control messages, the PUT/FILE header supplies the JSON byte count, and `metadata.size` supplies the exact file byte count.

## Safety and integrity behavior

- Filenames containing path separators, traversal names, control characters, or unsafe Windows characters are rejected.
- Stable UUIDs identify stored files independently of their original names.
- Duplicate UUIDs and destination overwrites are rejected.
- SHA-256 is calculated before upload, while receiving on the server, and while downloading on the client.
- Incoming content remains in `tmp` until validation succeeds.
- Final moves are atomic where the filesystem supports them, with a safe fallback.
- Interrupted and failed transfers delete temporary files and do not create metadata entries.
- Replicas retain the primary file's stable UUID and complete original metadata.
- Replica uploads never cascade to a third peer.
- Replica failure does not remove or misreport the successfully committed primary.
- Heartbeat state is maintained in thread-safe immutable snapshots.
- Monitoring excludes the local peer and does not modify stored files or replica placement.
- Inventory snapshots are read-only, thread-safe, bounded, and exclude incomplete uploads.
- Inventory comparison groups copies by UUID and reports conflicting metadata instead of accepting it as a valid replica.

## LAN use

Phase 6A uses the JSON configuration described above for separate laptops. The same configured endpoint list is shared by replication, heartbeat monitoring, inventory, self-healing, and reconciliation.

## Phase 5A manual verification

1. Run `.\mvnw.cmd clean test` and confirm the inventory tests pass along with the existing transfer, replication, and heartbeat tests.
2. Start Peers A, B, and C using the commands above and wait for heartbeat status to become `ALIVE`.
3. Upload a file to Peer B with `put 127.0.0.1 5001 sample.txt`; the normal replication path should place the same UUID on Peer C.
4. Run `inventory` in Peer A. Confirm B and C show the UUID and matching filename, size, hash, and creation timestamp.
5. Stop Peer C, wait until heartbeat reports `DEAD`, and run `inventory` again. Confirm C is marked `unavailable`, not empty.
6. For conflict reporting, only in a disposable test directory, edit one replica's JSON metadata without changing its UUID and run `inventory`; the UUID row must show `CONFLICTING METADATA`. No automatic repair is expected in Phase 5A.

## Phase 5B automatic replica repair

`SelfHealingManager` runs an asynchronous inventory check beside `HeartbeatManager`. It groups reachable inventories by stable UUID, requires all observed metadata for a UUID to agree, and repairs only when fewer than two reachable copies exist. A repair streams a verified `GET` from a healthy source into the destination's existing `REPLICA_PUT` path, then validates the destination inventory. UUID, filename, size, SHA-256, and `createdAt` are preserved; no existing UUID is overwritten.

Repair ownership is deterministic: the lexicographically smallest reachable peer currently holding the UUID owns the repair attempt. This avoids ordinary duplicate work across peer processes without introducing a central coordinator. A peer that is not the owner only observes and reports the under-replication. This is a best-effort coordination rule: concurrent inventory changes or a peer starting with stale observations can still cause a safe duplicate rejection, never an overwrite.

Each UUID has at most one local active job. Failed transfers retry at most three times with exponential backoff (2, 4, then 8 seconds using the default settings). Unreachable inventories, fewer than two reachable peers, metadata conflicts, duplicate UUID responses, and placement changes are deferred or failed and remain visible in recent outcomes. A recovered peer is rechecked before transfer and the destination inventory is verified afterward. Phase 5B does not delete extra copies or reconcile recovered peers; those are Phase 5C.

The read-only CLI command `repair-status` displays currently under-replicated UUIDs, active repair jobs, and recent outcomes:

```text
repair-status
```

### Phase 5B manual three-peer test

1. Run `.mvnw.cmd clean test` and confirm the full test suite passes.
2. Start Peers A, B, and C on ports 5000, 5001, and 5002.
3. From Peer B, run `put 127.0.0.1 5001 sample.txt`; normal replication should create matching copies on B and C.
4. Stop Peer C and wait for B's `status` to report C as `DEAD`.
5. Run `repair-status` on B. It should show the UUID as under-replicated and then report a successful repair to A.
6. Run `inventory` and confirm B and A have identical UUID, metadata, and SHA-256. Restart C and observe that Phase 5B does not delete or reconcile the recovered extra copy.

## Phase 5C peer recovery and reconciliation

`HeartbeatManager` publishes immutable peer-state transitions. `ReconciliationManager` listens specifically for `DEAD -> ALIVE`, queues an asynchronous inventory scan, and also performs periodic scans so a restart or missed event is eventually observed. It uses the Phase 5A `LIST_FILES` inventory, groups records by UUID, compares the complete `FileMetadata` record, and then verifies content integrity by streaming each candidate through `GET` and recalculating SHA-256. Matching metadata alone is never treated as a verified copy.

The deterministic placement policy is the lexicographically first two reachable peers whose content and metadata are verified. If three or more verified copies exist, the remaining peers are reported as `EXTRA_CANDIDATE` cleanup candidates. No files or metadata are deleted in Phase 5C. If fewer than two verified copies remain, a peer is unreachable, inventory is incomplete, metadata conflicts, or a preferred peer disappears during the final recheck, the result is `DEFERRED` or `CONFLICT` and the system preserves all existing data.

Repair and reconciliation use thread-safe per-UUID coordination. Reconciliation defers while a Phase 5B repair is active, and recovery events are coalesced so repeated `DEAD -> ALIVE` transitions do not create uncontrolled duplicate scans. There is no central coordinator; each peer can independently make the same deterministic read-only decision. A stale cross-process observation can therefore produce duplicate status work, but it cannot overwrite or delete a file. If a peer becomes unavailable again during reconciliation, the final inventory/health recheck marks the decision deferred and leaves every copy untouched.

Use the read-only CLI command below to inspect the current decision:

```text
reconcile-status
```

It displays each UUID's state, preferred replicas, extra cleanup candidates, metadata conflicts, unavailable peers, active reconciliations, and recent outcomes.

### Phase 5C manual three-peer demonstration

1. Start Peers A, B, and C on ports 5000, 5001, and 5002.
2. Upload a file to B. Confirm B and C contain matching UUID, metadata, and SHA-256.
3. Stop C and wait for B's `status` to report C as `DEAD`. Phase 5B repairs a copy to A.
4. Restart C using the same peer name and storage path. Wait for `DEAD -> ALIVE`.
5. Run `reconcile-status` on B. It should report preferred replicas `[A, B]` and C as an extra cleanup candidate when all three copies verify.
6. Confirm no content or metadata was deleted. If C becomes unavailable during the scan, the decision is deferred and all copies remain intact.

## Phase 6B automatic discovery and dynamic membership

Discovery is optional and disabled by default. A discovery-enabled configuration sets `clusterId`, `discoveryEnabled`, a multicast `discoveryGroup`/`discoveryPort`, and a shared `discoverySecret`. The example files `config\examples\discovery-lan-A.json` and `config\examples\discovery-lan-B.json` each start with only their local peer listed; they can discover each other and later peers without manually adding every address to every JSON file. Static configurations still require at least two peers.

Each JOIN is a bounded JSON multicast datagram containing the peer ID, advertised address, TCP port, cluster ID, timestamp, nonce, and an HMAC-SHA-256 signature. A JOIN is accepted only when the cluster ID, signature, timestamp window, endpoint, and peer identity are valid. Secrets are never printed or included in status output. Messages are limited to 4 KiB, timestamps must be within 30 seconds, and each source/peer identity is rate-limited to 30 messages per minute. Keep the shared secret out of source control and protect the configuration file with normal Windows file permissions; the example secret must be replaced before real use.

`PeerRegistry` deduplicates by stable peer ID, updates an authenticated peer's address when its IP changes, and tracks `ALIVE`, `SUSPECTED`, and `DEAD` states from heartbeat transitions. Replication, heartbeat, inventory, self-healing, and reconciliation read current registry snapshots. Replica placement remains deterministic and replication factor remains 2. Discovery only changes membership; it does not transfer files or delete files. The existing peer TCP protocol has no separate file-access authorization layer, so treat every authenticated cluster member as trusted for the existing peer operations. Discovery authentication must not be mistaken for end-to-end file authorization.

Useful CLI commands:

```text
peers
discovery-status
```

`peers` shows configured and discovered identities, current endpoint, and heartbeat state. `discovery-status` shows counters and the multicast group without revealing the secret. A discovered peer remains in the registry when it becomes `DEAD`; this prevents temporary partitions or restarts from causing unsafe removal, and its files are never deleted automatically.

### Two-laptop discovery test on Windows

1. On each laptop, run `ipconfig` and record the private Wi-Fi IPv4 address. Set the local `advertisedAddress` and peer TCP port in its discovery configuration. Use the same `clusterId`, `discoveryGroup`, `discoveryPort`, and random secret in both files.
2. Allow the TCP port only from the trusted private subnet, as described in the Phase 6A firewall section. Also allow the discovery UDP port from that subnet if Windows Firewall blocks multicast. Do not disable Windows Firewall or use a public-interface rule.
3. Start A with `config\examples\discovery-lan-A.json` and B with `config\examples\discovery-lan-B.json`:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=config\examples\discovery-lan-A.json'
.\mvnw.cmd exec:java '-Dexec.mainClass=storage.app.PeerNode' '-Dexec.args=config\examples\discovery-lan-B.json'
```

4. Run `discovery-status` and `peers` on both laptops. Each should show discovery running and the authenticated peer in the registry. Use `Test-NetConnection <other-private-ip> -Port <tcp-port>` to verify TCP reachability.
5. Start a third laptop with a new peer ID, its own advertised IP/port, the same cluster ID/secret, and an initial `peers` list containing at least itself and one existing peer. Its JOIN should appear in `peers` on authenticated cluster members. Upload a file and verify the existing replication, inventory, repair, and reconciliation behavior.

Discovery sends the signed JOIN over multicast, configured peer addresses, and the selected interface's directed broadcast. The unicast/broadcast copies are a fallback for Windows or Wi-Fi networks that do not forward multicast; they still require the UDP discovery port to be allowed by the Windows Private-network firewall. Configure every device's `peers` list with its own endpoint and the endpoints of the devices that should act as discovery seeds. Wi-Fi networks that isolate clients, filter all UDP, or use captive portals/VLANs still require the Phase 6A static JSON configuration.

This fallback is ordinary IP discovery, not Bluetooth discovery. Windows devices can participate in the mesh over TCP and can discover Linux/Windows peers through the signed JOIN path, but this project does not yet implement Windows Bluetooth RFCOMM, Bluetooth service records, Wi-Fi Direct advertisements, or Mobile Hotspot/Windows Runtime integration. Those features need a Windows-specific transport adapter and real Windows hardware testing.
