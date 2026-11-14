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
* **Stalls are visible and resumable.** Without custody, a stall ends `FAILED` and allows manual resume. With secure custody enabled it waits for a later contact. Chunk acknowledgements, source journals and receiver bitmaps allow missing chunks to resume, including after a process restart. Final delivery requires a verified `FILE_DONE`, not a relay custody acknowledgement.
* **Integrity and idempotence.** Wrong hash => refused and the partial file deleted. Sending a file the receiver already
  stores is recognised (`ALREADY_HAVE`) and needs no chunks.
* **Bounds.** Max 8 concurrent incoming transfers and 65,536 chunks. Recipient partial files expire after 24 hours idle. Source staging is retained until final delivery and is bounded to 4 GiB / 200 pending transfers. Startup preserves journals and unknown existing staging rather than deleting user data.
* **Temporary relay custody.** When an OS-protected key is available, encrypted chunks can cross separate contacts. Relay defaults are 1 GB / 24 hours, adjustable in Settings. Full custody storage rejects new data; expiry affects temporary relay records, while the sender retains its original. See [implementation status](offline-mesh-status.md) for key-store requirements and limitations.


## Using it
* **UI:** *Share files* -> choose a person. A recipient that is directly registered uses the fast TCP path; a recipient that
  is only reachable through the mesh (neighbour or routed destination) is sent over the mesh and shows live progress,
  *Waiting for a route*, and a **Resume** button.
* **Fallback:** if the direct TCP connection to a registered peer fails, the file is sent over the mesh instead
  Only user-requested deliveries use this fallback; received files are never automatically replicated.
* **REST:** `POST /api/files?recipient=<id>` (202 `TRANSFERRING` + transfer ids), `GET /api/mesh/transfers`,
  `GET /api/mesh/transfers/{id}`, `POST /api/mesh/transfers/{id}/resume`.
* **Console:** `send-file <device-id> <path>`, `transfers`; received files print `[file] received ...`.
* **Tuning:** `-Dmesh.chunkIntervalMillis=<pause between chunks>` (rate limit for slow radios),
  `-Dmesh.stallSeconds=<seconds>`.

## Limitations
* Network routing and connection recovery do not create additional stored copies. Only the intended recipient commits a received file; intermediate relays may temporarily retain encrypted custody records within the configured limits.
* One thread per active outgoing transfer; browser sends are limited to 100 MiB. The backend allows up to 2 GiB, with an additional 65,536-chunk bound (1 GiB at the default 16 KiB chunk size).
* Application carriers use mesh-password authentication and hop-by-hop TLS authenticated encryption. Trusted relays can inspect forwarded plaintext; this is not end-to-end encryption against members.
