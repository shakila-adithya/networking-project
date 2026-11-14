"use client";
import Link from "next/link";
import { useState } from "react";
import { useMesh, request, NearbyItem } from "@/lib/mesh";
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

/** The "Nearby devices" list: scan, see signal and state, connect. */
export function NearbyPanel({ method, embedded = false, onScan, scanAvailable, scanning }: {
  method?: "bluetooth" | "lan"; embedded?: boolean; onScan?: () => Promise<void>; scanAvailable?: boolean; scanning?: boolean;
} = {}) {
  const { nearby, scanNearby, connectNearby } = useMesh();
  const [busy, setBusy] = useState<string | null>(null),
    [errors, setErrors] = useState<Record<string, string>>({}),
    [scanError, setScanError] = useState(""),
    [address, setAddress] = useState(""),
    [addressMessage, setAddressMessage] = useState("");
  const isScanning = scanning ?? nearby.scanning;
  const canScan = scanAvailable ?? nearby.bluetoothAvailable;
  const items = [...nearby.items].filter(item => !method || (method === "bluetooth" ? item.kind === "bluetooth" : ["lan", "tcp"].includes(item.kind))).sort(
    (a, b) => order[a.state] - order[b.state] || (b.signal ?? -1) - (a.signal ?? -1),
  );
  async function scan() {
    setScanError("");
    try {
      await (onScan || scanNearby)();
    } catch (e) {
      setScanError((e as Error).message);
    }
  }
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
    <section className={`nearby${embedded ? " nearby-embedded" : ""}`} aria-label="Nearby devices">
      <div className="nearby-head">
        <Eyebrow>{method === "lan" ? "RELAY PEERS ON THIS NETWORK" : method === "bluetooth" ? "BLUETOOTH DISCOVERY" : "NEARBY DEVICES"}</Eyebrow>
        {method !== "lan" && <button
          className="button scan-button"
          onClick={scan}
          disabled={isScanning || !canScan}
          title={canScan ? "" : "Bluetooth is not available on this device"}
        >
          {isScanning ? "Scanning…" : method === "bluetooth" ? "Scan Bluetooth" : "Scan now"}
        </button>}
      </div>
      <small className="hint">
        {method === "lan" ? "Relay peers announce themselves automatically on this network." : isScanning ? "Looking for nearby Bluetooth devices…" : nearby.lastScanAt ? `Last Bluetooth scan ${new Date(nearby.lastScanAt).toLocaleTimeString()}` : canScan ? "Scan to find nearby Bluetooth devices running Relay." : "Bluetooth discovery is currently unavailable."}
      </small>
      {scanError && (
        <p role="alert" className="field-error">
          {scanError}
        </p>
      )}
      <div className="nearby-list">
        {actionable.map((item) => row(item))}
        {!actionable.length && (
          <p className="empty">
            {method === "lan" ? "No Relay peers detected on this local network yet." : "No Relay Bluetooth devices found. Make the other Relay device visible, then scan again."}
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
      {method !== "bluetooth" && <details className="by-address">
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
      </details>}
    </section>
  );
}

/** Shown when the user selects their own device on the map: the only place visibility is changed. */
export function VisibilityCard() {
  const { device, saveDevice } = useMesh();
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const available = !!device?.bluetooth.available;
  const visible = !!device?.visible;
  async function toggle() {
    setBusy(true);
    setError("");
    try {
      await saveDevice({ visible: !visible });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  let text: string;
  if (!device) text = "Waiting for the local node…";
  else if (!available) text = `Bluetooth is not available, so other devices cannot find you this way. ${device.bluetooth.reason ?? ""}`;
  else if (visible && device.bluetooth.error) text = `Visibility is on, but Bluetooth could not advertise: ${device.bluetooth.error}`;
  else if (visible) text = `Nearby Relay devices can find you as “${device.bluetooth.advertisedAs}”.`;
  else text = "Hidden. Nearby devices cannot find you, but you can still scan and connect to them.";
  return (
    <div className="visibility-card">
      <button
        type="button"
        role="switch"
        aria-checked={visible}
        aria-label="Visible to nearby devices"
        className="switch"
        disabled={busy || !available}
        onClick={toggle}
      />
      <div>
        <b>Visible to nearby devices</b>
        <p>{text}</p>
        {error && (
          <p role="alert" className="field-error">
            {error}
          </p>
        )}
        <p className="hint">
          Change the name others see in <Link href="/settings/#my-device">Settings → My device</Link>.
        </p>
      </div>
    </div>
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
