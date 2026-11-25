"use client";

import { useEffect, useState } from "react";

type Theme = "light" | "dark";
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("dark");
  useEffect(() => {
    const read = () => setTheme(document.documentElement.dataset.theme === "light" ? "light" : "dark");
    read();
    const sync = (event: StorageEvent) => {
      if (event.key !== "relay-theme" && event.key !== null) return;
      const selected = event.newValue === "light" || event.newValue === "dark"
        ? event.newValue : window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
      document.documentElement.dataset.theme = selected;
      setTheme(selected);
    };
    window.addEventListener("storage", sync);
    return () => window.removeEventListener("storage", sync);
  }, []);
  function toggle() {
    const next: Theme = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    setTheme(next);
    try { localStorage.setItem("relay-theme", next); } catch { /* The selected theme still works for this page. */ }
  }
  return <button type="button" role="switch" aria-label="Light mode" aria-checked={theme === "light"}
    className="theme-toggle" onClick={toggle} title={theme === "dark" ? "Switch to light mode" : "Switch to dark mode"}>
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
      {theme === "dark" ? <><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" /></>
        : <path d="M20 14a8 8 0 0 1-10-10 8 8 0 1 0 10 10Z" />}
    </svg>
    <span>{theme === "dark" ? "Light mode" : "Dark mode"}</span>
  </button>;
}
