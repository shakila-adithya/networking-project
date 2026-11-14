"use client";
import { useEffect, useRef, useState } from "react";
import { useMesh, request } from "@/lib/mesh";
import { Icon, Eyebrow, Avatar, Search, FileTile, bytes } from "./ui";
type Transfer = {
  name: string;
  detail: string;
  state: string;
  progress?: number;
  id?: string;
  tid?: string; // mesh transfer id (live mode)
  size?: number;
  resumable?: boolean;
  clientKey?: string;
};
type TransferStatus = {
  state: string;
  percent: number;
  stalled: boolean;
  error: string;
};
// Maps the backend's transfer status onto what the activity list shows.
function applyStatus(t: Transfer, s: TransferStatus): Transfer {
  const peer = t.detail.replace(/^To /, "").split(" · ")[0];
  const sent = t.size ? ` · ${bytes(t.size)}` : "";
  if (s.state === "DELIVERED")
    return { ...t, state: "Delivered", progress: undefined, resumable: false, detail: `To ${peer}${sent}` };
  if (s.state === "FAILED")
    return { ...t, state: "Failed", progress: undefined, resumable: true, detail: s.error || "Transfer stopped" };
  if (s.stalled || s.state === "WAITING")
    return { ...t, state: "Waiting", progress: undefined, resumable: false, detail: `To ${peer} · waiting for a route · ${s.percent}% sent` };
  return { ...t, state: "Sending", progress: s.percent, resumable: false, detail: `To ${peer}${sent}` };
}
const samples: Transfer[] = [
  {
    name: "Community map.pdf",
    detail: "To Amaya · via Maya",
    state: "Sending",
    progress: 68,
    size: 5 * 1024 * 1024,
  },
  { name: "Meeting point.jpg", detail: "To Maya · 840 KB", state: "Delivered" },
  {
    name: "First aid guide.pdf",
    detail: "From Ravi · 2.4 MB",
    state: "Received",
  },
  { name: "Photos.zip", detail: "To Kasun · 18 MB", state: "Waiting" },
  { name: "Local guide.pdf", detail: "From Maya · 1.2 MB", state: "Receiving", progress: 32, size: Math.round(1.2 * 1024 * 1024) },
  { name: "Notes.txt", detail: "To Ravi · transfer interrupted", state: "Failed" },
];
export function Files() {
  const { preview, peers, files, refresh } = useMesh(),
    [picked, setPicked] = useState<File[]>([]),
    [selected, setSelected] = useState<string[]>([]),
    [query, setQuery] = useState(""),
    [filter, setFilter] = useState("All"),
    [activity, setActivity] = useState<Transfer[]>([]),
    [remoteActivity, setRemoteActivity] = useState<Transfer[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [showAll, setShowAll] = useState(false),
    [dismissSample, setDismissSample] = useState(false);
  useEffect(() => {
    setActivity([]);
    setRemoteActivity([]);
    setPicked([]);
    setError("");
  }, [preview]);
  // Follow running mesh transfers: progress, "waiting for a route", delivered, failed.
  const activeKey = activity
    .filter((t) => t.tid && (t.state === "Sending" || t.state === "Waiting") && !t.resumable)
    .map((t) => t.tid)
    .join(",");
  useEffect(() => {
    if (preview || !activeKey) return;
    const ids = activeKey.split(",");
    const timer = setInterval(async () => {
      for (const tid of ids) {
        try {
          const status = (await request("/mesh/transfers/" + tid)) as TransferStatus;
          setActivity((a) => a.map((t) => (t.tid === tid ? applyStatus(t, status) : t)));
        } catch {
          setActivity((a) =>
            a.map((t) =>
              t.tid === tid
                ? { ...t, state: "Waiting", progress: undefined, resumable: false, detail: "This transfer is no longer tracked (the node restarted)" }
                : t,
            ),
          );
        }
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [activeKey, preview]);
  // Include transfers started elsewhere and actual receiver progress; no simulated live states.
  useEffect(() => {
    if (preview) return;
    let cancelled = false;
    async function load() {
      try {
        const response = await request("/mesh/transfers");
        const outgoing = (response.transfers || []).map((status: TransferStatus & { transferId: string; fileName: string; to: string; size: number }) =>
          applyStatus({ name: status.fileName, detail: `To ${status.to}`, tid: status.transferId, size: status.size, state: "Sending" }, status));
        const incoming = (response.incoming || []).map((status: { fileName: string; from: string; fileId: string; percent: number; size: number; state: string; error: string }) => ({
          name: status.fileName, size: status.size, detail: `From ${status.from} · ${bytes(status.size)}${status.error ? ` · ${status.error}` : ""}`,
          state: status.state === "RECEIVED" ? "Received" : status.state === "FAILED" ? "Failed" : status.state === "WAITING" ? "Waiting" : "Receiving",
          progress: status.state === "RECEIVING" ? status.percent : undefined,
          id: status.state === "RECEIVED" ? status.fileId : undefined,
        }));
        if (!cancelled) setRemoteActivity([...outgoing, ...incoming]);
      } catch { /* Existing activity remains visible if the node temporarily stops responding. */ }
    }
    void load();
    const timer = setInterval(load, 1000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [preview]);
  async function resume(tid: string) {
    try {
      const status = (await request("/mesh/transfers/" + tid + "/resume", { method: "POST" })) as TransferStatus;
      setActivity((a) => a.map((t) => (t.tid === tid ? applyStatus(t, status) : t)));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  const input = useRef<HTMLInputElement>(null),
    recipients = peers.filter((p) => selected.includes(p.id)),
    hasSample = preview && !picked.length && !dismissSample,
    total = picked.reduce((s, f) => s + f.size, 0),
    names = recipients.map((p) => p.name).join(", ");
  const liveActivity = [...activity.filter(t => !t.tid || !remoteActivity.some(remote => remote.tid === t.tid)), ...remoteActivity];
  const transfers: Transfer[] = preview ? [...activity, ...samples] : [
    ...liveActivity,
    ...files.filter(file => !liveActivity.some(t => t.id === file.fileId)).map(file => ({
      name: file.fileName, detail: `Saved on this device · ${bytes(file.size)}`, state: "Received", id: file.fileId,
    })),
  ];
  const filters = ["All", "Sending", "Receiving", "Sent", "Received", "Waiting for a route", "Failed"];
  const matchesFilter = (t: Transfer) => filter === "All" || t.state === (filter === "Sent" ? "Delivered" : filter === "Waiting for a route" ? "Waiting" : filter);
  const visibleTransfers = transfers.filter(matchesFilter);
  function add(list: FileList | null) {
    if (!list) return;
    const added = Array.from(list);
    setPicked((prev) => [...prev, ...added.filter(file => !prev.some(existing => existing.name === file.name && existing.size === file.size && existing.lastModified === file.lastModified))]);
    setDismissSample(true);
    setError("");
  }
  async function send() {
    if (!picked.length) {
      setError(
        preview
          ? "Choose a real file to try this interaction. The sample PDF is a design reference."
          : "Choose at least one file.",
      );
      return;
    }
    if (!recipients.length) {
      setError("Choose at least one reachable recipient.");
      return;
    }
    setBusy(true);
    setError("");
    const failures: string[] = [];
    try {
      for (const file of picked) {
        if (file.size > 100 * 1024 * 1024)
          throw new Error("Files must be smaller than 100 MB.");
        if (preview) {
          setActivity((a) => [
            {
              name: file.name,
              detail: `To ${names} · preview only`,
              state: "Waiting",
            },
            ...a,
          ]);
          continue;
        }
        for (const peer of recipients) {
          const clientKey = crypto.randomUUID();
          setActivity(previous => [{ name: file.name, detail: `To ${peer.name} · ${bytes(file.size)}`, state: "Sending", clientKey }, ...previous]);
          try {
            const r = await request(
              "/files?recipient=" + encodeURIComponent(peer.id),
              {
                method: "POST",
                headers: {
                  "X-File-Name": encodeURIComponent(file.name),
                  "Content-Type": "application/octet-stream",
                },
                body: file,
              },
            );
            if (r.shareStatus === "INCOMPLETE" || r.failed?.length)
              throw new Error(
                r.message || "Some recipients did not accept this file.",
              );
            const started = (
              (r.transfers || []) as { peer: string; transferId: string }[]
            ).find((t) => t.peer === peer.id);
            setActivity((a) => [
              started
                ? {
                    name: file.name,
                    detail: `To ${peer.name} · ${bytes(file.size)}`,
                    state: "Sending",
                    progress: 0,
                    tid: started.transferId,
                    size: file.size,
                  }
                : {
                    name: file.name,
                    detail: `To ${peer.name} · ${bytes(file.size)}`,
                    state: "Delivered",
                  },
              ...a.filter(item => item.clientKey !== clientKey),
            ]);
          } catch (e) {
            // One unreachable recipient must not stop the others.
            setActivity(previous => previous.map(item => item.clientKey === clientKey ? { ...item, state: "Failed", detail: `To ${peer.name} · ${(e as Error).message}` } : item));
            failures.push(`${peer.name}: ${(e as Error).message}`);
          }
        }
      }
      if (failures.length) throw new Error(failures.join(" · "));
      setPicked([]);
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  // Only the design preview preselects its sample person; a live device must never be chosen silently.
  useEffect(() => {
    setSelected(preview ? ["A"] : []);
  }, [preview]);
  return (
    <section className="files-page">
      <div className="files-title">
        <div>
          <Eyebrow gold>OFFLINE FILE SHARING</Eyebrow>
          <h1>Share beyond your range.</h1>
          <p>
            Choose a file and someone reachable through your{" "}
            {preview ? "Bluetooth" : "local"} mesh.
          </p>
        </div>
        <span className="mesh-count">
          <Icon name="bluetooth-icon-3" />
          {preview ? 6 : peers.filter((p) => p.status === "ALIVE").length + 1} devices in your mesh
        </span>
      </div>
      <div className="file-workspace">
        <div className="new-transfer">
          <Eyebrow gold>NEW TRANSFER</Eyebrow>
          <h2>Send something over.</h2>
          <p>Select your files and choose who should receive them.</p>
          <div className="transfer-steps">
            <section className="step">
              <div className="step-head">
                <span className="step-number">1</span>
                <div>
                  <h3>Add files</h3>
                  <p>Choose what you want to send</p>
                </div>
              </div>
              <hr />
              <input
                ref={input}
                type="file"
                multiple
                hidden
                onChange={(e) => {
                  add(e.target.files);
                  e.target.value = "";
                }}
              />
              <button
                className="dropzone"
                onClick={() => input.current?.click()}
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  add(e.dataTransfer.files);
                }}
              >
                <Icon name="upload-icon-tile" />
                <b>Drop files here</b>
                <span>or choose files from this device</span>
                <strong>Browse files</strong>
              </button>
              <Eyebrow>READY TO SEND</Eyebrow>
              <div className="selected-files">
                {hasSample && (
                  <div className="selected-file">
                    <FileTile name="Community map.pdf" />
                    <div>
                      <b>Community map.pdf</b>
                      <small>5 MB · Ready to send</small>
                    </div>
                    <button
                      className="icon-button"
                      onClick={() => setDismissSample(true)}
                      aria-label="Remove sample file"
                    >
                      <Icon name="close-icon" />
                    </button>
                  </div>
                )}
                {picked.map((f, i) => (
                  <div className="selected-file" key={i}>
                    <FileTile name={f.name} />
                    <div>
                      <b>{f.name}</b>
                      <small>{bytes(f.size)} · Ready to send</small>
                    </div>
                    <button
                      className="icon-button"
                      onClick={() =>
                        setPicked((v) => v.filter((_, j) => i !== j))
                      }
                      aria-label={`Remove ${f.name}`}
                    >
                      <Icon name="close-icon" />
                    </button>
                  </div>
                ))}
                {!hasSample && !picked.length && (
                  <p className="empty">No files selected</p>
                )}
              </div>
              <div className="file-summary">
                <div>
                  <b>
                    {hasSample ? 1 : picked.length} file
                    {picked.length > 1 ? "s" : ""} selected
                  </b>
                  <small>Total size: {hasSample ? "5 MB" : bytes(total)}</small>
                </div>

              </div>
            </section>
            <section className="step recipients">
              <div className="step-head">
                <span className="step-number">2</span>
                <div>
                  <h3>Choose recipients</h3>
                  <p>Select one or more people in the mesh</p>
                </div>
                <span className="selected-count">
                  <i className="status-dot green" />
                  {recipients.length} selected
                </span>
              </div>
              <hr />
              <Search
                value={query}
                onChange={setQuery}
                placeholder="Search people or devices"
                icon="search-icon-2"
              />
              <div className="row between recipients-label">
                <Eyebrow>REACHABLE THROUGH THE MESH</Eyebrow>
                <small>{peers.length} people</small>
              </div>
              <div className="recipient-list">
                {peers
                  .filter((p) =>
                    p.name.toLowerCase().includes(query.toLowerCase()),
                  )
                  .slice(0, showAll ? undefined : 4)
                  .map((p) => (
                    <button
                      key={p.id}
                      className={`recipient ${selected.includes(p.id) ? "checked" : ""}`}
                      onClick={() =>
                        setSelected((v) =>
                          v.includes(p.id)
                            ? v.filter((id) => id !== p.id)
                            : [...v, p.id],
                        )
                      }
                      aria-pressed={selected.includes(p.id)}
                    >
                      <span className="avatar-state">
                        <Avatar
                          name={p.name}
                          size={34}
                          gold={selected.includes(p.id)}
                        />
                        <i className={`status-dot ${p.tone}`} />
                      </span>
                      <div>
                        <b>{p.name}</b>
                        <small>{p.detail}</small>
                      </div>
                      {selected.includes(p.id) ? (
                        <Icon name="select-amaya" />
                      ) : (
                        <span className="checkbox" />
                      )}
                    </button>
                  ))}
                {!peers.length && (
                  <p className="empty">
                    No nearby recipients. Open the Network map to connect to other devices.
                  </p>
                )}
              </div>
              <div className="recipient-footer">
                <Icon name="refresh-icon-2" />
                <span>Routes may change as devices move.</span>
                <button onClick={() => setShowAll(!showAll)}>
                  {showAll ? "Show less" : `See all ${peers.length}`}
                </button>
              </div>
            </section>
          </div>
          <div className="transfer-summary">
            <Icon name="file-icon-5" />
            <div>
              <b>
                {hasSample
                  ? "1 file · 5 MB"
                  : `${picked.length} files · ${bytes(total)}`}
              </b>
              <small>
                {recipients.length
                  ? `Sending to ${names}${preview ? " · currently reachable via Maya" : ""}`
                  : "Choose a recipient to continue"}
              </small>
            </div>
            <button
              className="button primary"
              onClick={send}
              disabled={busy || !recipients.length}
            >
              <Icon name="send-icon" />
              {busy
                ? "Sending…"
                : `Send to ${recipients.length === 1 ? names : `${recipients.length} people`}`}
            </button>
          </div>
          {error && (
            <p role="alert" className="error-text">
              {error}
            </p>
          )}
        </div>
        <aside className="transfer-activity">
          <Eyebrow>TRANSFER ACTIVITY</Eyebrow>
          <h2>Recent transfers</h2>
          <p>Keep track of files moving through the mesh.</p>
          <div className="filters" role="group" aria-label="Filter transfers">
            {filters.map((v) => (
              <button
                key={v}
                onClick={() => setFilter(v)}
                data-state={v}
                aria-pressed={v === filter}
                className={v === filter ? "active" : ""}
              >
                {v}
              </button>
            ))}
          </div>
          <div className="transfers">
            {visibleTransfers.map((t, i) => (
                <div
                  key={i}
                  className={`transfer-item ${t.progress !== undefined ? "in-progress" : ""}`}
                >
                  <div className="row">
                    <FileTile name={t.name} />
                    <div className="transfer-description">
                      <b title={t.name}>{t.name}</b>
                      <small>{t.detail}</small>
                      <span className={`transfer-status ${t.state === "Delivered" ? "delivered" : t.state === "Received" ? "received" : t.state === "Failed" ? "failed" : t.state === "Waiting" ? "waiting" : "moving"}`}>
                        <i className="status-dot" />{t.state === "Waiting" ? "Waiting for a route" : t.state}
                      </span>
                    </div>
                    {t.progress !== undefined ? (
                      <b className="gold-text">{t.progress}%</b>
                    ) : t.tid && t.resumable ? (
                      <button className="button" onClick={() => resume(t.tid!)}>
                        Resume
                      </button>
                    ) : t.id ? (
                      <a
                        href={`/api/files/${t.id}/download`}
                        className="icon-button"
                        aria-label={`Download ${t.name}`}
                      >
                        <Icon name="download-icon" />
                      </a>
                    ) : null}
                  </div>
                  {t.progress !== undefined && (
                    <>
                      <div className="progress-track">
                        <span style={{ width: `${t.progress}%` }} />
                      </div>
                      <div className="row between">
                        <small>
                          {t.size
                            ? `${bytes(Math.round((t.size * t.progress) / 100))} of ${bytes(t.size)}`
                            : "Progress reported by the device"}
                        </small>
                        <small className="cyan">
                          {t.state === "Receiving" ? "Receiving over the mesh" : "Sending over the mesh"}
                        </small>
                      </div>
                    </>
                  )}
                </div>
              ))}
            {!visibleTransfers.length && <p className="empty">{filter === "All" ? "No transfers yet." : `No ${filter.toLowerCase()} transfers.`}</p>}
          </div>
          <div className="info-card">
            <Icon name="refresh-icon-3" />
            <div>
              <b>Routes can change while a file is being sent.</b>
              <p>Waiting transfers need an available route.</p>
            </div>
          </div>
        </aside>
      </div>
    </section>
  );
}
