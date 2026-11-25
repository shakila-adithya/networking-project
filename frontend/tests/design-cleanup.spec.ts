import { test, expect } from "@playwright/test";

test("navigation owns Settings and the preview switch, with no duplicate footer", async ({ page }) => {
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await expect(nav.getByRole("link", { name: "Settings", exact: true })).toBeVisible();
  await expect(nav.getByRole("link", { name: "My device", exact: true })).toHaveCount(0);
  await expect(nav.getByRole("button", { name: "Use live network" })).toBeVisible();
  await expect(page.locator(".mode-control")).toHaveCount(0);
  await expect(page.getByRole("group", { name: "Map controls" })).toBeVisible();
  const sidebar = page.locator(".network-inspector");
  // Map and sidebar share one height and end on the same line.
  const mapBox = (await page.locator(".topology").boundingBox())!, sideBox = (await sidebar.boundingBox())!;
  expect(Math.abs(mapBox.y - sideBox.y)).toBeLessThan(1);
  expect(Math.abs(mapBox.y + mapBox.height - (sideBox.y + sideBox.height))).toBeLessThan(1);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await sidebar.evaluate(element => element.scrollHeight <= element.clientHeight + 1)).toBeTruthy();
  await expect(nav.getByRole("link", { name: "Settings", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await nav.getByRole("link", { name: "Settings", exact: true }).click();
  await expect(page).toHaveURL(/settings/);
  await expect(page.getByRole("region", { name: "My device" }).getByLabel("Device name")).toBeVisible();
});

test("file picking has one control and messages use a green Delivered pill without the shared-files shortcut", async ({ page }) => {
  await page.goto("/files/");
  await expect(page.getByRole("button", { name: "Add more", exact: true })).toHaveCount(0);
  await expect(page.locator(".dropzone")).toHaveCount(1);
  for (const name of ["All", "Sending", "Receiving", "Sent", "Received", "Waiting for a route", "Failed"]) {
    await expect(page.getByRole("group", { name: "Filter transfers" }).getByRole("button", { name, exact: true })).toBeVisible();
  }
  await page.getByRole("button", { name: "Sent", exact: true }).click();
  await expect(page.locator(".transfer-status.delivered")).toHaveText("Delivered");
  await expect(page.locator(".transfer-status.delivered")).toHaveCSS("color", "rgb(169, 221, 183)");
  await page.goto("/messages/");
  await expect(page.getByRole("link", { name: "Open shared files", exact: true })).toHaveCount(0);
  await expect(page.locator(".delivery-pill.green")).toHaveText("Delivered");
});

test("live filters show actual incoming progress, sent confirmations and route waiting separately", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  const sent = { transferId: "sent", fileId: "s", fileName: "sent.pdf", to: "R", size: 1000, state: "DELIVERED", percent: 100, error: "" };
  const waiting = { ...sent, transferId: "waiting", fileName: "waiting.pdf", state: "SENDING", percent: 20, stalled: true };
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    const responses: Record<string, unknown> = {
      "/api/health": { peers: [] },
      "/api/files": { files: [{ fileId: "received", fileName: "received.pdf", size: 1000 }] },
      "/api/mesh": { deviceId: "Local", neighbours: [], routes: [], inbox: [] },
      "/api/device": { deviceId: "Local", displayName: "Local", bluetooth: { available: false } },
      "/api/nearby": { items: [] },
      "/api/mesh/transfers": { transfers: [sent, waiting], incoming: [{ fileName: "incoming.pdf", from: "B", fileId: "in", percent: 37, size: 2000, state: "RECEIVING", error: "" }] },
      "/api/mesh/transfers/waiting": waiting,
    };
    return route.fulfill({ json: responses[path] || {} });
  });
  await page.goto("/files/");
  const filters = page.getByRole("group", { name: "Filter transfers" });
  await filters.getByRole("button", { name: "Receiving", exact: true }).click();
  await expect(page.locator(".transfers")).toContainText("incoming.pdf");
  await expect(page.locator(".transfers")).toContainText("37%");
  await expect(page.locator(".transfers").getByRole("link")).toHaveCount(0);
  await filters.getByRole("button", { name: "Sent", exact: true }).click();
  await expect(page.locator(".transfers")).toContainText("sent.pdf");
  await expect(page.locator(".transfers")).not.toContainText("incoming.pdf");
  await filters.getByRole("button", { name: "Received", exact: true }).click();
  await expect(page.locator(".transfers").getByRole("link", { name: "Download received.pdf" })).toBeVisible();
  await filters.getByRole("button", { name: "Waiting for a route", exact: true }).click();
  await expect(page.locator(".transfers")).toContainText("waiting.pdf");
  await expect(page.locator(".transfers")).not.toContainText("received.pdf");
});
