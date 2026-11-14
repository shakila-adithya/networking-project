# Routing

The mesh uses **AODV** (on-demand distance-vector routing) with a pluggable link-cost metric,
in the style of the 802.11s HWMP protocol. Code: `storage.routing`.

## Why this algorithm
Nodes join and leave constantly, run on batteries, and have few radio links each. Link-state
(Dijkstra) and proactive distance-vector (Bellman-Ford) need periodic network-wide updates even when
nobody is talking; slow convergence after a node loss is the exact case the mesh must handle. RPL
assumes a root; flooding everything wastes battery and cannot carry files. AODV spends radio time
only when someone sends, is loop-free through sequence numbers, and has explicit failure handling.

## How it works
| Event | Behaviour |
|---|---|
| Send to unknown destination | Buffer the message, flood a **RREQ** (TTL-bounded, up to 3 attempts) |
| RREQ arrives | Record a reverse route to the originator; re-flood only if this copy is **strictly cheaper** than one already handled |
| Destination receives RREQ | Sends a **RREP** back along the reverse path; each hop installs the forward route (cost accumulates) |
| Better RREQ copy arrives later | Destination replies again; same sequence + lower cost replaces the route |
| Neighbour link lost | Routes through it become invalid (sequence +1); **RERR** goes to the neighbours that used them and is relayed upstream |
| Relay gets data with no route | Buffers it and discovers a new path (local repair); if that fails it sends a RERR to the previous hop |
| No path exists | Discovery gives up; the chat ends `FAILED`. Delivery is never claimed for a partitioned network |

Only the destination answers a RREQ, so a stale cached route cannot answer for a dead node.
Routes expire after 60 s without use. Broadcasts stay flooded (TTL + duplicate cache).

## Link cost
`LinkCost.cost(neighbour)` returns the cost of the direct link; routes minimise the sum.
The runtime currently uses **hop count**. Measured quality (RTT, loss, signal strength) is
not implemented yet; it plugs in through this interface once the Bluetooth transport provides it.

## Limitations
- Link costs are assumed symmetric.
- A direct neighbour is not given a special route, so a first message to a neighbour also runs a
  (one-hop) discovery.
- Tested on the in-memory simulation and on real processes over TCP. Not tested on Bluetooth.
