import sizes from "@/lib/icon-sizes.json";
import type { ReactNode } from "react";
export function Icon({ name }: { name: string }) {
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
