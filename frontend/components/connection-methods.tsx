"use client";
import { useEffect, useState } from "react";
import { useMesh } from "@/lib/mesh";
import { ConnectionGlyph, ConnectionMethod } from "./connection-glyph";
import { NearbyPanel } from "./nearby";

export type LocalNetwork = { address: string; label: string };
export type RadioStatus = {
  available: boolean; reason?: string; state: string; mode?: string; error?: string;
  adapters: { path: string; name: string; type: string; hotspotCapable: boolean }[];
  peers: { devicePath: string; address: string; name: string }[];
};
type Props = {
  radio: RadioStatus | null; radioError: string; networks: LocalNetwork[]; networksError: string;
  bluetoothAvailable?: boolean; bluetoothReason?: string; discoveryError?: string; scanning: boolean;
  busy: boolean; checking: boolean; showPeers: boolean; reload: () => Promise<void>; scanBluetooth: () => Promise<void>;
  perform: (path: string, body?: object) => Promise<boolean>;
};
const methods: { id: ConnectionMethod; label: string }[] = [
  { id: "bluetooth", label: "Bluetooth" }, { id: "lan", label: "Wi-Fi / LAN" },
  { id: "wifi-direct", label: "Wi-Fi Direct" }, { id: "hotspot", label: "Hotspot" },
];

