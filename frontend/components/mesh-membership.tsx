"use client";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { request, useMesh } from "@/lib/mesh";
import { ConnectionGlyph, type ConnectionMethod } from "./connection-glyph";
import type { PublicDevice } from "./network-discovery";
import { NearbyPanel } from "./nearby";
import { ConnectionMethods, LocalNetwork, RadioStatus } from "./connection-methods";

type Advertisement = { meshId: string; peerId: string; address: string; peerPort: number; kind?: "lan" | "bluetooth"; verified: false };
type Membership = { meshId: string; phase: string; nearby: Advertisement[]; discoveryError: string; error: string; bluetoothReason?: string; bluetoothAvailable?: boolean; scanning?: boolean };
const empty: Membership = { meshId: "", phase: "UNCONFIGURED", nearby: [], discoveryError: "", error: "" };
const phases: Record<string, string> = { CONNECTED: "Connected", DISCOVERING: "Looking for peers", STARTING: "Starting connections", LEFT: "Ready to join", UNCONFIGURED: "Ready to join", ERROR: "Needs attention" };
const methodName = (item: Advertisement) => item.kind === "bluetooth" ? "Bluetooth" : "Wi-Fi / LAN";

/** Mesh identity/password membership is separate from the physical link used to reach a member. */
export function MeshMembership({ showPeers = true, joinRequest, overview, onDiscoveryRefresh, discoveredDevices }: { showPeers?: boolean; joinRequest?: { meshId: string; sequence: number }; overview?: ReactNode; onDiscoveryRefresh?: () => void; discoveredDevices?: PublicDevice[] }) {
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
  const [selection, setSelection] = useState<Advertisement | "create" | null>(null);
  const [meshId, setMeshId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const consumedJoin = useRef(0);
  useEffect(() => {
    if (!joinRequest || consumedJoin.current === joinRequest.sequence || preview || membership.meshId) return;
    const candidate = membership.nearby.find(m => m.meshId === joinRequest.meshId);
    if (candidate) { consumedJoin.current = joinRequest.sequence; select(candidate); }
  }, [joinRequest, membership.nearby, membership.meshId, preview]);
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
      if (["/membership/create", "/membership/join", "/membership/leave"].includes(path)) { setPassword(""); setSelection(null); }
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
  function select(item: Advertisement | "create") {
    setSelection(item); setPassword(""); setError(""); setMessage("");
    setMeshId(item === "create" ? "" : item.meshId);
  }
  const groups = [...new Set(membership.nearby.map(item => item.meshId))].map(id => ({ id, items: membership.nearby.filter(item => item.meshId === id) }));
  const scanning = !!membership.scanning || nearby.scanning;
  const selectedCandidates = selection && selection !== "create" ? membership.nearby.filter(item => item.meshId === meshId) : [];
  const selectedKind = selection && selection !== "create" && selection.kind === "bluetooth" ? "bluetooth" : "lan";
  const selectedStillNearby = !selection || selection === "create" || selectedCandidates.some(item => item.peerId === selection.peerId && item.address === selection.address);
  function chooseConnection(kind: "bluetooth" | "lan") {
    const candidate = selectedCandidates.find(item => (item.kind === "bluetooth" ? "bluetooth" : "lan") === kind);
    if (candidate) setSelection(candidate);
  }
  return <section className="mesh-connections" aria-label="Relay mesh membership">
    <div className="nearby-scan-card"><div className="nearby-scan-heading"><div><span className="map-device-eyebrow">NEARBY DISCOVERY</span><h3>Find devices &amp; meshes</h3></div><button className="button primary" disabled={busy || scanning || (!preview && loading)} onClick={() => void scanAll()}>{scanStarting || scanning ? "Scanning…" : "Scan nearby"}</button></div><p>Search Wi-Fi, Ethernet and Bluetooth. Wi-Fi Direct is checked when your device supports it.</p><small>Looks for Relay on your local network. Connect to a hotspot first to find devices using it.</small></div>
    {overview}
    <section id="mesh-membership" className="mesh-membership-card" aria-label="Mesh membership">
      <div className="connection-section-heading"><div><h3>{membership.meshId ? "Your mesh" : "Find a mesh to join"}</h3><p>{membership.meshId ? "You belong to one mesh at a time." : "Choose a mesh, choose a connection, then enter its password."}</p></div></div>
      {preview ? <p className="connection-preview-note">Design preview · Switch to live to create or join a real mesh.</p> : <>
        {membership.meshId && <div className={`membership-status ${membership.phase === "CONNECTED" ? "joined" : ""}`} role="status"><div><b>{membership.meshId}</b><small><i className="status-dot" />{phases[membership.phase] || "Checking connection"}</small></div><button className="button" disabled={busy} onClick={() => void operation("/membership/leave")}>Leave mesh</button></div>}
        {loading && <p className="mesh-discovery-empty" role="status">Looking for nearby meshes…</p>}
        {groups.length > 0 && <div className="mesh-results" aria-label="Nearby meshes">{groups.map(group => <div className="mesh-result" key={group.id}><div><b>{group.id}</b><small>Unverified name · {[...new Set(group.items.map(methodName))].join(" + ")}</small></div><button className="button" disabled={busy || !!membership.meshId} title={membership.meshId ? "Leave your current mesh before joining another" : "Choose how to reach this mesh"} onClick={() => select(group.items[0])} aria-label={`Join mesh ${group.id}`}>Join</button></div>)}</div>}
        {!membership.meshId && !loading && <div className={`mesh-create-choice${groups.length ? " has-meshes" : ""}`}>
          <p>{groups.length ? "Want a separate group? Create your own mesh." : "No meshes found yet. Scan again with another Relay device nearby, or create a mesh for others to join."}</p>
          <button className={`button${groups.length ? "" : " primary"}`} disabled={busy || !!loadError} onClick={() => select("create")}>Create mesh</button>
        </div>}
        {selection && <form className="mesh-join-form connection-form" onSubmit={event => { event.preventDefault(); if (!selectedStillNearby) return; void operation(`/membership/${selection === "create" ? "create" : "join"}`, {
          meshId, password, localAddress: selection !== "create" && selection.kind === "bluetooth" ? localAddress || networks[0]?.address || "127.0.0.1" : localAddress, ...(selection === "create" ? {} : { peerId: selection.peerId, address: selection.address }),
        }); }}>
          <h4>{selection === "create" ? "Create a new mesh" : "Join this mesh"}</h4>
          {selection === "create" && <p className="method-help">Start a new Relay group with a name and password. Other devices can find it and join with that password. This does not create a Wi-Fi hotspot.</p>}
          <label className="connection-field">Mesh name<input className="text-input" aria-label="Mesh name" required pattern="[A-Za-z0-9_.\-]{1,80}" value={meshId} readOnly={selection !== "create"} onChange={e => setMeshId(e.target.value)} /><small>1–80 letters, numbers, dots, underscores or hyphens.</small></label>
          {selection !== "create" && <>
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
          {(selection === "create" || selection.kind !== "bluetooth") && <>
          <label className="connection-field">Your Wi-Fi or Ethernet connection<select className="text-input" aria-label="Mesh local network" required value={localAddress} onChange={e => setLocalAddress(e.target.value)}><option value="">Choose your connection</option>{!networks.length && <option value="127.0.0.1">No LAN address · use Bluetooth</option>}{networks.map(n => <option key={n.address} value={n.address}>{n.label}</option>)}</select></label>
          <div className="connection-actions"><button className="button" type="button" disabled={busy} onClick={() => void refreshNetworks()}>Refresh connections</button></div>
          <p className="method-help">Switched Wi-Fi, phone hotspot or Ethernet? Refresh connections before joining.</p>
          </>}
          <label className="connection-field">Mesh password<input className="text-input" aria-label="Mesh password" type="password" minLength={16} maxLength={256} required autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} /><small>16–256 characters. Anyone with this password can join.</small></label>
          <p className="method-help">Names are unverified until authentication succeeds. Joining makes your device visible. Use the Your device card in this sidebar to change visibility.</p>
          <div className="connection-actions"><button className="button primary" disabled={busy || !selectedStillNearby} type="submit">{busy ? "Authenticating…" : selection === "create" ? "Create this mesh" : "Authenticate and join"}</button><button className="button" type="button" disabled={busy} onClick={() => { setSelection(null); setPassword(""); }}>Cancel</button></div>
        </form>}
        {(membership.error || loadError) && <details className="mesh-service-notice"><summary>Mesh service needs attention</summary><p>{loadError || membership.error}</p><Link className="button" href="/settings/">Review Settings</Link></details>}
      </>}
      {message && <p role="status" className="connection-message">{message}</p>}
      {error && <p role="alert" className="connection-note error">{error}</p>}
    </section>
    {showPeers && <div className="all-nearby-devices"><NearbyPanel discoveredDevices={discoveredDevices} /></div>}
    <div className="mesh-connection-setup" ref={setupPanel}><ConnectionMethods requestedMethod={setupMethod} radio={radio} radioError={radioError} networks={networks} networksError={networksError} bluetoothAvailable={membership.bluetoothAvailable} bluetoothReason={membership.bluetoothReason} discoveryError={membership.discoveryError} scanning={scanning} busy={busy} checking={loading} reload={refreshNetworks} perform={operation} /></div>
  </section>;
}
