# Share files with chosen recipients

Relay sends files to recipients selected by the user. There is no automatic backup replication or redistribution when a device disappears.

In Share files, choose files and one or multiple recipients, then send. A file delivered to another device appears in that recipient's local Files list. The sender's original remains on their computer, but the application does not create a permanent received-file copy on the sender for a different single recipient.

The API accepts `POST /api/files?recipient=C` or `?recipient=all`. All takes a snapshot of currently registered ALIVE peers and this device. Later arrivals receive nothing from earlier shares. The UI supports selecting multiple people individually. These are intentional deliveries, not backups.

The backend uses direct TCP `PUT` for registered peers and falls back to routed mesh transfer if direct delivery fails. Mesh-only recipients can be selected once known by the mesh. Direct success is confirmed by the receiving peer. Mesh starts return 202 with transfer IDs; follow progress until DELIVERED or FAILED. Partial fan-out reports successful recipients and individual failures. An unreachable recipient is never reported as delivered; failed mesh transfers may be manually resumed while staging/state remain available.

Selected recipient IDs remain in metadata, and receiving peers reject files addressed to other devices. This is placement validation. Application entry points now require mesh-password membership and TLS for direct TCP and mesh carriers; there is no individual-member ACL or end-to-end encryption against other mesh members.

`GET /api/files` lists only local complete files. `GET /api/files/{id}/download` downloads the local file. `DELETE /api/files/{id}` removes only this device's copy; other recipients retain theirs. No background service restores a deleted file.

Uploads without a recipient are local-only saves, retained for compatibility. Neither those uploads nor direct console `put` operations trigger additional deliveries. The old `REPLICA_PUT` command remains an alias for single-device `PUT`, with the same no-overwrite checks. New clients use `PUT`.

Existing data and metadata remain readable and are not deleted during upgrade. Update all devices together, because old backends may still replicate an ordinary upload. REST responses no longer contain `replicaCount` or `replicationStatus`; sharing results keep `deliveredTo`, `failedPeers`, `transfers`, and `shareStatus`.
