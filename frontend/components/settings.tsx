"use client";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { request, useMesh } from "@/lib/mesh";
import { RelayStorageSettings } from "./relay-storage-settings";
import { MyDevice } from "./my-device";
import { Eyebrow } from "./ui";

type Network = { address: string; label: string };
type Saved = { peerId: string; clusterId: string; address: string; peerPort: number; restPort: number; discoveryGroup: string; discoveryPort: number };
type Status = { configured: boolean; phase: string; settings?: Saved; error?: string };
const post = (path: string, body: object) => request(`/setup/${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export function Settings() {
  const { preview } = useMesh();
  const live = useRef(!preview);
  live.current = !preview;
  const [status, setStatus] = useState<Status | null>(null);
  const [networks, setNetworks] = useState<Network[]>([]);
  const [networksError, setNetworksError] = useState("");
  const [networksLoaded, setNetworksLoaded] = useState(false);
  const networkRequest = useRef<AbortController | null>(null);
  const [checkingNetworks, setCheckingNetworks] = useState(false);
  const [address, setAddress] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [message, setMessage] = useState("");
  const networkEdited = useRef(false);
  const refreshStatus = useCallback(async () => {
    const data: Status = await request("/setup");
    if (!live.current) return;
    setStatus(data);
    if (data.settings) {
      if (!networkEdited.current) setAddress(data.settings.address);
    }
  }, []);
  const refreshNetworks = useCallback(async () => {
    if (!live.current || networkRequest.current) return;
    const controller = new AbortController();
    networkRequest.current = controller;
    setCheckingNetworks(true);
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const data = await request("/setup/networks", { signal: controller.signal });
      if (!live.current || networkRequest.current !== controller) return;
      setNetworks(data.networks || []);
      setNetworksError("");
    } catch (e) {
      if (!live.current || networkRequest.current !== controller) return;
      setNetworks([]);
      setNetworksError(controller.signal.aborted ? "Checking your connections took too long." : `Couldn't check your connections. ${(e as Error).message}`);
    } finally {
      clearTimeout(timeout);
      if (networkRequest.current === controller) {
        networkRequest.current = null;
        if (live.current) { setNetworksLoaded(true); setCheckingNetworks(false); }
      }
    }
  }, []);
  useEffect(() => {
    if (preview) { setStatus(null); setLoadError(""); setAddress(""); setNetworks([]); setNetworksError(""); setNetworksLoaded(false); setCheckingNetworks(false); networkEdited.current = false; setMessage(""); setError(""); return; }
    let alive = true;
    const load = async () => {
      try { await refreshStatus(); if (alive) setLoadError(""); }
      catch { if (alive) setLoadError("Can't reach Relay on this device. Start the Relay app, then try again."); }
    };
    load();
    void refreshNetworks();
    const timer = setInterval(() => { if (document.visibilityState === "visible") void load(); }, 3000);
    return () => {
      alive = false;
      clearInterval(timer);
      const pending = networkRequest.current;
      networkRequest.current = null;
      pending?.abort();
    };
  }, [preview, refreshStatus, refreshNetworks]);
  const phase = status?.phase;
  const left = phase === "LEFT";
  const changedNetwork = !!status?.settings && networks.length > 0 && status.settings.address !== "127.0.0.1" && !networks.some(n => n.address === status.settings?.address);
  const starting = phase === "STARTING";
  const disabled = preview || busy || starting || !status || !!loadError;
  const canReconnect = !!status?.configured && !left;
  // A stale saved address must not leave the only current connection unselected.
  const chosenAddress = address === "127.0.0.1" || networks.some(n => n.address === address)
    ? address : networks.length === 1 ? networks[0].address : "";
  const connection = preview ? ["This is a design preview", "These are sample settings. Switch to your live network to change this device."]
    : !status ? ["Checking your connection…", "Relay runs on this device. You don't need internet."]
    : left ? ["You left the mesh", "Your received files and device settings are still here. Join a mesh again from the Network map."]
    : !status.configured ? ["Join your first mesh", "Open the Network map and connect to a nearby Relay device. Choose a shared password the first time; later devices join with it."]
    : phase === "RESTART_REQUIRED" ? ["Your changes are saved", "Restart Relay below to use them. Finish any file transfers first."]
    : phase === "PEER_ID_CONFLICT" ? ["Two devices have the same ID", "Leave the mesh on the Network map and join again. If it keeps happening, remove this device\u2019s saved ID file and restart Relay."]
    : changedNetwork ? ["Your network changed", "Choose your new connection below, then select Use this connection."]
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
        <div className="settings-card-head">
          <div><h2 id="network-title">Your network connection</h2><p>Switched Wi-Fi, changed your phone hotspot, or plugged in Ethernet? Select Refresh connections, then choose the connection to use.</p></div>
          <button className="button secondary" disabled={preview || checkingNetworks} aria-busy={checkingNetworks} onClick={() => void refreshNetworks()}>{checkingNetworks ? "Refreshing…" : "Refresh connections"}</button>
        </div>
        {networksError && <p className="field-error" role="alert">{networksError} Select Refresh connections to try again.</p>}
        {canReconnect || preview ? <>
          {changedNetwork && <p className="settings-network-note">Relay's old connection is no longer available. Choose the new one below.</p>}
          <form className="settings-network-form" onSubmit={e => {
            e.preventDefault();
            if (!chosenAddress || networksError || !networksLoaded || checkingNetworks) return;
            act(async () => {
              await post("network", { address: chosenAddress });
              networkEdited.current = false;
              setMessage("Relay is reconnecting using this connection. Your mesh and files stay saved.");
            });
          }}>
            <label htmlFor="settings-network">Connection to use</label>
            <div className="settings-network-row">
              <select id="settings-network" value={chosenAddress} disabled={disabled || checkingNetworks || !networksLoaded || !!networksError} onChange={e => { networkEdited.current = true; setAddress(e.target.value); }}>
                <option value="">{!networksLoaded && !preview ? "Checking your connections…" : "Choose a connection"}</option>{options}
              </select>
              <button className="button primary" type="submit" disabled={disabled || checkingNetworks || !networksLoaded || !!networksError || !chosenAddress}>{busy ? "Connecting…" : "Use this connection"}</button>
            </div>
            <p className="settings-help">Updating interrupts current Relay transfers. Your mesh password and received files stay saved.</p>
          </form>
        </> : <>
          {!networksLoaded ? <p className="settings-help" role="status">Checking your connections…</p> : !networksError && networks.length > 0 && <ul className="settings-detected-connections" aria-label="Connections found">{networks.map(network => <li key={network.address}>{network.label}</li>)}</ul>}
        </>}
        {!preview && networksLoaded && !networksError && !networks.length && <p className="settings-help">No Wi-Fi or Ethernet connection found. Connect in your laptop's network settings, then select Refresh connections. Bluetooth may still be available on the Network map.</p>}
      </section>
      <RelayStorageSettings />
    </div>
    {error && <p className="settings-notice bad" role="alert">{error}</p>}
    {message && <p className="settings-notice good" role="status">{message}</p>}
    <details className="settings-card settings-advanced"><summary><span>Advanced settings<small>Help with connection problems</small></span><span aria-hidden="true">+</span></summary>
      <div className="settings-advanced-body">
        <h2>Having trouble connecting?</h2><p>Keep Relay open on both devices and use the same mesh password. For Wi-Fi or Ethernet, connect to the same local network. For Bluetooth, make your device visible on the map and accept any pairing prompt from your computer.</p>
        <p>If your firewall asks, allow Relay or Java on your trusted local network. Some guest Wi-Fi networks block connections between devices.</p>
        {status?.configured && !left && <><p className="settings-help">Restarting Relay interrupts current transfers. Your files and saved mesh stay here.</p><button className="button secondary" disabled={disabled} onClick={() => act(async () => { const data = await post("restart", {}); setMessage(data.message); })}>Restart Relay</button></>}
        {status?.error && <details className="settings-technical"><summary>Details from Relay</summary><p>{status.error}</p></details>}
      </div>
    </details>
    <p className="settings-footer">Received files stay with their chosen recipients. Relay may temporarily carry encrypted data for other members within your storage limits; it does not create permanent backups.</p>
  </div>;
}
