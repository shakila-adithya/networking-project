"use client";
import { useRef, useState } from "react";
import Link from "next/link";
import { useMesh, Person, transportLabel } from "@/lib/mesh";
import { Icon, Eyebrow, Badge, Search } from "./ui";
import { MeshMembership } from "./mesh-membership";
import { MapViewport, type MapCamera } from "./map-viewport";
import { ConnectionGlyph } from "./connection-glyph";
import { useNetworkDiscovery, MapVisibility } from "./network-discovery";
import { ConnectCard } from "./nearby";
const sample: Person[] = [
  {
    id: "You",
    name: "You",
    detail: "This device",
    tone: "gold-text",
    status: "ALIVE",
    x: 400,
    y: 335,
  },
  {
    id: "M",
    name: "Maya",
    detail: "Direct connection",
    tone: "green",
    status: "ALIVE",
    x: 310,
    y: 210,
  },
  {
    id: "N",
    name: "Nimal",
    detail: "Direct connection",
    tone: "green",
    status: "ALIVE",
    x: 650,
    y: 275,
  },
  {
    id: "A",
    name: "Amaya",
    detail: "Via Maya · 2 hops",
    tone: "cyan",
    status: "ALIVE",
    x: 480,
    y: 95,
  },
  {
    id: "R",
    name: "Ravi",
    detail: "Via Nimal · 2 hops",
    tone: "cyan",
    status: "ALIVE",
    x: 835,
    y: 445,
  },
  {
    id: "K",
    name: "Kasun",
    detail: "Direct connection",
    tone: "green",
    status: "ALIVE",
    x: 420,
    y: 580,
  },
  {
    id: "07",
    name: "Peer 07",
    detail: "Unavailable",
    tone: "red",
    status: "DEAD",
    x: 845,
    y: 235,
  },
  {
    id: "Z",
    name: "Zara",
    detail: "Discovered",
    tone: "muted",
    status: "DISCOVERED",
    x: 160,
    y: 480,
  },
];
export function NetworkMap() {
  const { preview, peers, neighbours, localId, links, graph, custody, error, bluetooth, device, nearby } = useMesh(),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState<Person | null>(null),
    [selectedSelf, setSelectedSelf] = useState(false);
  const camera = useRef<MapCamera>(null);
  const discovery = useNetworkDiscovery();
  const [joinRequest, setJoinRequest] = useState<{ meshId: string; sequence: number }>();
  const [startRequest, setStartRequest] = useState<{ name: string; sequence: number }>();
  const clearSelection = () => { setSelected(null); setSelectedSelf(false); };
  // Relay devices that were found nearby but are not connected yet appear as muted "discovered" nodes.
  const candidates = nearby.items.filter(item => item.relay && item.deviceId && !item.peerConnected
    && item.state !== "CONNECTED" && !peers.some(peer => peer.id === item.deviceId));
  const discovered: Person[] = [...new Set(candidates.map(item => item.deviceId as string))].map(id => {
    const addresses = candidates.filter(item => item.deviceId === id);
    const methods = [...new Set(addresses.map(item => ["lan", "tcp"].includes(item.kind) ? "Wi-Fi / LAN" : transportLabel(item.kind)))];
    return { id, name: addresses[0].name, detail: `Nearby · ${methods.join(" + ")}`, tone: "muted", status: "DISCOVERED" };
  });
  const publicDevices: Person[] = [...new Set(discovery.devices.map(d => d.deviceId))]
    .filter(id => id !== localId && id !== discovery.visibility?.deviceId && !peers.some(p => p.id === id) && !discovered.some(p => p.id === id))
    .map(id => { const d = discovery.devices.find(d => d.deviceId === id)!; return { id, name: d.name, detail: d.meshId ? `Unverified · ${d.meshId}` : "Unverified device · Not in a mesh", tone: "muted", status: "DISCOVERED" }; });
  const meshNodes: Person[] = [...new Set(discovery.meshes.map(m => m.meshId))].map(id => ({ id: `mesh/${id}`, name: id, detail: "Unverified mesh · Select to join", tone: "gold-text", status: "MESH" }));
  const around = [...peers, ...discovered, ...publicDevices, ...meshNodes];
  const nodes = preview
    ? sample
    : [
        {
          id: localId,
          name: device?.customName || "You",
          detail: "This device",
          tone: "gold-text",
          status: "ALIVE",
          x: 470,
          y: 350,
        },
        ...around.map((p, i) => ({
          ...p,
          x:
            470 + Math.cos((i * 2 * Math.PI) / Math.max(around.length, 1)) * 290,
          y:
            350 + Math.sin((i * 2 * Math.PI) / Math.max(around.length, 1)) * 240,
        })),
      ];
  const activeMethods = [...new Set(Object.values(links))].map(transportLabel).join(" · ");
  const selfNode = nodes[0];
  function inspect(p: Person) {
    setSelected(p); setSelectedSelf(p.id === selfNode.id);
  }
  const selectedPublic = discovery.devices.find(d => d.deviceId === selected?.id);
  const selectedIsSelf = !!selected && selectedSelf;
  const selectedIsDiscovered = !!selected && selected.status === "DISCOVERED";
  const edges = preview
    ? [
        [0, 1, "gold"],
        [0, 2, "gold"],
        [0, 5, "gold"],
        [1, 3, "cyan"],
        [2, 4, "cyan"],
        [4, 5, "green"],
        [2, 6, "red"],
        [0, 7, "muted"],
      ]
    : nodes
        .slice(1)
        .filter((p) => neighbours.includes(p.id))
        .map((p) => [0, nodes.indexOf(p), "gold"]);
  if (!preview) {
    const existing = new Set(edges.map(([a,b]) => [nodes[Number(a)].id, nodes[Number(b)].id].sort().join("/")));
    for (const member of graph) for (const peer of Object.keys(member.links)) {
      const a = nodes.findIndex(n => n.id === member.deviceId), b = nodes.findIndex(n => n.id === peer);
      const key = [member.deviceId, peer].sort().join("/");
      if (a >= 0 && b >= 0 && !existing.has(key)) { edges.push([a,b,member.reachable ? "cyan" : "muted"]); existing.add(key); }
    }
  }
  return (
    <section className="map-page">
      <div className="map-heading">
        <Eyebrow gold>{preview ? "DESIGN PREVIEW" : "NETWORK MAP"}</Eyebrow>
        <h1>Your Relay network</h1>
        <p>This map shows you and every Relay device around you. Pick how this device connects, press Scan nearby, then select a device or mesh on the map to join it. Chat and share files with no internet.</p>
        <div className="map-heading-meta">
        <div className="badge-row">
          {(preview || neighbours.length > 0) && <Badge>
            {preview
              ? "EXAMPLE DEVICES"
              : `${neighbours.length} device${neighbours.length === 1 ? "" : "s"} connected to you`}
          </Badge>}
          <Badge tone="cyan">
            {preview ? "Sample connections" : "No internet needed"}
          </Badge>
        </div>
        <ol className="map-heading-steps" aria-label="How to connect">
          <li><b>1</b> Choose a connection</li>
          <li><b>2</b> Scan nearby</li>
          <li><b>3</b> Select and join</li>
        </ol>
        </div>
        <Search
          value={search}
          onChange={setSearch}
          placeholder="Find a device"
          icon="search-icon-3"
        />
        {search.trim() && <div className="map-search-results" role="group" aria-label="Found devices">
          {nodes.filter(p => p.name.toLowerCase().includes(search.trim().toLowerCase()) || p.id.toLowerCase().includes(search.trim().toLowerCase())).map(p => <button className="button" key={p.id} onClick={() => { inspect(p); camera.current?.centerOn(p.x!, p.y!); }}><span>{p.name}</span><small>{p.detail}</small><span aria-hidden="true">↗</span></button>)}
          {!nodes.some(p => p.name.toLowerCase().includes(search.trim().toLowerCase()) || p.id.toLowerCase().includes(search.trim().toLowerCase())) && <p>No devices found. Try another name.</p>}
        </div>}
        <div className="map-key" aria-label="Map key">
          <div className="map-key-items">
            {(preview
              ? [["gold-text", "Direct link"], ["cyan", "Via another device"], ["green", "Alternative route"], ["red", "Link unavailable"], ["muted", "Nearby device"]]
              : [["gold-text", "Direct link"], ["cyan", "Via another device"], ["muted", "Nearby device"]]
            ).map(([tone, label]) => <span key={label}><i className={tone} aria-hidden="true" />{label}</span>)}
          </div>
          <p className="map-key-note">Shows connections, not exact locations.</p>
        </div>
      </div>
      <div className="map-area">
      <MapViewport key={preview ? "preview" : "live"} cameraRef={camera} self={{ x: selfNode.x!, y: selfNode.y! }} onClear={clearSelection}>
          <div className="orbit-grid" />
          <svg
            className="connections"
            viewBox="0 0 1050 760"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            {edges.map(([a, b, tone], i) => {
              const one = nodes[Number(a)],
                two = nodes[Number(b)];
              return (
                <g key={i} className={`edge ${tone}`}>
                  <path
                    d={`M ${one.x} ${one.y} Q ${(one.x! + two.x!) / 2 + 35} ${(one.y! + two.y!) / 2 - 25} ${two.x} ${two.y}`}
                  />
                  {preview && tone !== "muted" && tone !== "red" && (
                    <circle r="3">
                      <animateMotion
                        dur={`${4 + i}s`}
                        repeatCount="indefinite"
                        path={`M ${one.x} ${one.y} Q ${(one.x! + two.x!) / 2 + 35} ${(one.y! + two.y!) / 2 - 25} ${two.x} ${two.y}`}
                      />
                    </circle>
                  )}
                </g>
              );
            })}
          </svg>
          {nodes.map((p, i) => (
            <button
              key={p.id}
              aria-pressed={selected?.id === p.id}
              className={`map-node ${p.tone} ${i === 0 ? "self" : ""} ${search.trim() && !p.name.toLowerCase().includes(search.trim().toLowerCase()) && !p.id.toLowerCase().includes(search.trim().toLowerCase()) ? "dim" : ""}`}
              style={{
                left: `${(p.x! / 1050) * 100}%`,
                top: `${(p.y! / 760) * 100}%`,
              }}
              onClick={() => inspect(p)}
              aria-label={`Inspect ${p.name}`}
            >
              <span className="device-tile">
                {p.status === "MESH" ? <ConnectionGlyph kind="mesh" /> : <Icon
                  name={
                    i === 0
                      ? "phone-icon"
                      : p.name === "Ravi"
                        ? "laptop-icon"
                        : p.tone === "red"
                          ? "phone-icon-4"
                          : p.tone === "muted"
                            ? "phone-icon-5"
                            : p.tone === "cyan"
                              ? "phone-icon-3"
                              : "phone-icon-2"
                  }
                />}
                <i />
              </span>
              <b>{p.name}</b>
              <small>{p.detail}</small>
            </button>
          ))}
          {preview && (
            <>
              <div className="traffic-label message-label cyan">
                <Icon name="chat-icon-4" />
                Message · 2 hops
              </div>
              <div className="traffic-label file-label gold-text">
                <Icon name="file-icon-6" />
                Outgoing file · 68%
              </div>
              <div className="traffic-label route-label green">
                <Icon name="refresh-icon-4" />
                Alternative route
              </div>
              <div className="traffic-label lost-label red">
                <Icon name="signal-icon" />
                Link lost
              </div>
            </>
          )}
      </MapViewport>
      </div>
      <aside className="network-inspector">
        <MeshMembership joinRequest={joinRequest} startRequest={startRequest} onDiscoveryRefresh={discovery.reload} overview={<>
        <MapVisibility discovery={discovery} />
        {selected && <div className="row between">
          <Eyebrow>{selected.status === "MESH" ? "MESH" : "DEVICE DETAILS"}</Eyebrow>
          <button className="button inspector-back" aria-label="Reset inspector" onClick={() => { setSelected(null); setSelectedSelf(false); }}>Close</button>
        </div>}
        {selected && <div className="mesh-identity">
          <span className="overview-symbol"><ConnectionGlyph kind="mesh" /></span>
          <div>
            <h2>{selected?.name || "Your connections"}</h2>
            <p>
              {selected?.detail ||
                (preview
                  ? "Example of a mixed-transport mesh"
                  : neighbours.length ? `Active links: ${activeMethods}` : error ? "See mesh status below" : "Choose a method to connect nearby devices")}
            </p>
          </div>
        </div>}
        {selected && <div className="badge-row">
          <Badge tone={preview || neighbours.length ? "green" : "muted"}>{selected?.status || (preview ? "Preview mesh" : neighbours.length ? "Connected" : "No active links")}</Badge>
          <Badge tone="cyan">{preview ? "Preview only" : "Offline mesh"}</Badge>
        </div>}
        {selectedIsSelf && <p className="hint">Use the visibility switch in the Your device card. Hiding this device also leaves the mesh.</p>}
        {selected?.status === "MESH" && <><p className="hint">This mesh name is unverified until its password is accepted.</p><button className="button primary" onClick={() => setJoinRequest({ meshId: selected.name, sequence: Date.now() })}>Join {selected.name}</button></>}
        {selectedIsDiscovered && (selectedPublic ? <><p className="hint">This name is unverified until password authentication succeeds. {selectedPublic.meshId ? `This device is in ${selectedPublic.meshId}.` : "This Relay device is not in a mesh yet. The first connection generates a password for the new mesh."}</p>{selectedPublic.meshId ? <button className="button primary" onClick={() => setJoinRequest({ meshId: selectedPublic.meshId, sequence: Date.now() })}>Join {selectedPublic.meshId}</button> : <button className="button primary" onClick={() => setStartRequest({ name: selectedPublic.name || selectedPublic.deviceId, sequence: Date.now() })}>Connect</button>}</> : <ConnectCard id={selected!.id} fallbackName={selected!.name} />)}
        {!preview && custody && (custody.error || (custody.pending || 0) > 0) && <div className="mesh-custody-status"><b>{custody.error ? "Forwarding needs attention" : `${custody.pending} items waiting to forward`}</b>{custody.error && <p>{custody.error}</p>}<Link href="/settings/">Temporary storage settings</Link></div>}
        </>} />
        {preview && <details className="network-history"><summary>Preview network events</summary>
          <div className="events">{[["Route restored", "Kasun ↔ Ravi"], ["Link unavailable", "Nimal ↔ Peer 07"]].map(([title, detail]) => <div className="event" key={title}><i className="status-dot green" /><div><b>{title}</b><small>{detail}</small></div></div>)}</div>
        </details>}
        {selected && !["DISCOVERED", "MESH"].includes(selected.status) && (
          <Link className="button" href="/messages/">
            Open messages
          </Link>
        )}
      </aside>
    </section>
  );
}
