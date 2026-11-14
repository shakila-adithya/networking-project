# Share files with a chosen peer or all connected peers

Update and restart Java on **every device** before using recipient-based sharing. Older versions reject the new optional metadata field; failed deliveries are reported rather than sent using legacy ring placement.

In Files, select **Share with → Peer C** to send the selected files to C only. Choose **All connected peers (including this device)** to send to the currently registered ALIVE peers and keep a copy locally. Preview the files, check the recipient label, then confirm Upload. File storage appears on the recipient's local Files page. The sender's original file stays on their computer; the application does not store an extra sender copy for a different single recipient.

Completed uploads disappear from the progress list. A partial delivery stays visible with successful recipients and per-peer failure messages. New peers joining later are not retroactive recipients. If a selected peer is offline, the share fails; reconnect the peer and retry. Delivery to all uses a snapshot, so the results may include failures if a device disconnects during transfer.

Explicit shares persist their selected recipient IDs in metadata. They use REPLICA_PUT to prevent ring replication, reject delivery to unselected peers, and are excluded from ordinary automatic replica repair/redistribution. Reconciliation reports SHARE_COMPLETE/SHARE_INCOMPLETE against that recipient list. A single-peer share intentionally has only one stored copy; losing it cannot be repaired from another peer. Failed shares are not automatically retried. Select all again to retry delivery (a fresh upload creates a new UUID).

Legacy uploads with no `recipient` query keep the existing replication factor 2 and ring placement. Existing stored files are unchanged. The frontend passes `POST /api/files?recipient=C` or `?recipient=all`; the setup agent proxies the query unchanged. These options choose storage placement, not user access authorization: the existing trusted peer protocol still has no per-user access control or TLS.