export function ConnectionMethods(props: Props) {
  const { preview, device, links, bluetooth } = useMesh();
  const [method, setMethod] = useState<ConnectionMethod>("bluetooth");
  const [adapter, setAdapter] = useState("");
  const [ssid, setSsid] = useState("Relay offline");
  const [password, setPassword] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  useEffect(() => { setPassword(""); setConfirmed(false); }, [preview]);
  useEffect(() => { setConfirmed(false); }, [method]);
  const native = preview ? null : props.radio;
  const adapters = native ? native.adapters : [];
  const hotspots = adapters.filter(a => a.hotspotCapable);
  const directAvailable = !!native?.available && adapters.some(a => a.type === "wifi-direct");
  const hotspotAvailable = !!native?.available && hotspots.length > 0;
  const bluetoothAvailable = device?.bluetooth.available ?? props.bluetoothAvailable ?? (bluetooth?.adapter ? true : undefined);
  const networks = preview ? [{ address: "192.0.2.10", label: "Example local network" }] : props.networks;
  const supported: Record<ConnectionMethod, boolean | undefined> = {
    bluetooth: bluetoothAvailable, lan: props.networksError ? undefined : props.networks.length > 0,
    "wifi-direct": props.radioError ? false : props.radio === null ? undefined : directAvailable,
    hotspot: props.radioError ? false : props.radio === null ? undefined : hotspotAvailable,
  };
  const count = (id: ConnectionMethod) => Object.values(links).filter(kind => id === "lan" ? ["tcp", "lan"].includes(kind) : kind === id).length;
  const ownedMode = native?.mode === "wifi-direct" ? "Wi-Fi Direct" : native?.mode === "hotspot" ? "Hotspot" : "";
  const radioRunning = !!native && native.state !== "STOPPED";
  const isOwn = !!native && native.mode === method && radioRunning;
  const canConfigure = !!native && native.state === "STOPPED";
  const nativeReason = props.radioError || native?.reason;
  const bluetoothReason = device?.bluetooth.reason || props.bluetoothReason;
  async function startHotspot(event: React.FormEvent) {
    event.preventDefault();
    if (!preview && confirmed && hotspotAvailable && canConfigure && hotspots.some(a => a.path === adapter)) {
      const ok = await props.perform("/radio/hotspot", { devicePath: adapter, ssid, password, confirmed });
      if (ok) { setPassword(""); setConfirmed(false); }
    }
  }
  const warning = <label className="connection-consent">
    <input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={preview || props.busy} />
    <span>This may disconnect my current Wi-Fi network. I understand and want to continue.</span>
  </label>;
  const nativeState = native?.state === "ACTIVE" ? "Running" : native?.state === "STARTING" ? "Starting…" : native?.state === "FAILED" || native?.state === "ERROR" ? "Needs attention" : "Stopped";
  return <section className="connection-methods" aria-label="Connection methods">
    <div className="connection-section-heading"><div><h3>Connection methods</h3><p>Choose a way to link nearby devices.</p></div></div>
    <div className="connection-tabs" role="tablist" aria-label="Connection method">
      {methods.map((item, index) => <button type="button" role="tab" tabIndex={method === item.id ? 0 : -1} onKeyDown={event => {
        const offset = ({ ArrowRight: 1, ArrowLeft: -1, ArrowDown: 2, ArrowUp: -2 } as Record<string, number>)[event.key];
        if (offset === undefined && event.key !== "Home" && event.key !== "End") return;
        event.preventDefault();
        const next = event.key === "Home" ? 0 : event.key === "End" ? methods.length - 1 : (index + offset + methods.length) % methods.length;
        setMethod(methods[next].id); document.getElementById(`method-${methods[next].id}`)?.focus();
      }} id={`method-${item.id}`} key={item.id} aria-selected={method === item.id} aria-controls="connection-method-panel" onClick={() => setMethod(item.id)} className={method === item.id ? "selected" : ""}>
        <ConnectionGlyph kind={item.id} /><span><b>{item.label}</b><small className={count(item.id) && !preview ? "ready" : ""}>{preview ? "Preview" : count(item.id) ? `${count(item.id)} active link${count(item.id) === 1 ? "" : "s"}` : supported[item.id] === undefined ? props.checking ? "Checking…" : "Not reported" : supported[item.id] ? item.id === "hotspot" ? "Can host" : "Available" : "Unavailable"}</small></span>
      </button>)}
    </div>
    <div id="connection-method-panel" className="connection-method-panel" role="tabpanel" tabIndex={0} aria-labelledby={`method-${method}`}>
      {method === "bluetooth" && <>
        <div className="method-heading"><h4>Nearby Bluetooth devices</h4><span className="method-tag">{preview ? "Preview" : props.scanning ? "Scanning" : bluetoothAvailable ? "Ready" : bluetoothAvailable === false ? "Unavailable" : props.checking ? "Checking" : "Not reported"}</span></div>
        <p className="method-help">Discover nearby Relay devices without a Wi-Fi network. A mesh password is still required to join.</p>
        {!preview && bluetoothReason && <p className="connection-note">{bluetoothReason}</p>}
        {props.showPeers && <NearbyPanel method="bluetooth" embedded onScan={props.scanBluetooth} scanAvailable={preview || !!bluetoothAvailable} scanning={props.scanning} />}
      </>}
      {method === "lan" && <>
        <div className="method-heading"><h4>Wi-Fi or Ethernet</h4><button className="button" disabled={preview || props.busy} onClick={() => void props.reload()}>Refresh networks</button></div>
        <p className="method-help">Uses an existing local network. Connect to Wi-Fi or Ethernet in your system settings; Relay discovers members automatically.</p>
        <div className="local-network-list">{networks.map(network => <div className="local-network" key={network.address}><span className="network-address-mark" /><div><b>{network.label}</b><small>{network.address}</small></div></div>)}</div>
        {!networks.length && <p className="method-empty">No compatible local address detected. Connect Wi-Fi, Ethernet or a hotspot, then refresh.</p>}
        {!preview && (props.networksError || props.discoveryError) && <p className="connection-note">{props.networksError || props.discoveryError}<span>Bluetooth discovery is separate and may still be available.</span></p>}
        {props.showPeers && <NearbyPanel method="lan" embedded />}
      </>}
      {method === "wifi-direct" && <>
        <div className="method-heading"><h4>Connect without a router</h4><span className="method-tag">{preview ? "Preview" : isOwn ? nativeState : directAvailable ? "Ready" : "Unavailable"}</span></div>
        <p className="method-help">Find a Wi-Fi Direct device, connect through the operating system, then scan for its Relay mesh. Accept system pairing prompts when requested.</p>
        {!preview && !directAvailable && <p className="connection-note">{nativeReason || "No Wi-Fi Direct adapter was reported by the operating system or driver."}</p>}
        {radioRunning && !isOwn && <p className="connection-note">{ownedMode || "A radio connection"} is already active. Stop it before starting Wi-Fi Direct.</p>}
        <button className="button method-scan" disabled={preview || props.busy || !directAvailable} onClick={() => void props.perform("/radio/scan")}>Scan Wi-Fi Direct</button>
        {(preview || directAvailable) && <>
          {!native || !native.peers.length ? <p className="method-empty">{preview ? "Example view only. Switch to live to find Wi-Fi Direct devices." : "No Wi-Fi Direct devices found yet. Scan with the other device nearby."}</p> : <div className="direct-peer-list">{native.peers.map(peer => <div className="direct-peer" key={`${peer.devicePath}/${peer.address}`}><div><b>{peer.name}</b><small>Unverified device · {peer.address}</small></div><button className="button" disabled={!confirmed || !canConfigure || props.busy} onClick={() => void props.perform("/radio/connect", { devicePath: peer.devicePath, address: peer.address, confirmed })}>Connect device</button></div>)}</div>}
          {warning}
        </>}
        {isOwn && <button className="button method-stop" disabled={!confirmed || props.busy} onClick={() => void props.perform("/radio/stop", { confirmed })}>Stop Wi-Fi Direct</button>}
      </>}
      {method === "hotspot" && <>
        <div className="method-heading"><h4>Host a local network</h4><span className="method-tag">{preview ? "Preview" : isOwn ? nativeState : hotspotAvailable ? "Stopped" : "Unavailable"}</span></div>
        <p className="method-help">Let nearby devices connect to this laptop over Wi-Fi. They use the hotspot password first, then the Relay mesh password. Internet is not required.</p>
        {!preview && !hotspotAvailable && <p className="connection-note">{nativeReason || "No hotspot-capable adapter was reported by the operating system or driver."}</p>}
        {radioRunning && !isOwn && <p className="connection-note">{ownedMode || "A radio connection"} is already active. Stop it before starting a hotspot.</p>}
        {isOwn ? <div className="hosted-network"><p className="method-help">Connect the other device using the hotspot name and password chosen at startup, then join the Relay mesh.</p>{warning}<button type="button" className="button method-stop" disabled={!confirmed || props.busy} onClick={() => void props.perform("/radio/stop", { confirmed })}>Stop hotspot</button></div> : (preview || hotspotAvailable) && <form className="connection-form" onSubmit={startHotspot}>
          <label className="connection-field">Wi-Fi adapter<select className="text-input" aria-label="Hotspot adapter" required value={adapter} disabled={preview || !canConfigure || props.busy} onChange={event => setAdapter(event.target.value)}><option value="">Choose an adapter</option>{hotspots.map(item => <option key={item.path} value={item.path}>{item.name}</option>)}</select></label>
          <label className="connection-field">Hotspot name<input className="text-input" aria-label="Hotspot name" required value={ssid} maxLength={32} disabled={preview || !canConfigure || props.busy} onChange={event => setSsid(event.target.value)} /></label>
          <label className="connection-field">Hotspot password<input className="text-input" aria-label="Hotspot password" type="password" name="relay-hotspot-password" autoComplete="new-password" required minLength={8} maxLength={63} pattern="[\x20-\x7E]{8,63}" value={password} disabled={preview || !canConfigure || props.busy} onChange={event => setPassword(event.target.value)} /><small>8–63 ASCII characters. Separate from the mesh password.</small></label>
          {warning}
          <button type="submit" className="button primary method-start" disabled={preview || !confirmed || !adapter || password.length < 8 || !canConfigure || props.busy}>Start hotspot</button>
        </form>}
      </>}
      {!preview && native?.error && ["hotspot", "wifi-direct"].includes(method) && <p className="connection-note error" role="alert">{native.error}</p>}
    </div>
  </section>;
}
