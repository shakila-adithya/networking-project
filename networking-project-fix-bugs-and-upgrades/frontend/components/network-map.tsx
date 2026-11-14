"use client";
import { useState } from "react";
import Link from "next/link";
import { useMesh, Person } from "@/lib/mesh";
import { Icon, Eyebrow, Badge, Search } from "./ui";
import { NearbyPanel, VisibilityCard, ConnectCard } from "./nearby";
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
  const { preview, peers, neighbours, localId, transport, bluetooth, device, nearby } = useMesh(),
    [search, setSearch] = useState(""),
    [zoom, setZoom] = useState(1),
    [selectedId, setSelectedId] = useState<string | null>(null);
  // Relay devices that were found nearby but are not connected yet appear as muted "discovered" nodes.
  const discovered: Person[] = nearby.items
    .filter((i) => i.relay && i.deviceId && i.state !== "CONNECTED" && !peers.some((p) => p.id === i.deviceId))
    .map((i) => ({
      id: i.deviceId as string,
      name: i.name,
      detail: `Nearby · ${i.kind === "bluetooth" ? "Bluetooth" : "LAN"}`,
      tone: "muted",
      status: "DISCOVERED",
    }));
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
  const selfNode = nodes[0];
  const selected = selectedId === "__self__"
    ? selfNode
    : nodes.find((node) => node.id === selectedId) ?? null;
  const selectedIsSelf = selectedId === "__self__";
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
        <Eyebrow gold>LIVE NETWORK TOPOLOGY</Eyebrow>
        <h1>
          The mesh,
          <br />
          in motion.
        </h1>
        <p>See how devices connect, relay and adapt.</p>
        <div className="badge-row">
          <Badge>
            {preview
              ? "MESH ACTIVE"
              : peers.length
                ? "MESH ACTIVE"
                : "LOCAL DEVICE"}
          </Badge>
          <Badge tone="cyan">
            {preview ? "Discovering nearby" : `${transport} connections`}
          </Badge>
        </div>
        <Search
          value={search}
          onChange={setSearch}
          placeholder="Find a device"
          icon="search-icon-3"
        />
      </div>
      <div className="topology">
        <div className="topology-inner" style={{ transform: `scale(${zoom})` }}>
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
              className={`map-node ${p.tone} ${i === 0 ? "self" : ""} ${search && !p.name.toLowerCase().includes(search.toLowerCase()) ? "dim" : ""}`}
              style={{
                left: `${(p.x! / 1050) * 100}%`,
                top: `${(p.y! / 760) * 100}%`,
              }}
              onClick={() => setSelectedId(i === 0 ? "__self__" : p.id)}
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
        </div>
      </div>
      <aside className="network-inspector">
        <div className="row between">
          <Eyebrow>{selected ? "DEVICE DETAILS" : "NETWORK OVERVIEW"}</Eyebrow>
          <button
            className="icon-button"
            aria-label="Reset inspector"
            onClick={() => setSelectedId(null)}
          >
            <Icon name="more-icon-3" />
          </button>
        </div>
        <div className="mesh-identity">
          <Icon name="bluetooth-mesh-icon" />
          <div>
            <h2>{selected?.name || "Offline mesh"}</h2>
            <p>
              {selected?.detail ||
                (preview
                  ? "Bluetooth connections"
                  : `${transport} connections`)}
            </p>
          </div>
        </div>
        <div className="badge-row">
          <Badge>{selected?.status || "Active"}</Badge>
          <Badge tone="cyan">{preview ? "Discovering" : "Local network"}</Badge>
        </div>
        {selectedIsSelf && <VisibilityCard />}
        {selectedIsDiscovered && <ConnectCard id={selected!.id} fallbackName={selected!.name} />}
        <hr />
        <div className="network-metrics">
          <div>
            <Eyebrow>CONNECTED</Eyebrow>
            <strong>
              {preview ? 6 : peers.filter((p) => p.status === "ALIVE").length}
            </strong>
            <small>devices</small>
          </div>
          <div>
            <Eyebrow>DIRECT LINKS</Eyebrow>
            <strong>{preview ? 3 : neighbours.length}</strong>
            <small>from you</small>
          </div>
          <div>
            <Eyebrow>DISCOVERED</Eyebrow>
            <b>{preview ? "1 nearby" : `${peers.length} peer${peers.length === 1 ? "" : "s"}`}</b>
            <small>
              {!preview && bluetooth
                ? `${bluetooth.nearby.length} Bluetooth device${bluetooth.nearby.length === 1 ? "" : "s"} nearby · ${bluetooth.nearby.filter((d) => d.relayDevice).length} running Relay`
                : "Not connected yet"}
            </small>
          </div>
          <div>
            <Eyebrow>REACHABLE</Eyebrow>
            <b>{preview ? "2 via relays" : "Not reported"}</b>
            <small>Multiple hops</small>
          </div>
        </div>
        {!selected && <NearbyPanel />}
        <Eyebrow>SELF-HEALING</Eyebrow>
        <div className="recovery">
          <Icon name="refresh-icon-5" />
          <div>
            <b>
              {preview ? "Alternative route available" : "Automatic recovery"}
            </b>
            <p>
              {preview ? (
                <>
                  A lost connection was bypassed
                  <br />
                  through another connected device.
                </>
              ) : (
                "Route recovery is managed by the local node."
              )}
            </p>
          </div>
        </div>
        <Eyebrow>RECENT NETWORK EVENTS</Eyebrow>
        <div className="events">
          {(preview
            ? [
                ["Route restored", "Kasun ↔ Ravi", "Now", "green"],
                ["Link unavailable", "Nimal ↔ Peer 07", "1m", "red"],
                ["Device discovered", "Nearby device found", "2m", "cyan"],
              ]
            : peers.map((p) => ["Peer " + p.status, p.name, "Live", p.tone])
          ).map(([title, desc, time, tone]) => (
            <div className="event" key={title + desc}>
              <i className={`status-dot ${tone}`} />
              <div>
                <b>{title}</b>
                <small>{desc}</small>
              </div>
              <time>{time}</time>
            </div>
          ))}
        </div>
        {selected && (
          <Link className="button" href="/messages/">
            Open messages
          </Link>
        )}
      </aside>
      <div className="map-bottom">
        <div className="legend">
          {[
            "Connected",
            "Forwarding",
            "New route",
            "Link lost",
            "Discovered",
          ].map((v, i) => (
            <span key={v}>
              <i
                className={["gold-text", "cyan", "green", "red", "muted"][i]}
              />
              {v}
            </span>
          ))}
        </div>
        <div className="map-tools">
          {[
            ["focus", "Reset zoom", () => setZoom(1)],
            [
              "plus",
              "Zoom in",
              () => setZoom((z) => Math.min(1.5, z + 0.1)),
            ],
            [
              "minus",
              "Zoom out",
              () => setZoom((z) => Math.max(0.7, z - 0.1)),
            ],
          ].map(([icon, label, fn]) => (
            <button
              key={String(icon)}
              aria-label={String(label)}
              onClick={fn as () => void}
            >
              <span className={`map-tool-glyph ${String(icon)}`} aria-hidden="true">
                {icon === "plus" ? "+" : icon === "minus" ? "−" : ""}
              </span>
            </button>
          ))}
        </div>
        <Eyebrow>TOPOLOGY VIEW · POSITIONS ARE ILLUSTRATIVE</Eyebrow>
      </div>
      {preview && (
        <div className="network-toast">
          <Icon name="refresh-icon-6" />
          <div>
            <b>Network adapted</b>
            <small>A connection was lost · another route is available</small>
          </div>
          <span>NOW</span>
        </div>
      )}
    </section>
  );
}
