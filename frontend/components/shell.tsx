"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { MeshProvider, useMesh, transportLabel } from "@/lib/mesh";
import { Icon } from "./ui";
import { ConnectionGlyph } from "./connection-glyph";
export function Shell({ children }: { children: React.ReactNode }) {
  return (
    <MeshProvider>
      <Chrome>{children}</Chrome>
    </MeshProvider>
  );
}
function Chrome({ children }: { children: React.ReactNode }) {
  const path = usePathname(),
    { preview, setPreview, peers, links, error, connectionNotice } = useMesh();
  const nav = [
    [
      "/",
      "Network map",
      path === "/"
        ? "map-icon-5"
        : path.startsWith("/files")
          ? "map-icon-4"
          : "map-icon-2",
    ],
    [
      "/messages/",
      "Messages",
      path.startsWith("/messages")
        ? "chat-icon-2"
        : path.startsWith("/files")
          ? "chat-icon-3"
          : "chat-icon",
    ],
    [
      "/files/",
      "Share files",
      path.startsWith("/files")
        ? "file-icon-4"
        : path.startsWith("/messages")
          ? "file-icon-2"
          : "file-icon",
    ],
    ["/settings/", "Settings", "settings-icon"],
    ["/how-it-works/", "How Relay works", "guide-icon"],
  ];
  return (
    <>
      <header className="header">
        <Link href="/" className="brand">
          <Icon name="relay-mark" />
          <div>
            <strong>Relay</strong>
            <span>OFFLINE MESH NETWORK</span>
          </div>
        </Link>
        <nav className="main-nav" aria-label="Main navigation">
          {nav.map(([href, label, icon]) => (
            <Link
              key={href}
              href={href}
              className={
                (href === "/" ? path === "/" : path.startsWith(href))
                  ? "active"
                  : ""
              }
            >
              <Icon name={icon} />
              {label}
            </Link>
          ))}
          <button className="nav-mode" onClick={() => setPreview(!preview)} aria-pressed={preview}>
            <span className={`mode-indicator ${preview ? "preview" : "live"}`} />
            {preview ? "Use live network" : "View design preview"}
          </button>
        </nav>
        <span className="header-spacer" aria-hidden="true" />
      </header>
      {!preview && connectionNotice && !(connectionNotice === "not-joined" && path === "/") && <div className="connection-notice" role="status">
        <span className="connection-notice-icon" aria-hidden="true"><ConnectionGlyph kind="mesh" /></span>
        <div><strong>{connectionNotice === "starting" ? "Connecting to your mesh" : "Ready to join a mesh"}</strong><p>{connectionNotice === "starting" ? "Relay is starting your connections. Your network status will update shortly." : path === "/" ? "Choose a nearby mesh or create your own using the sidebar." : "Open the Network map to find a nearby mesh or create your own."}</p></div>
        {connectionNotice === "not-joined" && path !== "/" && <Link className="button secondary" href="/">Open Network map <span aria-hidden="true">↗</span></Link>}
      </div>}
      {!preview && error && (
        <div className="api-error" role="alert">
          {error} <Link href="/settings/">Open Settings</Link>
        </div>
      )}
      <main>{children}</main>

    </>
  );
}
