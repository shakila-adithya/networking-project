"use client";
import { useRef, useState } from "react";
import Link from "next/link";
import { useMesh, Person, transportLabel } from "@/lib/mesh";
import { Icon, Eyebrow, Badge, Search } from "./ui";
import { MeshMembership } from "./mesh-membership";
import { MapViewport, type MapCamera } from "./map-viewport";
import { ConnectionGlyph } from "./connection-glyph";
import { VisibilityCard, ConnectCard } from "./nearby";
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
  const { preview, peers, neighbours, localId, links, error, bluetooth, device, nearby } = useMesh(),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState<Person | null>(null),
    [selectedSelf, setSelectedSelf] = useState(false);
  const camera = useRef<MapCamera>(null);
  const clearSelection = () => { setSelected(null); setSelectedSelf(false); };
  // Relay devices that were found nearby but are not connected yet appear as muted "discovered" nodes.
  const candidates = nearby.items.filter(item => item.relay && item.deviceId && !item.peerConnected
    && item.state !== "CONNECTED" && !peers.some(peer => peer.id === item.deviceId));
  const discovered: Person[] = [...new Set(candidates.map(item => item.deviceId as string))].map(id => {
    const addresses = candidates.filter(item => item.deviceId === id);
    const methods = [...new Set(addresses.map(item => ["lan", "tcp"].includes(item.kind) ? "Wi-Fi / LAN" : transportLabel(item.kind)))];
    return { id, name: addresses[0].name, detail: `Nearby · ${methods.join(" + ")}`, tone: "muted", status: "DISCOVERED" };
  });
  const discoveredCount = new Set([
    ...nearby.items.filter(item => item.relay && !item.peerConnected && item.state !== "CONNECTED" && item.deviceId).map(item => item.deviceId),
    ...(bluetooth?.nearby || []).filter(item => item.relayDevice && !neighbours.includes(item.relayDevice)).map(item => item.relayDevice),
  ]).size;
  const around = [...peers, ...discovered];
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
  return (
    <section className="map-page">
      <div className="map-heading">
        <Eyebrow gold>{preview ? "DESIGN PREVIEW" : "YOUR NETWORK"}</Eyebrow>
        <h1>Your nearby devices</h1>
        <p>Chat and share files using Bluetooth, Wi-Fi, Ethernet or a hotspot.</p>
        <div className="badge-row">
          <Badge>
            {preview
              ? "EXAMPLE DEVICES"
              : neighbours.length
                ? "Connected to nearby devices"
                : "Not connected to other devices"}
          </Badge>
          <Badge tone="cyan">
            {preview ? "Sample connections" : "No internet needed"}
          </Badge>
        </div>
        <Search
          value={search}
          onChange={setSearch}
          placeholder="Find a device"
          icon="search-icon-3"
        />
        {search.trim() && <div className="map-search-results" role="group" aria-label="Found devices">
          {nodes.filter(p => p.name.toLowerCase().includes(search.trim().toLowerCase()) || p.id.toLowerCase().includes(search.trim().toLowerCase())).map(p => <button className="button" key={p.id} onClick={() => { setSelected(p); setSelectedSelf(p.id === selfNode.id); camera.current?.centerOn(p.x!, p.y!); }}><span>{p.name}</span><small>{p.detail}</small><span aria-hidden="true">↗</span></button>)}
          {!nodes.some(p => p.name.toLowerCase().includes(search.trim().toLowerCase()) || p.id.toLowerCase().includes(search.trim().toLowerCase())) && <p>No devices found. Try another name.</p>}
        </div>}
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
              onClick={() => { setSelected(p); setSelectedSelf(p.id === selfNode.id); }}
              aria-label={`Inspect ${p.name}`}
            >
              <span className="device-tile">
                <Icon
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
                />
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
      <div className="map-key" aria-label="Map key">
        <div className="map-key-items">
          {(preview
            ? [["gold-text", "Direct link"], ["cyan", "Via another device"], ["green", "Alternative route"], ["red", "Link unavailable"], ["muted", "Nearby device"]]
            : [["gold-text", "Direct link"], ["muted", "Nearby device"]]
          ).map(([tone, label]) => <span key={label}><i className={tone} aria-hidden="true" />{label}</span>)}
        </div>
        <p className="map-key-note">Shows connections, not exact locations.</p>
      </div>
      </div>
      <aside className="network-inspector">
        <div className="row between">
          <Eyebrow>{selected ? "DEVICE DETAILS" : "NETWORK OVERVIEW"}</Eyebrow>
          {selected && <button className="button inspector-back" aria-label="Reset inspector" onClick={() => { setSelected(null); setSelectedSelf(false); }}>Overview</button>}
        </div>
        <div className="mesh-identity">
          <span className="overview-symbol"><ConnectionGlyph kind="mesh" /></span>
          <div>
            <h2>{selected?.name || "Your connections"}</h2>
            <p>
              {selected?.detail ||
                (preview
                  ? "Example of a mixed-transport mesh"
                  : neighbours.length ? `Active links: ${activeMethods}` : error ? "Local service unavailable" : "Choose a method to connect nearby devices")}
            </p>
          </div>
        </div>
        <div className="badge-row">
          <Badge tone={preview || neighbours.length ? "green" : "muted"}>{selected?.status || (preview ? "Preview mesh" : neighbours.length ? "Connected" : "No active links")}</Badge>
          <Badge tone="cyan">{preview ? "Preview only" : "Offline mesh"}</Badge>
        </div>
        {selectedIsSelf && <VisibilityCard />}
        {selectedIsDiscovered && <ConnectCard id={selected!.id} fallbackName={selected!.name} />}
        <hr />
        <div className="network-metrics">
          <div><Eyebrow>DIRECT LINKS</Eyebrow><strong>{preview ? 3 : neighbours.length}</strong><small>connected to you</small></div>
          <div><Eyebrow>VIA RELAYS</Eyebrow><strong>{preview ? 2 : peers.filter(p => p.status === "ALIVE" && p.detail.startsWith("Via ")).length}</strong><small>reachable devices</small></div>
          <div className="discovery-summary"><i className="status-dot cyan" /><span>{preview ? "1 Relay device nearby" : `${discoveredCount} Relay device${discoveredCount === 1 ? "" : "s"} discovered`}</span></div>
        </div>
        <MeshMembership showPeers={!selected} />
        {preview && <details className="network-history"><summary>Preview network events</summary>
          <div className="events">{[["Route restored", "Kasun ↔ Ravi"], ["Link unavailable", "Nimal ↔ Peer 07"]].map(([title, detail]) => <div className="event" key={title}><i className="status-dot green" /><div><b>{title}</b><small>{detail}</small></div></div>)}</div>
        </details>}
        {selected && (
          <Link className="button" href="/messages/">
            Open messages
          </Link>
        )}
      </aside>


    </section>
  );
}
