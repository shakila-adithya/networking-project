"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { request, transportLabel, useMesh } from "@/lib/mesh";
export type PublicDevice = { deviceId: string; name: string; meshId: string; address: string; kind: string; verified: false };
export type PublicMesh = { meshId: string; peerId: string; address: string; kind: string; verified: false };
type Visibility = { displayName?: string; phase?: string; deviceId: string; visible: boolean; bluetoothAvailable: boolean; bluetoothReason: string; error: string };
type Discovery = { devices: PublicDevice[]; meshes: PublicMesh[]; visibility: Visibility | null };
const empty: Discovery = { devices: [], meshes: [], visibility: null };
export function useNetworkDiscovery() {
  const { preview } = useMesh();
  const [data, setData] = useState<Discovery>(empty);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const inFlight = useRef<AbortController | null>(null);
  const revision = useRef(0);
  useEffect(() => {
    if (preview) { setData(empty); setError(""); return; }
    let alive = true;
    const load = async () => {
      if (inFlight.current) return;
      const controller = new AbortController(); inFlight.current = controller; const startedAtRevision = revision.current;
      const timeout = setTimeout(() => controller.abort(), 8000);
      try {
        const result = await request("/discovery", { signal: controller.signal });
        if (!result.visibility || typeof result.visibility.visible !== "boolean") throw new Error("Visibility service unavailable");
        if (alive && startedAtRevision === revision.current) { setData({ devices: result.devices || [], meshes: result.meshes || [], visibility: result.visibility || null }); setError(""); }
      } catch { if (alive) setError("Nearby discovery is unavailable. Start the updated Relay app on this device."); }
      finally { clearTimeout(timeout); if (inFlight.current === controller) inFlight.current = null; }
    };
    void load();
    const timer = setInterval(() => { if (document.visibilityState === "visible") void load(); }, 4000);
    return () => { alive = false; clearInterval(timer); inFlight.current?.abort(); inFlight.current = null; };
  }, [preview, retry]);
  const reload = useCallback(() => setRetry(value => value + 1), []);
  const update = useCallback((visibility: Visibility) => { revision.current++; setData(current => ({ ...current, visibility })); }, []);
  return { ...data, error, update, reload };
}
export function MapVisibility({ discovery }: { discovery: ReturnType<typeof useNetworkDiscovery> }) {
  const { preview, device, deviceName, neighbours, links, saveDevice, refresh } = useMesh();
  const [profile, setProfile] = useState<{ deviceId?: string; customName?: string } | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  useEffect(() => {
    if (preview || device) return;
    const controller = new AbortController();
    request("/setup/device", { signal: controller.signal }).then(setProfile).catch(() => { /* Discovery reports service availability below. */ });
    return () => controller.abort();
  }, [preview, device]);
  const known = preview || !!discovery.visibility;
  const visible = preview ? !!device?.visible : !!discovery.visibility?.visible;
  const id = device?.deviceId || discovery.visibility?.deviceId || profile?.deviceId;
  const name = device?.customName || discovery.visibility?.displayName || profile?.customName || device?.displayName || (deviceName !== "You" ? deviceName : "This device");
  const activeMethods = [...new Set(Object.values(links).map(transportLabel))].join(" · ");
  const failure = error || (!preview && (discovery.error || discovery.visibility?.error));
  async function toggle() {
    setBusy(true); setError("");
    try {
      if (preview) await saveDevice({ visible: !visible });
      else { discovery.update(await request("/visibility", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ visible: !visible }) })); await refresh(); }
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <section className="map-visibility" aria-labelledby="map-device-title">
    <div className="map-device-header">
      <span className="map-device-icon" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="4" width="16" height="12" rx="2"/><path d="M2 20h20M9 16v4m6-4v4"/></svg></span>
      <div className="map-device-identity"><span className="map-device-eyebrow">YOUR DEVICE</span><h2 id="map-device-title">{name}</h2>{id && <span className="map-device-id">Device ID <code>{id}</code></span>}</div>
    </div>
    <div className="map-device-connection"><span className={`status-dot ${neighbours.length ? "green" : "muted"}`} aria-hidden="true"/><span>{preview ? "Example device · Design preview" : neighbours.length ? `Connected to ${neighbours.length} device${neighbours.length === 1 ? "" : "s"}${activeMethods ? ` · ${activeMethods}` : ""}` : "No devices connected"}</span><span className={`map-device-presence ${known && visible ? "is-visible" : ""}`}><i aria-hidden="true"/>{preview ? "Preview" : !known ? "Status unavailable" : visible ? "Visible" : "Hidden"}</span></div>
    <div className="map-device-visibility-row">
      <div><label htmlFor="map-visibility-switch">Visible to nearby devices</label><p id="visibility-effect">{preview ? "Try this switch in the preview. It won't change your device." : !known ? "Connect to the local Relay service to change visibility." : visible ? "Other Relay devices can find your device on their map." : "Other Relay devices can't find your device on their map."}</p></div>
      <button id="map-visibility-switch" className="switch" type="button" role="switch" aria-label="Visible to nearby devices" aria-describedby="visibility-effect visibility-leave-note" aria-checked={visible} disabled={busy || (!preview && !discovery.visibility)} onClick={toggle} />
    </div>
    <p id="visibility-leave-note" className="map-device-note">Turning this off leaves your mesh and pauses transfers. Turning it on again doesn't join a mesh.</p>
    {!preview && discovery.visibility && !discovery.visibility.bluetoothAvailable && <p className="map-device-note">Bluetooth: {discovery.visibility.bluetoothReason || "Unavailable"}. You can still be visible over Wi-Fi or Ethernet.</p>}
    {failure && <div className="map-device-error"><p role="alert">{failure}</p>{discovery.error && <button className="button secondary" type="button" onClick={discovery.reload}>Try again</button>}</div>}
  </section>;
}
