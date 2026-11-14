"use client";
import {
  createContext,
  useContext,
  useEffect,
  useState,
  useCallback,
} from "react";
export type Person = {
  id: string;
  name: string;
  detail: string;
  tone: string;
  status: string;
  x?: number;
  y?: number;
};
export type StoredFile = { fileId: string; fileName: string; size: number };
export type Chat = {
  chatId: string;
  from: string;
  text: string;
  receivedAt?: string;
};
export type BluetoothInfo = {
  adapter: string;
  advertisedAs: string;
  advertising?: boolean;
  scanning?: boolean;
  lastError: string;
  nearby: { address: string; name: string | null; rssi: number | null; relayDevice: string | null }[];
};
export type DeviceInfo = {
  deviceId: string;
  displayName: string; // what others see: the chosen name, or the id
  customName: string; // "" when none was chosen
  visible: boolean;
  transport: string;
  bluetooth: {
    available: boolean;
    adapter?: string;
    advertising?: boolean;
    advertisedAs?: string;
    error?: string;
    reason?: string;
  };
};
export type NearbyItem = {
  kind: "bluetooth" | "lan" | "tcp" | "hotspot" | "wifi-direct";
  deviceId: string | null;
  name: string;
  address: string;
  signal: number | null; // 0-100
  rssi: number | null;
  relay: boolean; // runs Relay (can be connected)
  state: "CONNECTED" | "AVAILABLE" | "FAILED" | "OTHER";
  error: string;
  hops: number | null;
  peerConnected?: boolean;
};
export type Nearby = {
  bluetoothAvailable: boolean;
  scanning: boolean;
  lastScanAt: number;
  items: NearbyItem[];
};
const noNearby: Nearby = { bluetoothAvailable: false, scanning: false, lastScanAt: 0, items: [] };
// Sample data for the design preview (nothing here talks to a real device).
const previewDevice: DeviceInfo = {
  deviceId: "Y",
  displayName: "Y",
  customName: "",
  visible: false,
  transport: "bluetooth",
  bluetooth: { available: true, adapter: "AA:BB:CC:00:11:22", advertising: false, advertisedAs: "" },
};
const previewNearby: Nearby = {
  bluetoothAvailable: true,
  scanning: false,
  lastScanAt: 0,
  items: [
    { kind: "bluetooth", deviceId: "M", name: "Maya", address: "bt:AA:BB:CC:DD:EE:01", signal: 82, rssi: -58, relay: true, state: "CONNECTED", error: "", hops: 1 },
    { kind: "bluetooth", deviceId: "Z", name: "Zara", address: "bt:AA:BB:CC:DD:EE:02", signal: 46, rssi: -78, relay: true, state: "AVAILABLE", error: "", hops: null },
    { kind: "bluetooth", deviceId: null, name: "soundcore R60i NC", address: "bt:34:09:C9:B1:39:88", signal: null, rssi: null, relay: false, state: "OTHER", error: "", hops: null },
  ],
};
// "tcp+bluetooth" -> "TCP + Bluetooth"
export function transportLabel(kind: string) {
  return String(kind || "tcp")
    .split("+")
    .map((k) => ({ bluetooth: "Bluetooth", "wifi-direct": "Wi-Fi Direct", hotspot: "Hotspot", unknown: "Transport not reported", memory: "Simulation" }[k.toLowerCase()] || k.toUpperCase()))
    .join(" + ");
}
export type Route = {
  destination: string;
  nextHop: string;
  cost: number;
  hops: number;
  valid: boolean;
};
export const people: Person[] = [
  {
    id: "A",
    name: "Amaya",
    detail: "Via Maya · 2 hops",
    tone: "cyan",
    status: "ALIVE",
  },
  {
    id: "M",
    name: "Maya",
    detail: "Direct Bluetooth connection",
    tone: "green",
    status: "ALIVE",
  },
  {
    id: "N",
    name: "Nimal",
    detail: "Direct Bluetooth connection",
    tone: "green",
    status: "ALIVE",
  },
  {
    id: "R",
    name: "Ravi",
    detail: "Via Nimal · 2 hops",
    tone: "cyan",
    status: "ALIVE",
  },
  {
    id: "K",
    name: "Kasun",
    detail: "Waiting for a route",
    tone: "gold-text",
    status: "SUSPECTED",
  },
];
class ApiError extends Error {
  constructor(message: string, readonly code: string) { super(message); }
}
export async function request(path: string, options?: RequestInit) {
  const r = await fetch(`/api${path}`, { ...options, cache: "no-store" });
  const data = await r.json().catch(() => ({}));
  if (!r.ok)
    throw new ApiError(
      data.message || "Can't reach Relay on this device. Start or restart Relay, then try again.",
      data.code || "",
    );
  return data;
}
type State = {
  preview: boolean;
  setPreview: (v: boolean) => void;
  peers: Person[];
  files: StoredFile[];
  inbox: Chat[];
  neighbours: string[];
  links: Record<string, string>;
  graph: { deviceId: string; links: Record<string, number>; reachable: boolean }[];
  custody: { available: boolean; pending?: number; error?: string } | null;
  localId: string;
  transport: string;
  bluetooth: BluetoothInfo | null;
  /** This device's name as others see it (display name, or the id). */
  deviceName: string;
  device: DeviceInfo | null;
  nearby: Nearby;
  /** Changes the name and/or visibility. Throws an Error with a readable message on failure. */
  saveDevice: (patch: { displayName?: string; visible?: boolean }) => Promise<void>;
  scanNearby: () => Promise<void>;
  /** Connects to a nearby Relay device by its id. Throws an Error with a readable message on failure. */
  connectNearby: (deviceId: string, address?: string) => Promise<void>;
  error: string;
  connectionNotice: "" | "not-joined" | "starting";
  refresh: () => Promise<void>;
};
const Context = createContext<State>(null!);
export function MeshProvider({ children }: { children: React.ReactNode }) {
  const [preview, setPreviewState] = useState(true),
    [peers, setPeers] = useState<Person[]>([]),
    [files, setFiles] = useState<StoredFile[]>([]),
    [inbox, setInbox] = useState<Chat[]>([]),
    [neighbours, setNeighbours] = useState<string[]>([]),
    [links, setLinks] = useState<Record<string, string>>({}),
    [localId, setLocalId] = useState("You"),
    [transport, setTransport] = useState("TCP"),
    [bluetooth, setBluetooth] = useState<BluetoothInfo | null>(null),
    [deviceName, setDeviceName] = useState("You"),
    [device, setDevice] = useState<DeviceInfo | null>(null),
    [graph, setGraph] = useState<{ deviceId: string; links: Record<string, number>; reachable: boolean }[]>([]),
    [custody, setCustody] = useState<{ available: boolean; pending?: number; error?: string } | null>(null),
    [nearby, setNearby] = useState<Nearby>(noNearby),
    [previewDev, setPreviewDev] = useState<DeviceInfo>(previewDevice),
    [previewNear, setPreviewNear] = useState<Nearby>(previewNearby),
    [error, setError] = useState(""),
    [connectionNotice, setConnectionNotice] = useState<"" | "not-joined" | "starting">("");
  useEffect(() => {
    setPreviewState(localStorage.getItem("relay-mode") !== "live");
  }, []);
  const setPreview = (v: boolean) => {
    setPreviewState(v);
    localStorage.setItem("relay-mode", v ? "preview" : "live");
  };
  const refresh = useCallback(async () => {
    try {
      const health = await request("/health");
      setError(""); setConnectionNotice("");
      const byId = new Map<string, Person>();
      for (const p of (health.peers || []).filter(
        (p: any) => p.source !== "local" && p.kind !== "local",
      )) {
        const id = p.id || p.peerId;
        byId.set(id, {
          id,
          name: id,
          detail: `${p.address || p.host || ""} · ${p.status}`,
          tone: p.status === "ALIVE" ? "green" : "red",
          status: p.status,
        });
      }
      const f = await request("/files");
      setFiles(f.files || []);
      let names: Record<string, string> = {};
      try {
        const m = await request("/mesh");
        names = m.names || {};
        const links: Record<string, string> = Object.fromEntries((m.neighbours || []).map((id: string) => [id, m.links?.[id] || "unknown"]));
        setLinks(links);
        setGraph(m.topology || []); setCustody(m.custody || null);
        if (m.historyError) setError(m.historyError);
        for (const member of m.topology || []) {
          if (member.deviceId !== m.deviceId && !byId.has(member.deviceId)) byId.set(member.deviceId, {
            id: member.deviceId, name: names[member.deviceId] || member.deviceId,
            detail: member.reachable ? "Mesh member · reachable" : "Last known member · finding a route",
            tone: member.reachable ? "cyan" : "muted", status: member.reachable ? "ALIVE" : "SUSPECTED",
          });
        }
        setLocalId(m.deviceId);
        setDeviceName(m.deviceName || m.deviceId);
        setInbox(m.inbox || []);
        setNeighbours(m.neighbours || []);
        setTransport(transportLabel(m.transport));
        setBluetooth(m.bluetooth || null);
        // Devices the mesh knows about appear even when the cluster heartbeat does not list them.
        const base = (id: string): Person =>
          byId.get(id) || { id, name: id, detail: "", tone: "green", status: "ALIVE" };
        for (const id of (m.neighbours || []) as string[]) {
          byId.set(id, {
            ...base(id),
            detail: `Direct ${transportLabel(links[id])} link`,
            tone: "green",
            status: "ALIVE",
          });
        }
        for (const r of (m.routes || []) as Route[]) {
          if (r.destination === m.deviceId || (m.neighbours || []).includes(r.destination)) continue;
          byId.set(r.destination, {
            ...base(r.destination),
            detail: r.valid
              ? `Via ${r.nextHop} · ${r.hops} hops`
              : "Route lost · rediscovering",
            tone: r.valid ? "cyan" : "gold-text",
            status: r.valid ? "ALIVE" : "SUSPECTED",
          });
        }
      } catch {
        setNeighbours([]); setGraph([]); setCustody(null);
        setLinks({});
      }
      // Friendly names learned from the mesh replace raw ids (the id stays in Person.id for API calls).
      setPeers([...byId.values()].map((p) => ({ ...p, name: names[p.id] || p.name })));
      try {
        setDevice(await request("/device"));
      } catch {
        setDevice(null);
      }
      try {
        setNearby(await request("/nearby"));
      } catch {
        setNearby(noNearby);
      }
    } catch (e) {
      setNeighbours([]); setLinks({}); setGraph([]); setCustody(null); setPeers([]); setDevice(null); setBluetooth(null); setNearby(noNearby);
      const failure = e as Error;
      // Older running backends do not include a status code yet.
      const notJoined = e instanceof ApiError && e.code === "MESH_NOT_JOINED"
        || failure.message === "Relay isn't connected to a mesh yet. Join one on the Network map, or check your connection in Settings.";
      const starting = e instanceof ApiError && e.code === "MESH_STARTING";
      setConnectionNotice(starting ? "starting" : notJoined ? "not-joined" : "");
      setError(starting || notJoined ? "" : failure.message);
    }
  }, []);
  useEffect(() => {
    if (preview) return;
    refresh();
    const t = setInterval(refresh, 3000);
    return () => clearInterval(t);
  }, [preview, refresh]);
  // While a Bluetooth scan runs (several seconds), follow it closely so the list appears as soon as it ends.
  useEffect(() => {
    if (preview || !nearby.scanning) return;
    const t = setInterval(async () => {
      try {
        setNearby(await request("/nearby"));
      } catch {
        /* the regular refresh reports connection problems */
      }
    }, 1000);
    return () => clearInterval(t);
  }, [preview, nearby.scanning]);
  const saveDevice = useCallback(
    async (patch: { displayName?: string; visible?: boolean }) => {
      if (preview) {
        // Validate first: an error must reach the caller, not be thrown inside a state updater.
        if (patch.displayName !== undefined && Array.from(patch.displayName.trim()).length > 32)
          throw new Error("The device name can be at most 32 characters.");
        setPreviewDev((d) => {
          const customName = patch.displayName !== undefined ? patch.displayName.trim() : d.customName;
          const visible = patch.visible ?? d.visible;
          return {
            ...d,
            customName,
            displayName: customName || d.deviceId,
            visible,
            bluetooth: {
              ...d.bluetooth,
              advertising: visible,
              advertisedAs: visible ? (customName ? `${customName} [relay:${d.deviceId}]` : `relay:${d.deviceId}`) : "",
            },
          };
        });
        return;
      }
      if (patch.visible !== undefined) {
        if (patch.displayName !== undefined) await request("/device", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ displayName: patch.displayName }) });
        await request("/visibility", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ visible: patch.visible }) });
        await refresh(); return;
      }
      const updated = (await request("/device", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(patch),
      })) as DeviceInfo;
      setDevice(updated);
      setDeviceName(updated.displayName);
    },
    [preview, refresh],
  );
  const scanNearby = useCallback(async () => {
    if (preview) {
      setPreviewNear((n) => ({ ...n, scanning: true }));
      setTimeout(() => setPreviewNear((n) => ({ ...n, scanning: false, lastScanAt: Date.now() })), 1500);
      return;
    }
    await request("/bluetooth/scan", { method: "POST" });
    setNearby((n) => ({ ...n, scanning: true }));
  }, [preview]);
  const connectNearby = useCallback(
    async (deviceId: string, address?: string) => {
      if (preview) {
        await new Promise((r) => setTimeout(r, 800));
        setPreviewNear((n) => ({
          ...n,
          items: n.items.map((i) => (i.deviceId === deviceId && (!address || i.address === address) ? { ...i, state: "CONNECTED", hops: 1 } : i)),
        }));
        return;
      }
      await request("/mesh/connect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(address ? { deviceId, candidateAddress: address } : { deviceId }),
      });
      await refresh();
    },
    [preview, refresh],
  );
  return (
    <Context.Provider
      value={{
        preview,
        setPreview,
        peers: preview ? people : peers,
        files,
        inbox,
        neighbours,
        links: preview ? { M: "bluetooth", N: "tcp", K: "hotspot" } : links,
        localId,
        transport,
        bluetooth,
        graph: preview ? [] : graph,
        custody: preview ? null : custody,
        deviceName: preview ? previewDev.displayName : deviceName,
        device: preview ? previewDev : device,
        nearby: preview ? previewNear : nearby,
        saveDevice,
        scanNearby,
        connectNearby,
        error,
        connectionNotice,
        refresh,
      }}
    >
      {children}
    </Context.Provider>
  );
}
export const useMesh = () => useContext(Context);
