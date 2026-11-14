import sizes from "@/lib/icon-sizes.json";
import type { ReactNode } from "react";
export function Icon({ name }: { name: string }) {
  if (name === "plus-icon-2") {
    return (
      <svg
        width={14}
        height={14}
        viewBox="0 0 14 14"
        className="icon icon-plus-icon-2"
        data-icon={name}
        aria-hidden="true"
      >
        <path
          d="M7 2.91667V7M7 7V11.0833M7 7H2.91667M7 7H11.0833"
          stroke="currentColor"
          strokeWidth="1.65"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }
  if (name === "upload-icon-tile") {
    return (
      <svg
        width={44}
        height={44}
        viewBox="0 0 44 44"
        className="icon icon-upload-icon-tile"
        data-icon={name}
        aria-hidden="true"
      >
        <rect width="44" height="44" rx="13" fill="var(--upload-tile)" />
        <path
          d="M22 26.1667V15.3334M26.1667 19.5L22 15.3334L17.8333 19.5M15.3333 26.1667V29.5H28.6667V26.1667"
          stroke="var(--upload-ink)"
          strokeWidth="1.65"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }
  return (
    <img
      width={sizes[name as keyof typeof sizes]?.width}
      height={sizes[name as keyof typeof sizes]?.height}
      className={`icon icon-${name}`}
      data-icon={name}
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
