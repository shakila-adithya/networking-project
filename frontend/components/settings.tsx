"use client";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { request, useMesh } from "@/lib/mesh";
import { MyDevice } from "./my-device";
import { Eyebrow } from "./ui";

type Network = { address: string; label: string };
type Saved = { peerId: string; clusterId: string; address: string; peerPort: number; restPort: number; discoveryGroup: string; discoveryPort: number };
type Status = { configured: boolean; phase: string; settings?: Saved; error?: string };
const empty = { peerId: "", clusterId: "", secret: "", address: "", peerPort: 5001, restPort: 6001, discoveryGroup: "239.255.42.99", discoveryPort: 45555 };
const post = (path: string, body: object) => request(`/setup/${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export function Settings() {
  const { preview } = useMesh();
  const live = useRef(!preview);
  live.current = !preview;
  const [status, setStatus] = useState<Status | null>(null);
  const [networks, setNetworks] = useState<Network[]>([]);
  const [address, setAddress] = useState("");
  const [form, setForm] = useState(empty);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [message, setMessage] = useState("");
  const networkEdited = useRef(false), manualEdited = useRef(false);
  const refreshStatus = useCallback(async () => {
    const data: Status = await request("/setup");
    if (!live.current) return;
    setStatus(data);
    if (data.settings) {
      if (!networkEdited.current) setAddress(data.settings.address);
      if (!manualEdited.current) setForm(current => ({ ...current, ...data.settings, secret: current.secret }));
    }
  }, []);
  const refreshNetworks = useCallback(async () => {
    const data = await request("/setup/networks");
    if (!live.current) return;
    setNetworks(data.networks || []);
    if (!manualEdited.current) setForm(current => ({ ...current, peerPort: current.peerId ? current.peerPort : data.ports.peerPort, restPort: current.peerId ? current.restPort : data.ports.restPort }));
  }, []);
  useEffect(() => {
    if (preview) { setStatus(null); setLoadError(""); setForm(empty); setAddress(""); setNetworks([]); networkEdited.current = false; manualEdited.current = false; setMessage(""); setError(""); return; }
    let alive = true;
    const load = async () => {
      try { await refreshStatus(); if (alive) setLoadError(""); }
      catch { if (alive) setLoadError("Can't reach Relay on this device. Start the Relay app, then try again."); }
    };
    load();
    refreshNetworks().catch(() => { /* The status error above explains when the local app is unavailable. */ });
    const timer = setInterval(load, 3000);
    return () => { alive = false; clearInterval(timer); };
  }, [preview, refreshStatus, refreshNetworks]);
  const phase = status?.phase;
  const left = phase === "LEFT";
  const changedNetwork = !!status?.settings && networks.length > 0 && status.settings.address !== "127.0.0.1" && !networks.some(n => n.address === status.settings?.address);
  const starting = phase === "STARTING";
  const disabled = preview || busy || starting || !status;
  const connection = preview ? ["This is a design preview", "These are sample settings. Switch to your live network to change this device."]
    : !status ? ["Checking your connection…", "Relay runs on this device. You don't need internet."]
    : left ? ["You left the mesh", "Your received files and device settings are still here. Join a mesh again from the Network map."]
    : !status.configured ? ["Join your first mesh", "Open the Network map to find nearby Relay meshes, or create one for other people to join."]
    : phase === "RESTART_REQUIRED" ? ["Your changes are saved", "Restart Relay below to use them. Finish any file transfers first."]
    : phase === "PEER_ID_CONFLICT" ? ["Two devices have the same ID", "Choose a different Device ID under Advanced settings, then save and reconnect."]
    : changedNetwork ? ["Your network changed", "Choose your current Wi-Fi or Ethernet network below to reconnect. You don't need to enter your mesh password again."]
    : phase === "ERROR" ? ["Relay couldn't connect", "Check your network, then restart Relay. If it still fails, check the help under Advanced settings."]
    : starting ? ["Connecting to your mesh…", "Please wait while Relay starts your connections."]
    : phase === "CONNECTED" ? ["You're connected", "You can chat and send files to reachable devices. See your connections on the Network map."]
    : ["Looking for other devices", "Relay is running. Other members need to be nearby, using the same mesh and password."];
  async function act(action: () => Promise<void>) {
    if (disabled) return;
    setBusy(true); setError(""); setMessage("");
    try { await action(); await refreshStatus(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  function edit(name: string, value: string | number) {
    manualEdited.current = true;
    setForm(current => ({ ...current, [name]: value })); setMessage("");
  }
  const options = <>{networks.map(n => <option key={n.address} value={n.address}>{n.label}</option>)}
    {(!networks.length || status?.settings?.address === "127.0.0.1") && <option value="127.0.0.1">No local network address — Bluetooth can still work</option>}</>;
  return <div className="settings-page">
    <header className="settings-title"><Eyebrow gold>MAKE RELAY YOURS</Eyebrow><h1>Settings</h1><p>Your device name, your connection, and help when something goes wrong.</p></header>
    {preview && <div className="settings-notice" role="status">Design preview · Nothing here changes a real device. Use the live network switch in the navigation to manage your connections.</div>}
    {loadError && <p className="settings-notice bad" role="alert">{loadError}</p>}
    <div className="settings-grid">
      <div className="settings-card"><MyDevice embedded /></div>
      <section className="settings-card settings-connection" aria-labelledby="connection-title">
        <Eyebrow>YOUR CONNECTION</Eyebrow><h2 id="connection-title">{connection[0]}</h2><p>{connection[1]}</p>
        {status?.settings && <dl className="settings-facts"><div><dt>{left || phase === "ERROR" ? "Last saved mesh" : "Mesh name"}</dt><dd>{status.settings.clusterId}</dd></div><div><dt>Device ID</dt><dd>{status.settings.peerId}</dd></div></dl>}
        <Link className="button secondary" href="/">Open Network map <span aria-hidden="true">↗</span></Link>
      </section>
      <section className="settings-card settings-network" aria-labelledby="network-title">
        <div className="settings-card-head"><div><Eyebrow>WI-FI &amp; ETHERNET</Eyebrow><h2 id="network-title">Choose your local network</h2></div><button className="button secondary" disabled={disabled} onClick={() => act(refreshNetworks)}>Refresh networks</button></div>
        <p>Use this if you changed Wi-Fi, plugged in Ethernet, or Relay is using an old address. Other devices must be able to reach this network.</p>
        <form onSubmit={e => { e.preventDefault(); act(async () => { const data = await post("network", { address }); networkEdited.current = false; setMessage(data.message); }); }}>
          <label htmlFor="settings-network">Wi-Fi or Ethernet network</label>
          <div className="settings-network-row"><select id="settings-network" value={address} disabled={disabled || left || !status?.configured} onChange={e => { networkEdited.current = true; setAddress(e.target.value); }}><option value="">Choose a current network</option>{address && address !== "127.0.0.1" && !networks.some(n => n.address === address) && <option value={address} disabled>{address} — no longer available</option>}{options}</select>
          <button className="button primary" type="submit" disabled={disabled || left || !status?.configured || !address || (address !== "127.0.0.1" && !networks.some(n => n.address === address))}>{busy ? "Working…" : "Save and reconnect"}</button></div>
        </form>
        <p className="settings-help">Reconnecting interrupts current Relay transfers. This keeps your mesh password and files, and does not change your computer's Wi-Fi connection.</p>
        {(!status?.configured || left) && !preview && <p className="settings-help">Create or join a mesh on the Network map first.</p>}
      </section>
      <section className="settings-card settings-methods" aria-labelledby="methods-title"><div><Eyebrow>CONNECTION OPTIONS</Eyebrow><h2 id="methods-title">Bluetooth, Wi-Fi Direct &amp; hotspot</h2><p>Check what your device supports, scan for nearby devices, or start a hotspot on the Network map. Relay shows the connection type each link actually uses.</p></div><Link className="button secondary" href="/">Manage connections <span aria-hidden="true">↗</span></Link></section>
    </div>
    {error && <p className="settings-notice bad" role="alert">{error}</p>}
    {message && <p className="settings-notice good" role="status">{message}</p>}
    <details className="settings-card settings-advanced"><summary><span>Advanced settings<small>Manual setup and help with connection problems</small></span><span aria-hidden="true">+</span></summary>
      <div className="settings-advanced-body">
        <h2>Having trouble connecting?</h2><p>Keep Relay open on both devices and use the same mesh password. For Wi-Fi or Ethernet, connect to the same local network. For Bluetooth, make your device visible on the map and accept any pairing prompt from your computer.</p>
        <p>If your firewall asks, allow Relay or Java on your trusted local network. Some guest Wi-Fi networks block connections between devices.</p>
        {status?.configured && !left && <><p className="settings-help">Restarting Relay interrupts current transfers. Your files and saved mesh stay here.</p><button className="button secondary" disabled={disabled} onClick={() => act(async () => { const data = await post("restart", {}); setMessage(data.message); })}>Restart Relay</button></>}
        <details className="settings-manual"><summary>Set up a mesh manually</summary><p>Usually, you only need the Network map. Use this form when you already know the mesh name and password. Everyone must use the same spelling. This device needs its own unique ID.</p>
          <form onSubmit={e => { e.preventDefault(); act(async () => { await post("apply", form); setForm(current => ({ ...current, secret: "" })); setMessage("Your settings are saved."); await post("restart", {}); setMessage("Settings saved. Relay is reconnecting."); }); }}>
            <fieldset disabled={disabled}><div className="settings-form-grid">
              <label>Device ID<input aria-label="Device ID" aria-describedby="manual-id-help" required autoComplete="off" pattern="[A-Za-z0-9_-]{1,40}" value={form.peerId} onChange={e => edit("peerId", e.target.value)} /><small id="manual-id-help">A unique ID for this device, such as laptop-A. Changing it does not rename or delete your received files.</small></label>
              <label>Mesh name<input aria-label="Mesh name" aria-describedby="manual-mesh-help" required pattern="[A-Za-z0-9._-]{1,80}" maxLength={80} autoComplete="off" value={form.clusterId} onChange={e => edit("clusterId", e.target.value)} /><small id="manual-mesh-help">The same name on every device. Use letters, numbers, dots, - or _.</small></label>
              <label className="settings-full">Mesh password<input aria-label="Mesh password" aria-describedby="manual-password-help" required type="password" minLength={16} maxLength={256} autoComplete="new-password" value={form.secret} onChange={e => edit("secret", e.target.value)} /><small id="manual-password-help">At least 16 characters. Your saved password is never shown here.</small></label>
              <label className="settings-full">Local network<select aria-label="Local network" required value={form.address} onChange={e => edit("address", e.target.value)}><option value="">Choose a current network</option>{options}</select></label>
            </div>
            <details className="settings-ports"><summary>Port numbers</summary><p>Leave these as they are unless another app is already using a port.</p><div className="settings-form-grid"><label>File service port<input aria-label="File service port" required type="number" min={1} max={63535} value={form.peerPort} onChange={e => edit("peerPort", Number(e.target.value))} /><small>Relay also uses this number + 2000 for mesh connections.</small></label><label>Local app port<input aria-label="Local app port" required type="number" min={1} max={65535} value={form.restPort} onChange={e => edit("restPort", Number(e.target.value))} /><small>The internal service used by this app.</small></label></div></details>
            <p className="settings-help">Saving reconnects Relay and interrupts current transfers. Existing received files stay on this device.</p><button className="button primary" type="submit">{busy ? "Working…" : "Save manual settings and reconnect"}</button></fieldset>
          </form>
        </details>
        {status?.error && <details className="settings-technical"><summary>Details from Relay</summary><p>{status.error}</p></details>}
      </div>
    </details>
    <p className="settings-footer">Files are sent only to the people you choose. Joining a mesh does not download old files or back up other people's files on your device.</p>
  </div>;
}
