import { test, expect } from "@playwright/test";

test("new replies follow sent messages even when the peer clock is behind", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  const inbox = [{ chatId: "old", from: "akila", text: "First message", timestampMillis: Date.now() - 3600000 }];
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    const data: Record<string, unknown> = {
      "/api/health": { peers: [{ peerId: "akila", status: "ALIVE", address: "192.0.2.2:5000" }] },
      "/api/files": { files: [] },
      "/api/mesh": { deviceId: "Local", neighbours: ["akila"], links: { akila: "tcp" }, names: { akila: "Akila" }, inbox },
      "/api/device": { deviceId: "Local", customName: "", displayName: "Local", bluetooth: { available: false } },
      "/api/nearby": { items: [] },
      "/api/mesh/chat": { chatId: "sent", status: "PENDING" },
      "/api/mesh/chat/sent": { status: "DELIVERED" },
    };
    return route.fulfill({ json: data[path] || {} });
  });
  await page.goto("/messages/");
  await expect(page.locator(".chat-scroll .bubble")).toHaveText(["First message"]);
  await page.getByRole("textbox", { name: "Message", exact: true }).fill("My reply");
  await page.getByRole("button", { name: "Send message", exact: true }).click();
  await expect(page.locator(".chat-scroll .bubble")).toHaveText(["First message", "My reply"]);
  inbox.push({ chatId: "reply", from: "akila", text: "New reply from Akila", timestampMillis: Date.now() - 3600000 });
  await expect(page.locator(".chat-scroll .bubble")).toHaveText(["First message", "My reply", "New reply from Akila"]);
  await expect(page.locator(".message-row.own small")).toHaveText("DELIVERED");
  await expect(page.locator(".chat-scroll .bubble")).toHaveText(["First message", "My reply", "New reply from Akila"]);
});
