import React from 'react'

const paths = {
  moon: <path d="M20.9 13a9 9 0 0 1-9.9-9.9A9 9 0 1 0 20.9 13Z"/>,
  sun: <><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></>,
  dashboard: <><rect x="3" y="3" width="7" height="9" rx="2"/><rect x="14" y="3" width="7" height="5" rx="2"/><rect x="14" y="12" width="7" height="9" rx="2"/><rect x="3" y="16" width="7" height="5" rx="2"/></>,
  files: <><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></>,
  peers: <><circle cx="12" cy="5" r="2.5"/><circle cx="5" cy="18" r="2.5"/><circle cx="19" cy="18" r="2.5"/><path d="M10.8 7.3 6.2 15.7M13.2 7.3l4.6 8.4M7.5 18h9"/></>,
  repairs: <><path d="M20 11a8 8 0 0 0-14.5-4.5L4 8"/><path d="M4 4v4h4"/><path d="M4 13a8 8 0 0 0 14.5 4.5L20 16"/><path d="M20 20v-4h-4"/></>,
  settings: <><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/></>,
  upload: <><path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></>,
  download: <><path d="M12 4v12M7 11l5 5 5-5"/><path d="M4 18v1a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-1"/></>,
  trash: <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-12M9 7V4h6v3"/>,
  search: <><circle cx="11" cy="11" r="6.5"/><path d="m20 20-4-4"/></>,
  check: <path d="m5 12 5 5 9-10"/>,
  alert: <><path d="M12 8v5M12 16.5v.01"/><path d="M10.3 4.2 2.8 17.5a2 2 0 0 0 1.7 3h15a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z"/></>,
  close: <path d="M6 6l12 12M18 6 6 18"/>,
  arrow: <path d="M5 12h14M13 6l6 6-6 6"/>,
  inbox: <><path d="M4 13l2.5-7.5A2 2 0 0 1 8.4 4h7.2a2 2 0 0 1 1.9 1.5L20 13"/><path d="M4 13v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5h-4.5a1.5 1.5 0 0 0-1.5 1.5 1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 9 14.5 1.5 1.5 0 0 0 7.5 13z"/></>,
}

export default function Icon({ name, size = 18 }) {
  return <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>
}

export function LogoMark({ size = 30 }) {
  return <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
    <defs><linearGradient id="lm" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#8E84FF"/><stop offset="1" stopColor="#3EE0B5"/></linearGradient></defs>
    <path d="M16 2.5 28 9.5v13L16 29.5 4 22.5v-13z" fill="none" stroke="url(#lm)" strokeWidth="2" strokeLinejoin="round"/>
    <circle cx="16" cy="16" r="3.4" fill="url(#lm)"/>
    <path d="M16 12.6V7M18.9 17.7l4.8 2.8M13.1 17.7l-4.8 2.8" stroke="url(#lm)" strokeWidth="1.6" strokeLinecap="round"/>
  </svg>
}
