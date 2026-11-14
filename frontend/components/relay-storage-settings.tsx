"use client";
import { useEffect, useRef, useState } from "react";
import { request, useMesh } from "@/lib/mesh";
import { bytes } from "./ui";
type Policy = { quotaBytes: number; retentionHours: number };
type Usage = { available: boolean; usedBytes?: number; pending?: number; expired?: number; error?: string };
export function RelayStorageSettings() {
  const { preview } = useMesh();
  const [policy, setPolicy] = useState<Policy>({ quotaBytes: 1_000_000_000, retentionHours: 24 });
  const [usage, setUsage] = useState<Usage | null>(null);
  const [limit, setLimit] = useState("1"), [hours, setHours] = useState("24");
  const [busy, setBusy] = useState(false), [error, setError] = useState(""), [saved, setSaved] = useState(false);
  const dirty = useRef(false);
  useEffect(() => {
    if (preview) { setUsage(null); setError(""); setSaved(false); return; }
    let alive = true; let pending = false; const controller = new AbortController();
    const load = async () => {
      if (pending) return; pending = true;
      try {
        const result = await request("/relay-storage", { signal: controller.signal });
        if (!alive) return;
        if (!result.policy) { setUsage({ available: false, error: "Start the updated Relay app to configure temporary relay storage." }); return; }
        setPolicy(result.policy); setUsage(result.status);
        if (!dirty.current) { setLimit(String(result.policy.quotaBytes / 1_000_000_000)); setHours(String(result.policy.retentionHours)); }
      } catch (e) { if (alive) setError((e as Error).message); }
      finally { pending = false; }
    };
    void load(); const timer = setInterval(() => { if (document.visibilityState === "visible") void load(); }, 15000);
    return () => { alive = false; clearInterval(timer); controller.abort(); };
  }, [preview]);
  async function save() {
    if (preview || busy) return;
    setBusy(true); setError(""); setSaved(false);
    try {
      const result = await request("/relay-storage", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ quotaBytes: Math.round(Number(limit) * 1_000_000_000), retentionHours: Number(hours) }) });
      setPolicy(result.policy); setUsage(result.status); dirty.current = false; setSaved(true);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <section className="settings-card settings-relay-storage" aria-labelledby="relay-storage-title">
    <h2 id="relay-storage-title">Help carry messages and files</h2>
    <p>While you're in a mesh, Relay can temporarily hold encrypted messages and file chunks for other members. It forwards them when a connection becomes available.</p>
    <div className="relay-storage-usage"><b>{usage?.available ? `${bytes(usage.usedBytes || 0)} used of ${bytes(policy.quotaBytes)}` : preview ? "Design preview" : "Temporary relay storage"}</b>{usage?.available && <small>{usage.pending || 0} pending items · {usage.expired || 0} expired</small>}</div>
    {usage?.error && <p className="settings-help">{usage.error}</p>}
    <form onSubmit={event => { event.preventDefault(); void save(); }}>
      <fieldset disabled={preview || busy}><div className="settings-form-grid">
        <label>Storage limit (GB)<input aria-label="Relay storage limit in GB" type="number" required min="0.01" max="16" step="0.01" value={limit} onChange={e => { dirty.current = true; setLimit(e.target.value); setSaved(false); }} /></label>
        <label>Keep relay data for (hours)<input aria-label="Relay data expiry in hours" type="number" required min="1" max="168" value={hours} onChange={e => { dirty.current = true; setHours(e.target.value); setSaved(false); }} /></label>
      </div><button className="button secondary" type="submit">{busy ? "Saving…" : "Save relay storage limits"}</button></fieldset>
    </form>
    <p className="settings-help">Defaults: 1 GB and 24 hours. The sender keeps its original until the recipient confirms delivery. Expiry removes temporary relay copies, not your received files.</p>
    {error && <p role="alert" className="field-error">{error}</p>}{saved && <p role="status" className="saved">Relay storage limits saved.</p>}
  </section>;
}
