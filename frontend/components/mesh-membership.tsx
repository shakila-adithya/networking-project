"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { request, useMesh } from "@/lib/mesh";
import { ConnectionMethods, LocalNetwork, RadioStatus } from "./connection-methods";

type Advertisement = { meshId: string; peerId: string; address: string; peerPort: number; kind?: "lan" | "bluetooth"; verified: false };
type Membership = { meshId: string; phase: string; nearby: Advertisement[]; discoveryError: string; error: string; bluetoothReason?: string; bluetoothAvailable?: boolean; scanning?: boolean };
const empty: Membership = { meshId: "", phase: "UNCONFIGURED", nearby: [], discoveryError: "", error: "" };
const phases: Record<string, string> = { CONNECTED: "Connected", DISCOVERING: "Looking for peers", STARTING: "Starting connections", LEFT: "Ready to join", UNCONFIGURED: "Ready to join", ERROR: "Needs attention" };
const methodName = (item: Advertisement) => item.kind === "bluetooth" ? "Bluetooth" : "Wi-Fi / LAN";

/** Mesh identity/password membership is separate from the physical link used to reach a member. */
export function MeshMembership({ showPeers = true }: { showPeers?: boolean }) {
  const { preview, refresh, device, nearby, scanNearby } = useMesh();
  const [membership, setMembership] = useState<Membership>(empty);
  const [radio, setRadio] = useState<RadioStatus | null>(null);
  const [networks, setNetworks] = useState<LocalNetwork[]>([]);
  const [radioError, setRadioError] = useState("");
  const [networksError, setNetworksError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [loading, setLoading] = useState(true);
  const [localAddress, setLocalAddress] = useState("");
  const [selection, setSelection] = useState<Advertisement | "create" | null>(null);
  const [meshId, setMeshId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    if (preview) return;
    const [m, r, n] = await Promise.allSettled([request("/membership"), request("/radio"), request("/setup/networks")]);
    if (m.status === "fulfilled" && m.value.phase) { setMembership({ ...empty, ...m.value, nearby: m.value.nearby || [] }); setLoadError(""); }
    else setLoadError(m.status === "rejected" ? m.reason.message : "The mesh service did not report its state. Restart the updated backend.");
    if (r.status === "fulfilled") { setRadio({ ...r.value, adapters: r.value.adapters || [], peers: r.value.peers || [] }); setRadioError(""); }
    else { setRadio(null); setRadioError(r.reason.message); }
    if (n.status === "fulfilled") { setNetworks(n.value.networks || []); setNetworksError(""); }
    else { setNetworks([]); setNetworksError(n.reason.message); }
    setLoading(false);
  }, [preview]);
  useEffect(() => {
    setPassword(""); setSelection(null); setError(""); setMessage("");
    if (preview) return;
    void load();
    const timer = setInterval(() => void load(), 4000);
    return () => clearInterval(timer);
  }, [preview, load]);
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
  async function scanBluetooth() {
    if (preview || device) await scanNearby();
    else await operation("/membership/scan");
  }
  function select(item: Advertisement | "create") {
    setSelection(item); setPassword(""); setError(""); setMessage("");
    setMeshId(item === "create" ? "" : item.meshId);
  }
  const groups = [...new Set(membership.nearby.map(item => item.meshId))].map(id => ({ id, items: membership.nearby.filter(item => item.meshId === id) }));
  const scanning = !!membership.scanning || nearby.scanning;
  return <section className="mesh-connections" aria-label="Relay mesh membership">
    <section className="mesh-membership-card" aria-label="Mesh membership">
      <div className="connection-section-heading"><div><h3>Your mesh</h3><p>One mesh at a time. Join with its password.</p></div><button className="button" disabled={preview || busy || scanning || loading} onClick={() => void operation("/membership/scan")}>{scanning ? "Scanning meshes…" : "Scan meshes"}</button></div>
      {preview ? <p className="connection-preview-note">Design preview · Switch to live to create or join a real mesh.</p> : <>
        <div className={`membership-status ${membership.phase === "CONNECTED" ? "joined" : ""}`} role="status"><div><b>{membership.meshId || "No mesh joined"}</b><small><i className="status-dot" />{loading ? "Checking mesh…" : phases[membership.phase] || "Checking connection"}</small></div>
          {membership.meshId ? <button className="button" disabled={busy} onClick={() => void operation("/membership/leave")}>Leave mesh</button> : <button className="button" disabled={busy || loading || !!loadError} onClick={() => select("create")}>Create mesh</button>}
        </div>
        {groups.length > 0 && <div className="mesh-results"><h4>Nearby meshes</h4>{groups.map(group => <div className="mesh-result" key={group.id}><div><b>{group.id}</b><small>Unverified name · {[...new Set(group.items.map(methodName))].join(" + ")}</small></div><button className="button" disabled={busy || !!membership.meshId} title={membership.meshId ? "Leave the current mesh before joining another" : "Authenticate with this mesh's password"} onClick={() => select(group.items[0])} aria-label={`Join mesh ${group.id}`}>Join</button></div>)}</div>}
        {!membership.meshId && !groups.length && !loading && <p className="mesh-discovery-empty">No meshes discovered yet. Scan for Bluetooth members or connect to the same local network.</p>}
        {selection && <form className="mesh-join-form connection-form" onSubmit={event => { event.preventDefault(); void operation(`/membership/${selection === "create" ? "create" : "join"}`, {
          meshId, password, localAddress, ...(selection === "create" ? {} : { peerId: selection.peerId, address: selection.address }),
        }); }}>
          <h4>{selection === "create" ? "Create a mesh" : "Join this mesh"}</h4>
          <label className="connection-field">Mesh name<input className="text-input" aria-label="Mesh name" required pattern="[A-Za-z0-9_.\-]{1,80}" value={meshId} readOnly={selection !== "create"} onChange={e => setMeshId(e.target.value)} /><small>1–80 letters, numbers, dots, underscores or hyphens.</small></label>
          {selection !== "create" && <label className="connection-field">Connection<select className="text-input" aria-label="Mesh connection" value={`${selection.peerId}/${selection.address}`} onChange={event => { const item = membership.nearby.find(item => `${item.peerId}/${item.address}` === event.target.value && item.meshId === meshId); if (item) setSelection(item); }}>{membership.nearby.filter(item => item.meshId === meshId).map(item => <option value={`${item.peerId}/${item.address}`} key={`${item.peerId}/${item.address}`}>{methodName(item)} · {item.address}</option>)}</select></label>}
          <label className="connection-field">Local network<select className="text-input" aria-label="Mesh local network" required value={localAddress} onChange={e => setLocalAddress(e.target.value)}><option value="">Choose a local network</option>{!networks.length && <option value="127.0.0.1">No LAN address · use Bluetooth</option>}{networks.map(n => <option key={n.address} value={n.address}>{n.label}</option>)}</select></label>
          <label className="connection-field">Mesh password<input className="text-input" aria-label="Mesh password" type="password" minLength={16} maxLength={256} required autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} /><small>16–256 characters. Anyone with this password can join.</small></label>
          <p className="method-help">Names are unverified until authentication succeeds. Joining enables Bluetooth visibility; change it by selecting your device on the map.</p>
          <div className="connection-actions"><button className="button primary" disabled={busy} type="submit">{busy ? "Authenticating…" : selection === "create" ? "Create this mesh" : "Authenticate and join"}</button><button className="button" type="button" disabled={busy} onClick={() => { setSelection(null); setPassword(""); }}>Cancel</button></div>
        </form>}
        {(membership.error || loadError) && <details className="mesh-service-notice"><summary>Mesh service needs attention</summary><p>{loadError || membership.error}</p><Link className="button" href="/settings/">Review Settings</Link></details>}
      </>}
      {message && <p role="status" className="connection-message">{message}</p>}
      {error && <p role="alert" className="connection-note error">{error}</p>}
    </section>
    <ConnectionMethods radio={radio} radioError={radioError} networks={networks} networksError={networksError} bluetoothAvailable={membership.bluetoothAvailable} bluetoothReason={membership.bluetoothReason} discoveryError={membership.discoveryError} scanning={scanning} busy={busy} checking={loading} showPeers={showPeers} reload={load} scanBluetooth={scanBluetooth} perform={operation} />
  </section>;
}
