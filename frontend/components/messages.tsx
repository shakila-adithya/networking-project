"use client";
import { Fragment, useEffect, useState } from "react";
import Link from "next/link";
import { request, useMesh } from "@/lib/mesh";
import { Icon, Avatar, Eyebrow, Badge, Search } from "./ui";
type Outgoing = { id: string; to: string; text: string; status: string };
export function Messages() {
  const { preview, peers, inbox } = useMesh(),
    [selected, setSelected] = useState("A"),
    [query, setQuery] = useState(""),
    [text, setText] = useState(""),
    [outgoing, setOutgoing] = useState<Outgoing[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    setOutgoing([]);
    setText("");
    setError("");
  }, [preview]);
  const person = peers.find((p) => p.id === selected) || peers[0],
    name = person?.name || "a device";
  useEffect(() => {
    if (preview) return;
    const timer = setInterval(() => {
      outgoing
        .filter((m) => m.status === "PENDING")
        .forEach(async (m) => {
          try {
            const r = await request("/mesh/chat/" + m.id);
            setOutgoing((prev) =>
              prev.map((x) => (x.id === m.id ? { ...x, status: r.status } : x)),
            );
          } catch {
            /* Retry status on the next poll. */
          }
        });
    }, 2000);
    return () => clearInterval(timer);
  }, [preview, outgoing]);
  async function send() {
    if (!text.trim() || !person) return;
    setBusy(true);
    setError("");
    try {
      const r = preview
        ? { chatId: crypto.randomUUID(), status: "Preview only" }
        : await request("/mesh/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ to: person.id, text: text.trim() }),
          });
      setOutgoing((v) => [
        ...v,
        { id: r.chatId, to: person.id, text: text.trim(), status: r.status },
      ]);
      setText("");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="messages-workspace">
      <aside className="conversation-sidebar">
        <div className="conversation-intro">
          <Eyebrow gold>OFFLINE MESSAGES</Eyebrow>
          <h1>Stay in touch.</h1>
          <p>Reach people through your nearby mesh.</p>
          <Search
            value={query}
            onChange={setQuery}
            placeholder="Search people or messages"
          />
        </div>
        <Eyebrow>PEOPLE IN THE MESH</Eyebrow>
        <div className="people-list">
          {peers
            .filter((p) => p.name.toLowerCase().includes(query.toLowerCase()))
            .map((p, i) => (
              <button
                className={`conversation-item ${person?.id === p.id ? "selected" : ""}`}
                key={p.id}
                onClick={() => setSelected(p.id)}
              >
                <span className="avatar-state">
                  <Avatar name={p.name} gold={person?.id === p.id} />
                  <i className={`status-dot ${p.tone}`} />
                </span>
                <div>
                  <b>{p.name}</b>
                  <small>
                    {preview
                      ? [
                          "Thanks! I can see it now.",
                          "I’m nearby if you need help.",
                          "The route is back.",
                          "Sending the document…",
                          "Waiting for a route",
                        ][i]
                      : p.detail}
                  </small>
                </div>
                {preview && (
                  <span className="conversation-time">
                    {["Now", "4m", "18m", "1h", "2h"][i]}
                    {i === 0 && <em>2</em>}
                  </span>
                )}
              </button>
            ))}
          {!peers.length && (
            <p className="empty">
              No devices yet. Open the Network map to join a mesh.
            </p>
          )}
        </div>
        <div className="info-card">
          <Icon name="bluetooth-icon" />
          <div>
            <b>Messaging without internet</b>
            <p>
              Messages travel through available
              <br />
              connected devices.
            </p>
          </div>
        </div>
      </aside>
      <div className="conversation">
        <div className="chat-header">
          <Avatar name={name} gold size={40} />
          <div>
            <h2>{name}</h2>
            <p>
              <i className="status-dot cyan" />
              {preview
                ? "Reachable through Maya · 2 hops"
                : person?.detail || "Waiting for a nearby peer"}
            </p>
          </div>
          <Link className="button" href="/">
            <Icon name="map-icon-3" />
            View route
          </Link>
          <Link
            href="/settings/"
            className="icon-button"
            aria-label="Connection settings"
          >
            <Icon name="more-icon" />
          </Link>
        </div>
        <div className="chat-body">
          <div className="current-route">
            <Icon name="bluetooth-icon-2" />
            <span>Current route</span>
            <b>You</b>
            <Icon name="arrow-icon" />
            {preview && (
              <>
                <b>Maya</b>
                <Icon name="arrow-icon-2" />
              </>
            )}
            <b className="cyan">{name}</b>
            <small>{preview ? "2 hops" : "Live mesh"}</small>
          </div>
          <div className="chat-date">
            {preview ? "TODAY, 09:41" : "LIVE CONVERSATION"}
          </div>
          <div className="chat-scroll">
            {preview && person?.id === "A" && (
              <>
                <Message
                  text="Hi! The internet is down here. Can you send me the meeting location?"
                  meta="Amaya · 09:41"
                />
                <Message
                  own
                  text="Yes, meet us at the community centre."
                  meta="09:42 · Delivered through 2 hops"
                />
                <Message
                  text="Thanks! I can see it now."
                  meta="Amaya · 09:43"
                />
                <div className="route-change">
                  <Icon name="refresh-icon" />A new route is being found
                </div>
                <Message
                  own
                  text="I’ll share the updated map too."
                  meta="Waiting for a route · 09:44"
                  waiting
                />
              </>
            )}
            {!preview &&
              inbox
                .filter((m) => m.from === person?.id)
                .map((m) => (
                  <Message key={m.chatId} text={m.text} meta={m.from} />
                ))}
            {outgoing
              .filter((m) => m.to === person?.id)
              .map((m) => (
                <Message
                  key={m.id}
                  own
                  text={m.text}
                  meta={m.status}
                  waiting={m.status === "PENDING"}
                />
              ))}
          </div>
          {error && (
            <p role="alert" className="error-text">
              {error}
            </p>
          )}
          <form
            className="composer"
            onSubmit={(e) => {
              e.preventDefault();
              send();
            }}
          >
            <Link href="/files/" className="icon-button" aria-label="Add file">
              <Icon name="plus-icon" />
            </Link>
            <input
              aria-label="Message"
              placeholder={`Message ${name}…`}
              value={text}
              onChange={(e) => setText(e.target.value)}
              maxLength={4096}
            />
            <Link
              href="/files/"
              className="icon-button"
              aria-label="Attach file"
            >
              <Icon name="clip-icon" />
            </Link>
            <button
              className="send-message"
              type="submit"
              disabled={busy || !text.trim() || !person}
              aria-label="Send message"
            >
              <Icon name="send-message" />
            </button>
          </form>
          <div className="composer-note">
            {preview
              ? "DESIGN PREVIEW · NEW MESSAGES STAY IN THIS BROWSER"
              : "MESSAGES MAY WAIT UNTIL A ROUTE IS AVAILABLE"}
          </div>
        </div>
      </div>
      <aside className="conversation-details">
        <Eyebrow>MESH CONNECTION</Eyebrow>
        <div className="profile">
          <Avatar name={name} size={64} gold />
          <h2>{name}</h2>
          <p>{person ? `Mesh device · Peer ${person.id}` : "Choose a conversation to see its connection"}</p>
          <Badge tone="cyan">
            {preview ? "Via nearby devices" : person?.status || "Not connected"}
          </Badge>
        </div>
        <hr />
        <Eyebrow>LAST KNOWN ROUTE</Eyebrow>
        {person && (preview || person.status === "ALIVE") ? <div className="route-avatars">
          {(preview ? ["You", "Maya", name] : ["You", name]).map((n, i) => (
            <Fragment key={i}>
              {i > 0 && <Icon name="arrow-icon-3" />}
              <div>
                <Avatar name={n} size={28} gold={i === 0} />
                <small>{n}</small>
              </div>
            </Fragment>
          ))}
        </div> : <p className="route-empty">No active route. Connect a device to get started.</p>}
        <Eyebrow>DELIVERY STATES</Eyebrow>
        <div className="delivery-states">
          {[
            ["green", "Delivered", "Reached the recipient’s device"],
            ["cyan", "Forwarding", "Moving through the mesh"],
            ["gold-text", "Waiting", "No route is currently available"],
          ].map(([tone, title, desc]) => (
            <div className="event" key={title}>
              <i className={`status-dot ${tone}`} />
              <div>
                <b className={`delivery-pill ${tone}`}>{title}</b>
                <small>{desc}</small>
              </div>
            </div>
          ))}
        </div>
        <p className="footnote">Routes can change as devices join or leave.</p>
      </aside>
    </section>
  );
}
function Message({
  text,
  meta,
  own = false,
  waiting = false,
}: {
  text: string;
  meta: string;
  own?: boolean;
  waiting?: boolean;
}) {
  return (
    <div className={`message-row ${own ? "own" : ""}`}>
      {!own && <Avatar name={meta} size={28} />}
      <div>
        <div className="bubble">{text}</div>
        <small className={waiting ? "gold-text" : own ? "green" : ""}>
          {waiting && <Icon name="clock-icon" />}
          {meta}
        </small>
      </div>
    </div>
  );
}
