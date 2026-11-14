"use client";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { request, useMesh } from "@/lib/mesh";
import { ConnectionGlyph, type ConnectionMethod } from "./connection-glyph";
import { ConnectionMethods, LocalNetwork, RadioStatus } from "./connection-methods";

type Advertisement = { meshId: string; peerId: string; address: string; peerPort: number; kind?: "lan" | "bluetooth"; verified: false };
type Membership = { meshId: string; phase: string; nearby: Advertisement[]; discoveryError: string; error: string; bluetoothReason?: string; bluetoothAvailable?: boolean; scanning?: boolean };
const empty: Membership = { meshId: "", phase: "UNCONFIGURED", nearby: [], discoveryError: "", error: "" };
const phases: Record<string, string> = { CONNECTED: "Connected", DISCOVERING: "Looking for peers", STARTING: "Starting connections", LEFT: "Ready to join", UNCONFIGURED: "Ready to join", ERROR: "Needs attention" };
/** A readable random mesh name so people only have to choose a password. */
const newMeshName = () => `relay-${Array.from(crypto.getRandomValues(new Uint8Array(3)), b => b.toString(16).padStart(2, "0")).join("")}`;
/** 20 random letters and digits (about 100 bits) for sharing out loud or by message. */
const newPassword = () => { const chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"; return Array.from(crypto.getRandomValues(new Uint8Array(20)), b => chars[b % chars.length]).join(""); };
const methodName = (item: Advertisement) => item.kind === "bluetooth" ? "Bluetooth" : "Wi-Fi / LAN";

/** Mesh identity/password membership is separate from the physical link used to reach a member. */
export function MeshMembership({ joinRequest, startRequest, overview, onDiscoveryRefresh }: { joinRequest?: { meshId: string; sequence: number }; startRequest?: { name: string; sequence: number }; overview?: ReactNode; onDiscoveryRefresh?: () => void }) {
  const { preview, refresh, nearby, scanNearby } = useMesh();
  const [membership, setMembership] = useState<Membership>(empty);
  const [radio, setRadio] = useState<RadioStatus | null>(null);
  const [networks, setNetworks] = useState<LocalNetwork[]>([]);
  const [radioError, setRadioError] = useState("");
  const [networksError, setNetworksError] = useState("");
  const networkCheck = useRef(false);
  const [loadError, setLoadError] = useState("");
  const [loading, setLoading] = useState(true);
  const [localAddress, setLocalAddress] = useState("");
  const [selection, setSelection] = useState<Advertisement | "start" | null>(null);
  const [meshId, setMeshId] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [startTarget, setStartTarget] = useState("");
  const [shared, setShared] = useState<{ password: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const consumedJoin = useRef(0);
  useEffect(() => {
    if (!joinRequest || consumedJoin.current === joinRequest.sequence || preview || membership.meshId) return;
    const candidate = membership.nearby.find(m => m.meshId === joinRequest.meshId);
    if (candidate) { consumedJoin.current = joinRequest.sequence; select(candidate); }
  }, [joinRequest, membership.nearby, membership.meshId, preview]);
  const consumedStart = useRef(0);
  useEffect(() => {
    if (!startRequest || consumedStart.current === startRequest.sequence || preview || membership.meshId) return;
    consumedStart.current = startRequest.sequence; startWith(startRequest.name);
  }, [startRequest, preview, membership.meshId]);
  const [busy, setBusy] = useState(false);
  const [setupMethod, setSetupMethod] = useState<ConnectionMethod | undefined>();
  const setupPanel = useRef<HTMLDivElement>(null);
  function openSetup(method: ConnectionMethod) {
    setSetupMethod(method);
    setupPanel.current?.scrollIntoView({ block: "start" });
  }
  const load = useCallback(async () => {
    if (preview) return;
    const [m, r] = await Promise.allSettled([request("/membership"), request("/radio")]);
    if (m.status === "fulfilled" && m.value.phase) { setMembership({ ...empty, ...m.value, nearby: m.value.nearby || [] }); setLoadError(""); }
    else setLoadError(m.status === "rejected" ? m.reason.message : "The mesh service did not report its state. Restart the updated backend.");
    if (r.status === "fulfilled") { setRadio({ ...r.value, adapters: r.value.adapters || [], peers: r.value.peers || [] }); setRadioError(""); }
    else { setRadio(null); setRadioError(r.reason.message); }
    setLoading(false);
  }, [preview]);
  const refreshNetworks = useCallback(async () => {
    if (preview || networkCheck.current) return;
    networkCheck.current = true;
    try { const data = await request("/setup/networks"); setNetworks(data.networks || []); setNetworksError(""); }
    catch (e) { setNetworks([]); setNetworksError((e as Error).message); }
    finally { networkCheck.current = false; }
  }, [preview]);
  useEffect(() => {
    setPassword(""); setSelection(null); setError(""); setMessage("");
    if (preview) return;
    void load();
    void refreshNetworks();
    const timer = setInterval(() => void load(), 4000);
    return () => clearInterval(timer);
  }, [preview, load, refreshNetworks]);
  async function operation(path: string, body: object = {}): Promise<boolean> {
    if (preview || busy) return false;
    setBusy(true); setError(""); setMessage("");
    try {
      const result = await request(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      setMessage(result.message || "Request sent. The connection status will update when the operating system responds.");
      if (["/membership/create", "/membership/join", "/membership/leave"].includes(path)) { setPassword(""); setSelection(null); setShared(null); setCopied(false); }
      await load(); await refresh();
      return true;
    } catch (e) { setError((e as Error).message); return false; }
    finally { setBusy(false); }
  }
  const scanInFlight = useRef(false);
  const [scanStarting, setScanStarting] = useState(false);
  async function scanAll() {
    if (busy || scanInFlight.current) return;
    if (preview) { await scanNearby(); return; }
    scanInFlight.current = true; setScanStarting(true); setBusy(true); setError(""); setMessage("");
    try {
      const jobs = [request("/membership/scan", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" })];
      if (radio?.available && radio.adapters.some(adapter => adapter.type === "wifi-direct")) {
        jobs.push(request("/radio/scan", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }));
      }
      const results = await Promise.allSettled(jobs);
      const failures = results.filter((result): result is PromiseRejectedResult => result.status === "rejected");
      if (failures.length) setError(failures.map(result => result.reason.message).join(" · "));
      if (results.some(result => result.status === "fulfilled")) setMessage("Search started. Nearby devices and meshes appear on the map as they are found.");
      await refreshNetworks(); await load(); await refresh(); onDiscoveryRefresh?.();
    } finally { scanInFlight.current = false; setScanStarting(false); setBusy(false); }
  }
  /** Reveals the saved password on demand (loopback-only endpoint); it is kept in memory only. */
  async function revealPassword() {
    try { const result = await request("/membership/password"); setShared({ password: String(result.password) }); setError(""); }
    catch (e) { setError((e as Error).message); }
  }
  async function copyPassword() {
    if (!shared) return;
    try { await navigator.clipboard.writeText(shared.password); setCopied(true); setTimeout(() => setCopied(false), 2000); }
    catch { setError("Could not copy automatically. Select the password and copy it."); }
  }
  function startWith(name: string) { setStartTarget(name); select("start"); setPassword(newPassword()); setShowPassword(true); }
  function select(item: Advertisement | "start") {
    setSelection(item); setPassword(""); setError(""); setMessage("");
    setShowPassword(false);
    setMeshId(item === "start" ? newMeshName() : item.meshId);
  }
  const scanning = !!membership.scanning || nearby.scanning;

  const selectedCandidates = selection && selection !== "start" ? membership.nearby.filter(item => item.meshId === meshId) : [];
  const selectedKind = selection && selection !== "start" && selection.kind === "bluetooth" ? "bluetooth" : "lan";
  const selectedStillNearby = !selection || selection === "start" || selectedCandidates.some(item => item.peerId === selection.peerId && item.address === selection.address);
  function chooseConnection(kind: "bluetooth" | "lan") {
    const candidate = selectedCandidates.find(item => (item.kind === "bluetooth" ? "bluetooth" : "lan") === kind);
    if (candidate) setSelection(candidate);
  }
  return <section className="mesh-connections" aria-label="Relay mesh membership">
    {overview}
    <section id="mesh-membership" className="mesh-membership-card" aria-label="Mesh membership">
      {membership.meshId && <div className="connection-section-heading"><div><h3>Your mesh</h3><p>You belong to one mesh at a time.</p></div></div>}
      {preview ? <p className="connection-preview-note">Design preview · Switch to live to start or join a real mesh.</p> : <>
        {membership.meshId && <div className={`membership-status ${membership.phase === "CONNECTED" ? "joined" : ""}`} role="status"><div><b>{membership.meshId}</b><small><i className="status-dot" />{phases[membership.phase] || "Checking connection"}</small></div><button className="button" disabled={busy} onClick={() => void operation("/membership/leave")}>Leave mesh</button></div>}
        {membership.meshId && <div className="mesh-share-password" aria-label="Share mesh password"><div><b>Mesh password</b><small>Give this to each new device that joins <b>{membership.meshId}</b>. Share it only with people you trust.</small></div>
          {shared ? <div className="connection-actions"><input className="text-input" readOnly aria-label="Saved mesh password" value={shared.password} onFocus={event => event.currentTarget.select()} /><button className="button" type="button" onClick={() => void copyPassword()}>{copied ? "Copied" : "Copy"}</button><button className="button" type="button" onClick={() => { setShared(null); setCopied(false); }}>Hide</button></div>
            : <div className="connection-actions"><button className="button" type="button" disabled={busy} onClick={() => void revealPassword()}>Show password to share</button></div>}</div>}
        {!membership.meshId && !selection && !loading && <div className="mesh-ready-card" role="status"><span aria-hidden="true"><ConnectionGlyph kind="mesh" /></span><div><b>Ready to join a mesh</b><p>Choose how to connect, press Scan nearby, then select a mesh or device on the map and press Join or Connect.</p></div></div>}

        {selection && <form className="mesh-join-form connection-form" onSubmit={event => { event.preventDefault(); if (!selectedStillNearby) return; void operation(`/membership/${selection === "start" ? "create" : "join"}`, {
          meshId, password, localAddress: selection !== "start" && selection.kind === "bluetooth" ? localAddress || networks[0]?.address || "127.0.0.1" : localAddress, ...(selection === "start" ? {} : { peerId: selection.peerId, address: selection.address }),
        }); }}>
          <h4>{selection === "start" ? `Connect to ${startTarget || "this device"}` : "Join this mesh"}</h4>
          {selection === "start" && <p className="method-help">First connection: Relay generated a password and named this mesh <b>{meshId}</b>. Note or copy the password now. It stays under Mesh password so you can give it to devices that join later. You can also type your own. This does not create a Wi-Fi hotspot.</p>}
          {selection !== "start" && <label className="connection-field">Mesh name<input className="text-input" aria-label="Mesh name" required pattern="[A-Za-z0-9_.\-]{1,80}" value={meshId} readOnly /><small>Names are not verified until the password is accepted.</small></label>}
          {selection !== "start" && <>
            <fieldset className="mesh-join-methods"><legend>How do you want to connect?</legend>
              {(["lan", "bluetooth"] as const).map(kind => {
                const available = selectedCandidates.some(item => (item.kind === "bluetooth" ? "bluetooth" : "lan") === kind);
                return <button className="mesh-join-method" key={kind} type="button" disabled={busy || !available} aria-pressed={selectedKind === kind} onClick={() => chooseConnection(kind)}>
                  <ConnectionGlyph kind={kind} /><span><b>{kind === "bluetooth" ? "Bluetooth" : "Wi-Fi / LAN"}</b><small>{available ? "Found this mesh using this connection" : "Not detected for this mesh"}</small></span>
                </button>;
              })}
            </fieldset>
            {selectedCandidates.filter(item => (item.kind === "bluetooth" ? "bluetooth" : "lan") === selectedKind).length > 1 && <label className="connection-field">Reach this mesh through<select className="text-input" aria-label="Mesh connection" value={`${selection.peerId}/${selection.address}`} onChange={event => { const item = selectedCandidates.find(item => `${item.peerId}/${item.address}` === event.target.value); if (item) setSelection(item); }}>{selectedCandidates.filter(item => (item.kind === "bluetooth" ? "bluetooth" : "lan") === selectedKind).map(item => <option value={`${item.peerId}/${item.address}`} key={`${item.peerId}/${item.address}`}>{item.peerId} · {item.address}</option>)}</select></label>}
            {!selectedStillNearby && <p className="connection-note error" role="alert">This connection is no longer nearby. Select Scan nearby again or choose another connection.</p>}
            <details className="mesh-other-connections"><summary>Need another connection?</summary>
              <p className="method-help">For Bluetooth, scan while the other Relay device is visible. For Wi-Fi Direct or a hotspot, connect the devices first, then refresh connections and select Scan nearby again. Only detected connections can be used to join.</p>
              <div className="connection-actions"><button className="button" type="button" disabled={busy} onClick={() => void scanAll()}>Scan nearby</button><button className="button" type="button" onClick={() => openSetup("wifi-direct")}>Set up Wi-Fi Direct</button><button className="button" type="button" onClick={() => openSetup("hotspot")}>Set up hotspot</button></div>
            </details>
          </>}
          {(selection === "start" || selection.kind !== "bluetooth") && <>
          <label className="connection-field">Your Wi-Fi or Ethernet connection<select className="text-input" aria-label="Mesh local network" required value={localAddress} onChange={e => setLocalAddress(e.target.value)}><option value="">Choose your connection</option>{!networks.length && <option value="127.0.0.1">No LAN address · use Bluetooth</option>}{networks.map(n => <option key={n.address} value={n.address}>{n.label}</option>)}</select></label>
          <div className="connection-actions"><button className="button" type="button" disabled={busy} onClick={() => void refreshNetworks()}>Refresh connections</button></div>
          <p className="method-help">Switched Wi-Fi, phone hotspot or Ethernet? Refresh connections before joining.</p>
          </>}
          <label className="connection-field">Mesh password<input className="text-input" aria-label="Mesh password" type={showPassword ? "text" : "password"} minLength={16} maxLength={256} required autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} /><small>16–256 characters. Anyone with this password can join.</small></label>
          {selection === "start" && <div className="connection-actions"><button className="button" type="button" disabled={busy} onClick={() => { setPassword(newPassword()); setShowPassword(true); }}>Generate a password</button>{password && <button className="button" type="button" onClick={() => setShowPassword(v => !v)}>{showPassword ? "Hide password" : "Show password"}</button>}</div>}
          <p className="method-help">Names are unverified until authentication succeeds. Joining makes your device visible. Use the Your device card in this sidebar to change visibility.</p>
          <div className="connection-actions"><button className="button primary" disabled={busy || !selectedStillNearby} type="submit">{busy ? "Authenticating…" : selection === "start" ? "Connect" : "Authenticate and join"}</button><button className="button" type="button" disabled={busy} onClick={() => { setSelection(null); setPassword(""); }}>Cancel</button></div>
        </form>}
        {(membership.error || loadError) && <details className="mesh-service-notice"><summary>Mesh service needs attention</summary><p>{loadError || membership.error}</p><Link className="button" href="/settings/">Review Settings</Link></details>}
      </>}
      {message && <p role="status" className="connection-message">{message}</p>}
      {error && <p role="alert" className="connection-note error">{error}</p>}
    </section>
    <div className="mesh-connection-setup" ref={setupPanel}><ConnectionMethods requestedMethod={setupMethod} radio={radio} radioError={radioError} networks={networks} networksError={networksError} bluetoothAvailable={membership.bluetoothAvailable} bluetoothReason={membership.bluetoothReason} discoveryError={membership.discoveryError} scanning={scanning} busy={busy} checking={loading} reload={refreshNetworks} perform={operation} /></div>
    <div className="nearby-scan-card"><div className="nearby-scan-heading"><div><span className="map-device-eyebrow">NEARBY DISCOVERY</span><h3>Find devices &amp; meshes</h3></div><button className="button primary" disabled={busy || scanning || (!preview && loading)} onClick={() => void scanAll()}>{scanStarting || scanning ? "Scanning…" : "Scan nearby"}</button></div><p>Search Wi-Fi, Ethernet and Bluetooth. Wi-Fi Direct is checked when your device supports it.</p></div>
  </section>;
}
