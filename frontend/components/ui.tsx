import sizes from "@/lib/icon-sizes.json";
import type { CSSProperties, ReactNode } from "react";
export function Icon({ name }: { name: string }) {
  const size = sizes[name as keyof typeof sizes];
  if (name === "send-message") return <svg className="icon" width="42" height="42" viewBox="0 0 42 42" fill="none" aria-hidden="true">
    <rect width="42" height="42" rx="11" fill="var(--accent-fill)" />
    <path d="M17.25 21L14.25 27.75L28.5 21L14.25 14.25L17.25 21ZM28.5 21H17.25" stroke="var(--on-accent)" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
  if (name === "upload-icon-tile") return <svg className="icon" width="44" height="44" viewBox="0 0 44 44" fill="none" aria-hidden="true">
    <rect width="44" height="44" rx="13" fill="var(--raised)" />
    <path d="M22 26.1667V15.3334M26.1667 19.5L22 15.3334L17.8333 19.5M15.3333 26.1667V29.5H28.6667V26.1667" stroke="var(--gold)" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
  if (name !== "relay-mark" && name !== "select-amaya") return <span className="icon themed-icon" aria-hidden="true"
    style={{ "--icon-width": `${size?.width || 17}px`, "--icon-height": `${size?.height || 17}px`, maskImage: `url(/icons/${name}.svg)`, WebkitMaskImage: `url(/icons/${name}.svg)` } as CSSProperties} />;
  return (
    <img
      width={sizes[name as keyof typeof sizes]?.width}
      height={sizes[name as keyof typeof sizes]?.height}
      className="icon"
      src={`/icons/${name}.svg`}
      alt=""
      draggable={false}
    />
  );
}
export function Avatar({
  name,
  size = 36,
  gold = false,
}: {
  name: string;
  size?: number;
  gold?: boolean;
}) {
  return (
    <span
      className={`avatar ${gold ? "gold" : ""}`}
      style={{ width: size, height: size }}
    >
      {name.charAt(0).toUpperCase()}
    </span>
  );
}
export function Eyebrow({
  children,
  gold = false,
}: {
  children: ReactNode;
  gold?: boolean;
}) {
  return <div className={`eyebrow ${gold ? "gold-text" : ""}`}>{children}</div>;
}
export function Badge({
  children,
  tone = "green",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return (
    <span className={`badge ${tone}`}>
      <i />
      {children}
    </span>
  );
}
export function Search({
  value,
  onChange,
  placeholder,
  icon = "search-icon",
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  icon?: string;
}) {
  return (
    <label className="search">
      <Icon name={icon} />
      <input
        aria-label={placeholder}
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}
export function FileTile({ name }: { name: string }) {
  return (
    <span className="file-tile">
      {name.split(".").pop()?.slice(0, 4).toUpperCase()}
    </span>
  );
}
export const bytes = (v: number) =>
  v >= 1048576
    ? `${(v / 1048576).toFixed(1)} MB`
    : `${(v / 1024).toFixed(0)} KB`;
