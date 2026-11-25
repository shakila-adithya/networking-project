"use client";

import { useEffect, useRef, useState } from "react";
import { request, useMesh } from "@/lib/mesh";

export function DeviceIdSettings({ onSaved }: { onSaved: () => void }) {
  const { preview, refresh } = useMesh();
  const [savedId, setSavedId] = useState("");
  const [id, setId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [message, setMessage] = useState("");
  const [loaded, setLoaded] = useState(false);
  const touched = useRef(false);
  useEffect(() => {
    if (preview) { setLoaded(false); setSavedId(""); setId(""); setError(""); setLoadError(""); setMessage(""); touched.current = false; return; }
    let alive = true;
    const load = async () => {
      try {
        const profile = await request("/setup/device");
        if (!alive) return;
        setSavedId(profile.deviceId);
        if (!touched.current) setId(profile.deviceId);
        setLoaded(true);
        setLoadError("");
      } catch { if (alive) { setLoaded(false); setLoadError("Can't load your device ID. Check that Relay is running."); } }
    };
    void load();
    const timer = setInterval(() => { if (document.visibilityState === "visible") void load(); }, 3000);
    return () => { alive = false; clearInterval(timer); };
  }, [preview]);
  const valid = /^[A-Za-z0-9_-]{1,40}$/.test(id);
  async function save() {
    if (preview || busy || !valid || !loaded || id === savedId) return;
    setBusy(true); setError(""); setMessage("");
    try {
      const result = await request("/setup/identity", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ deviceId: id }) });
      setSavedId(result.deviceId); setId(result.deviceId); touched.current = false;
      setMessage(result.message);
      onSaved();
      void refresh();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <section aria-labelledby="device-id-title" className="device-id-settings">
    <h2 id="device-id-title">Your device ID</h2>
    <p>Each computer needs a different ID. Use this to fix two devices showing the same ID.</p>
    <form onSubmit={event => { event.preventDefault(); void save(); }}>
      <label htmlFor="device-id">Device ID</label>
      <div className="name-row">
        <input id="device-id" className="text-input" value={id} maxLength={40} autoComplete="off" spellCheck={false}
          disabled={preview || busy || !loaded} aria-describedby="device-id-help"
          onChange={event => { touched.current = true; setId(event.target.value); setError(""); setMessage(""); }} />
        <button className="button primary" type="submit" disabled={preview || busy || !loaded || !valid || id === savedId}>
          {busy ? "Saving…" : "Save device ID"}
        </button>
      </div>
      <p id="device-id-help" className="hint">1–40 letters, numbers, underscores or hyphens, for example Windows_PC_2. Changing your ID reconnects an active mesh and interrupts current transfers. Received files stay saved.</p>
      {loaded && !valid && <p className="field-error">Enter 1–40 letters, numbers, underscores or hyphens.</p>}
      {error && <p role="alert" className="field-error">{error}</p>}
      {loadError && <p role="alert" className="field-error">{loadError}</p>}
      {message && <p role="status" className="saved">{message}</p>}
    </form>
  </section>;
}
