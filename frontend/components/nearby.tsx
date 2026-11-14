"use client";
import { useState } from "react";
import { useMesh, request, NearbyItem } from "@/lib/mesh";
import type { PublicDevice } from "./network-discovery";
import { Eyebrow } from "./ui";

function SignalBars({ signal }: { signal: number | null }) {
  const level = signal === null ? 0 : signal >= 75 ? 4 : signal >= 50 ? 3 : signal >= 25 ? 2 : 1;
  return (
    <span
      className="signal-bars"
      role="img"
      aria-label={signal === null ? "Signal unknown" : `Signal ${signal} of 100`}
    >
      {[1, 2, 3, 4].map((n) => (
        <i key={n} className={n <= level ? "on" : ""} />
      ))}
    </span>
  );
}

const order = { CONNECTED: 0, AVAILABLE: 1, FAILED: 2, OTHER: 3 } as const;

function describe(item: NearbyItem) {
  const how = item.kind === "bluetooth" ? "Bluetooth" : item.kind === "wifi-direct" ? "Wi-Fi Direct / TCP" : item.kind === "hotspot" ? "Hotspot / TCP" : "LAN / TCP";
  if (!item.relay) return `${how} · not a Relay device`;
  const hops = item.hops ? ` · ${item.hops} hop${item.hops === 1 ? "" : "s"}` : "";
  return `${how} · ID ${item.deviceId}${hops}${item.state === "CONNECTED" ? "" : " · unverified candidate"}`;
}

/** Combined discovery results. Scanning is owned by the sidebar toolbar. */
export function NearbyPanel({ discoveredDevices = [] }: { discoveredDevices?: PublicDevice[] } = {}) {
  const { nearby, connectNearby } = useMesh();
  const [busy, setBusy] = useState<string | null>(null),
    [errors, setErrors] = useState<Record<string, string>>({}),
    [address, setAddress] = useState(""),
    [addressMessage, setAddressMessage] = useState("");
  const isScanning = nearby.scanning;
  const items = [...nearby.items].sort(
    (a, b) => order[a.state] - order[b.state] || (b.signal ?? -1) - (a.signal ?? -1),
  );
  async function connect(item: NearbyItem) {
    if (!item.deviceId) return;
    setBusy(item.deviceId);
    setErrors((e) => ({ ...e, [item.deviceId!]: "" }));
    try {
      await connectNearby(item.deviceId, item.address);
    } catch (e) {
      setErrors((x) => ({ ...x, [item.deviceId!]: (e as Error).message }));
    } finally {
      setBusy(null);
    }
  }
  async function connectAddress() {
    setAddressMessage("Connecting…");
    try {
      await request("/mesh/connect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ address: address.trim() }),
      });
      setAddressMessage("Connection started. It appears in the list when the link is up.");
      setAddress("");
    } catch (e) {
      setAddressMessage((e as Error).message);
    }
  }
  const actionable = items.filter((i) => i.relay);
  const others = items.filter((i) => !i.relay);
  const publicRows = [...new Set(discoveredDevices.map(item => item.deviceId))]
    .filter(id => !items.some(item => item.deviceId === id))
    .map(id => ({ device: discoveredDevices.find(item => item.deviceId === id)!, methods: [...new Set(discoveredDevices.filter(item => item.deviceId === id).map(item => item.kind === "bluetooth" ? "Bluetooth" : "Wi-Fi / LAN"))].join(" + ") }));
  const row = (item: NearbyItem) => (
    <div className={`nearby-item ${item.relay ? "" : "other"}`} key={item.address}>
      <SignalBars signal={item.signal} />
      <div className="info">
        <b title={item.name}>{item.name}</b>
        <small>{describe(item)}</small>
        {errors[item.deviceId ?? ""] || (item.state === "FAILED" && item.error) ? (
          <small className="field-error">{errors[item.deviceId ?? ""] || item.error}</small>
        ) : null}
      </div>
      {item.state === "CONNECTED" ? (
        <span className="state-connected">Connected</span>
      ) : item.peerConnected ? (
        <span title="This address is an alternative candidate. The active link is listed separately.">Alternative address</span>
      ) : item.relay ? (
        <button
          className="button"
          onClick={() => connect(item)}
          disabled={busy === item.deviceId}
          aria-label={`${item.state === "FAILED" ? "Retry connecting to" : "Connect to"} ${item.name}`}
        >
          {busy === item.deviceId ? "Connecting…" : item.state === "FAILED" ? "Retry" : "Connect"}
        </button>
      ) : null}
    </div>
  );
  return (
    <section className="nearby nearby-embedded" aria-label="Nearby devices">
      <div className="nearby-head"><Eyebrow>NEARBY DEVICES</Eyebrow></div>
      <small className="hint">{isScanning ? "Searching nearby connections…" : "Devices found across Bluetooth and your local network. Use Scan nearby above to search again."}</small>
      <div className="nearby-list">
        {actionable.map((item) => row(item))}
        {publicRows.map(({ device, methods }) => <div className="nearby-item" key={device.deviceId}><SignalBars signal={null}/><div className="info"><b>{device.name}</b><small>{methods} · Unverified</small><small>{device.meshId ? `Mesh: ${device.meshId}` : "Visible · Not in a mesh"}</small></div></div>)}
        {!actionable.length && !publicRows.length && (
          <p className="empty">
            No Relay devices found yet. Make the other device visible and select Scan nearby. For Wi-Fi or Ethernet, use the same local network.
          </p>
        )}
      </div>
      {others.length > 0 && (
        <details className="other-devices">
          <summary>
            Other Bluetooth devices ({others.length}) · not running Relay
          </summary>
          <div className="nearby-list">{others.map((item) => row(item))}</div>
        </details>
      )}
      <details className="by-address">
        <summary>Connect by address</summary>
        <p className="hint">For a device on your network: its address and mesh port, for example 192.168.1.20:7000.</p>
        <div className="name-row">
          <input
            className="text-input"
            value={address}
            placeholder="host:port"
            aria-label="Device address"
            onChange={(e) => setAddress(e.target.value)}
          />
          <button className="button" disabled={!address.trim()} onClick={connectAddress}>
            Connect
          </button>
        </div>
        {addressMessage && <p className="hint">{addressMessage}</p>}
      </details>
    </section>
  );
}

/** Shown when a discovered (not yet connected) device is selected on the map. */
export function ConnectCard({ id, fallbackName }: { id: string; fallbackName: string }) {
  const { nearby, connectNearby } = useMesh();
  const item = nearby.items.find((i) => i.deviceId === id && i.state === "CONNECTED") || nearby.items.find((i) => i.deviceId === id);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function connect() {
    setBusy(true);
    setError("");
    try {
      await connectNearby(id, item?.address);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const connected = item?.state === "CONNECTED";
  return (
    <div className="visibility-card connect-card">
      <div>
        <b>{item?.name ?? fallbackName}</b>
        <p>
          {connected
            ? "Connected."
            : item
              ? `${describe(item)}. Not connected yet.`
              : "Discovered nearby. Not connected yet."}
        </p>
        {(error || (item?.state === "FAILED" && item.error)) && (
          <p role="alert" className="field-error">
            {error || item?.error}
          </p>
        )}
        {!connected && (
          <button className="button primary" onClick={connect} disabled={busy}>
            {busy ? "Connecting…" : item?.state === "FAILED" ? "Retry connection" : "Connect"}
          </button>
        )}
      </div>
    </div>
  );
}
