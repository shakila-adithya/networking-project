import type { Metadata } from "next";
import "@fontsource-variable/inter";
import "./globals.css";
import { Shell } from "@/components/shell";
export const metadata: Metadata = {
  title: "Relay — Offline Mesh Network",
  icons: { icon: "/icons/relay-mark.svg" },
  description: "Your nearby network. Connect, message, and share files.",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Shell>{children}</Shell>
      </body>
    </html>
  );
}
