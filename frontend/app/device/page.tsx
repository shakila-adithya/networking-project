"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";

// Keep old bookmarks working after moving device preferences into Settings.
export default function Page() {
  const router = useRouter();
  useEffect(() => { router.replace("/settings/#my-device"); }, [router]);
  return <p className="settings-page">Opening device settings…</p>;
}
