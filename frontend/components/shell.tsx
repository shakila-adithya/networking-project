"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { MeshProvider, useMesh } from "@/lib/mesh";
import { Icon, Avatar } from "./ui";
export function Shell({ children }: { children: React.ReactNode }) {
  return (
    <MeshProvider>
      <Chrome>{children}</Chrome>
    </MeshProvider>
  );
}
function Chrome({ children }: { children: React.ReactNode }) {
  const path = usePathname(),
    { preview, setPreview, peers, deviceName, transport, error } = useMesh();
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
    ["/device/", "My device", path.startsWith("/device") ? "phone-icon" : "phone-icon-2"],
  ];
  return (
    <>
      <header className="header">
        <Link href="/" className="brand">
          <Icon name="relay-mark" />
          <div>
            <strong>relay</strong>
            <span>OFFLINE MESH</span>
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
        </nav>
        <div className="connection">
          <i className="status-dot cyan" />
          <div>
            {preview ? "Bluetooth active" : `${transport} mesh`}
            <small>
              {preview
                ? "Internet unavailable"
                : `${peers.filter((p) => p.status === "ALIVE").length} nearby ${peers.filter((p) => p.status === "ALIVE").length === 1 ? "peer" : "peers"}`}
            </small>
          </div>
          <Link href="/device/" aria-label="My device">
            <Avatar name={deviceName} size={38} />
          </Link>
        </div>
      </header>
      {!preview && error && (
        <div className="api-error" role="alert">
          {error} <Link href="/settings/">Open Settings</Link>
        </div>
      )}
      <main>{children}</main>
      <div className="mode-control">
        <span>
          {preview
            ? "Design preview · sample devices and activity"
            : "Live network · actual device data"}
        </span>
        <button onClick={() => setPreview(!preview)}>
          {preview ? "Use live network" : "View design preview"}
        </button>
        <Link href="/settings/">Settings</Link>
      </div>
    </>
  );
}
