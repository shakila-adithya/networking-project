import type { ReactNode } from "react";
export type ConnectionMethod = "bluetooth" | "lan" | "wifi-direct" | "hotspot";

/** Small UI symbols; the label and live API state describe the actual transport. */
export function ConnectionGlyph({ kind }: { kind: ConnectionMethod | "mesh" }) {
  const shapes: Record<string, ReactNode> = {
    bluetooth: <><path d="m8 7 9 10-5 4V3l5 4-9 10" /></>,
    lan: <><path d="M3 8a15 15 0 0 1 18 0M6 12a10 10 0 0 1 12 0M9 16a5 5 0 0 1 6 0" /><circle cx="12" cy="20" r=".8" /></>,
    "wifi-direct": <><path d="M3 8a15 15 0 0 1 18 0M7 12a9 9 0 0 1 10 0m-9 5 3-3m-3 3H4m4 0v-4m8 4-3 3m3-3h4m-4 0v4" /></>,
    hotspot: <><circle cx="12" cy="12" r="2" /><path d="M7.8 7.8a6 6 0 0 0 0 8.4m8.4-8.4a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2m14.2-14.2a10 10 0 0 1 0 14.2" /></>,
    mesh: <><path d="m7 7 10 1-4 11L7 7M7 7l-3 9 9 3" /><circle cx="7" cy="7" r="2.5" /><circle cx="17" cy="8" r="2.5" /><circle cx="13" cy="19" r="2.5" /><circle cx="4" cy="16" r="2.5" /></>,
  };
  return <svg className="connection-glyph" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{shapes[kind]}</svg>;
}
