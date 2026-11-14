# File sharing over the mesh

Code: `backend/src/main/java/storage/transfer/`. A file can reach a device that is **not directly connected**:
it travels hop by hop along the routes found by the router (see [routing.md](routing.md)).

## Protocol (one transfer)
| Step | Messages | What happens |
|---|---|---|
| 1 | `FILE_OFFER` -> `FILE_ACCEPT` | Sender offers the file (metadata, chunk size). The receiver accepts, rejects (not addressed to it, busy) or reports **which chunks it already has** |
| 2 | `FILE_CHUNK` <-> `FILE_CHUNK_ACK` | 16 KiB chunks, up to 8 unacknowledged at once; a chunk with no ACK for 2 s is sent again |
| 3 | `FILE_DONE` | The receiver checks the whole-file **SHA-256**, commits the file into its `FileStore`, and reports success or the reason |

* **Route breaks need no special code.** Chunks are ordinary routed messages; when a relay dies the router
  rediscovers a path and the unacknowledged chunks are retransmitted over it.
* **Stalls are visible and resumable.** If nothing is acknowledged for the stall timeout (45 s) the transfer ends
  `FAILED` ("route unavailable; can be resumed"), keeps its staged file, and `resume` continues from the chunks
  the receiver already holds. Real run: a relay was stopped at 32 % (235 of 733 chunks); after it returned,
  the resume sent only the 490 missing chunks.
* **Integrity and idempotence.** Wrong hash => refused and the partial file deleted. Sending a file the receiver already
  stores is recognised (`ALREADY_HAVE`) and needs no chunks.
* **Bounds.** Max 8 concurrent incoming transfers, at most 65 536 chunks, partial files idle for 30 min are dropped,
  leftovers from an earlier run are deleted at start (transfer state lives in memory, so a restart cannot resume).

## Using it
* **UI:** *Share files* -> choose a person. A recipient that is directly registered uses the fast TCP path; a recipient that
  is only reachable through the mesh (neighbour or routed destination) is sent over the mesh and shows live progress,
  *Waiting for a route*, and a **Resume** button.
* **Fallback:** if the direct TCP connection to a registered peer fails, the file is sent over the mesh instead
  (also for automatic replicas: `ReplicationManager` reports `ROUTED_IN_PROGRESS`).
* **REST:** `POST /api/files?recipient=<id>` (202 `TRANSFERRING` + transfer ids), `GET /api/mesh/transfers`,
  `GET /api/mesh/transfers/{id}`, `POST /api/mesh/transfers/{id}/resume`.
* **Console:** `send-file <device-id> <path>`, `transfers`; received files print `[file] received ...`.
* **Tuning:** `-Dmesh.chunkIntervalMillis=<pause between chunks>` (rate limit for slow radios),
  `-Dmesh.stallSeconds=<seconds>`.

## Limitations
* Self-healing and reconciliation of stored files still use the direct IP inventory between registered peers; only
  *sending* and the replica fallback are routed.
* One thread per active outgoing transfer; 100 MB is the file size limit.
* No application-level encryption (dropped from the plan).
