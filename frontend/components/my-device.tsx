"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { useMesh } from "@/lib/mesh";
import { Avatar, Eyebrow } from "./ui";

const MAX_NAME = 32;

// "My device": the name other people see. Visibility is changed on the Network map (select your own device).
export function MyDevice() {
  const { preview, device, saveDevice } = useMesh();
  const saved = device?.customName ?? "";
  const [name, setName] = useState(saved),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [done, setDone] = useState(false);
  // Follow the saved value when it arrives or changes (first load, other tab), but never overwrite typing in progress.
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (!touched) setName(saved);
  }, [saved, touched]);

  const trimmed = name.trim();
  const tooLong = Array.from(trimmed).length > MAX_NAME;
  const unchanged = trimmed === saved;
  const id = device?.deviceId ?? "…";
  const shownName = trimmed || id;

  async function save() {
    setBusy(true);
    setError("");
    setDone(false);
    try {
      await saveDevice({ displayName: trimmed });
      setTouched(false);
      setDone(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="my-device">
      <Eyebrow gold>MY DEVICE</Eyebrow>
      <h1>Your name in the mesh</h1>
      <p className="lead">
        This is the name other people see in their lists and on their map.
      </p>
      <form
        className="device-card"
        onSubmit={(e) => {
          e.preventDefault();
          if (!busy && !unchanged && !tooLong) save();
        }}
      >
        <div className="device-identity">
          <Avatar name={shownName} gold size={56} />
          <div>
            <b>{shownName}</b>
            <small>
              Device ID <code>{id}</code>
            </small>
          </div>
        </div>
        <label htmlFor="device-name">Device name</label>
        <div className="name-row">
          <input
            id="device-name"
            className="text-input"
            value={name}
            placeholder={`Shown as “${id}” until you choose a name`}
            maxLength={64}
            autoComplete="off"
            onChange={(e) => {
              setName(e.target.value);
              setTouched(true);
              setDone(false);
              setError("");
            }}
          />
          <button
            type="submit"
            className="button primary"
            disabled={busy || unchanged || tooLong || (!preview && !device)}
          >
            {busy ? "Saving…" : "Save"}
          </button>
        </div>
        <div className="name-meta">
          <span className={tooLong ? "field-error" : "hint"}>
            {Array.from(trimmed).length}/{MAX_NAME} characters
          </span>
          {done && <span className="saved" role="status">Saved</span>}
        </div>
        {error && (
          <p role="alert" className="field-error">
            {error}
          </p>
        )}
        {!preview && !device && (
          <p role="alert" className="field-error">
            The local node is not running yet. Open <Link href="/settings/">Settings</Link> to start it.
          </p>
        )}
      </form>
      <p className="hint">
        To let nearby devices find you, open the <Link href="/">Network map</Link> and select your device.
        The device ID is set in <Link href="/settings/">Settings</Link>.
      </p>
    </section>
  );
}
