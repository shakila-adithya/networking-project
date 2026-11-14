import type { Metadata } from "next";
import "@fontsource-variable/inter";
import "./globals.css";
import { Shell } from "@/components/shell";
export const metadata: Metadata = {
  title: "relay — Offline mesh",
  icons: { icon: "/icons/relay-mark.svg" },
  description: "Your nearby network. Connect, message, and share files.",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  const themeScript = `(function(){try{var t=localStorage.getItem('relay-theme');if(t!=='light'&&t!=='dark'){t=matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'}document.documentElement.dataset.theme=t;document.documentElement.style.colorScheme=t}catch(e){document.documentElement.dataset.theme='dark'}})()`;
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body>
        <Shell>{children}</Shell>
      </body>
    </html>
  );
}
