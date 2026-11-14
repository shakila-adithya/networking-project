import { test, expect, Page } from "@playwright/test";
async function discoveryFixture(page: Page) {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  const posts: { path: string; body: unknown }[] = [];
  let visible = false;
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    const state = () => ({ deviceId: "Local", visible, bluetoothAvailable: false, bluetoothReason: "No Bluetooth adapter", error: "", phase: "LEFT" });
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON(); posts.push({ path, body });
      if (path === "/api/visibility") { visible = body.visible; return route.fulfill({ json: state() }); }
      if (path === "/api/relay-storage") return route.fulfill({ json: { policy: body, status: { available: false, error: "Join a mesh first" } } });
      return route.fulfill({ json: { message: "Request accepted" } });
    }
    if (["/api/health", "/api/device"].includes(path)) return route.fulfill({ status: 503, json: { message: "No mesh joined" } });
    const mesh = { meshId: "NearbyGroup", peerId: "Member", address: "192.0.2.10", peerPort: 5000, kind: "lan", verified: false };
    const responses: Record<string, unknown> = {
      "/api/discovery": { visibility: state(), devices: [
        { deviceId: "Unjoined", name: "Visible laptop", meshId: "", address: "192.0.2.11", kind: "lan", verified: false },
        { deviceId: "Unjoined", name: "Visible laptop", meshId: "", address: "bt:AA:BB:CC:DD:EE:01", kind: "bluetooth", verified: false },
      ], meshes: [mesh] },
      "/api/membership": { meshId: "", phase: "LEFT", nearby: [mesh], error: "", discoveryError: "" },
      "/api/radio": { available: false, state: "STOPPED", adapters: [], peers: [], reason: "Unavailable" },
      "/api/setup/networks": { networks: [{ address: "192.0.2.20", label: "Wi-Fi" }], ports: { peerPort: 5000, restPort: 6000 } },
      "/api/setup": { configured: false, phase: "UNCONFIGURED" }, "/api/setup/device": { deviceId: "Local", customName: "Laptop" },
      "/api/relay-storage": { policy: { quotaBytes: 1_000_000_000, retentionHours: 24 }, status: { available: false, error: "Join a mesh to enable encrypted store-and-forward." } },
    };
    return route.fulfill({ json: responses[path] || {} });
  });
  return posts;
}
test("visible unjoined devices and mesh groups appear without a running mesh service", async ({ page }) => {
  await discoveryFixture(page); await page.goto("/");
  await expect(page.getByRole("button", { name: "Inspect Visible laptop", exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: "Inspect Visible laptop", exact: true }).click();
  await expect(page.locator(".network-inspector")).toContainText("not in a mesh yet");
  await page.getByRole("button", { name: "Inspect NearbyGroup", exact: true }).click();
  await page.getByRole("button", { name: "Join NearbyGroup", exact: true }).click();
  await expect(page.getByLabel("Mesh password", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Mesh name", { exact: true })).toHaveValue("NearbyGroup");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
test("visibility works before joining without Bluetooth and hiding states it leaves the mesh", async ({ page }) => {
  const posts = await discoveryFixture(page); await page.goto("/");
  const toggle = page.getByRole("switch", { name: "Visible to nearby devices" });
  const card = page.locator(".map-visibility");
  await expect(card.getByRole("heading", { name: "Laptop", exact: true })).toBeVisible();
  await expect(card).toContainText("Device ID Local");
  await expect(card.getByText("Hidden", { exact: true })).toBeVisible();
  await expect(toggle).toBeEnabled(); await expect(page.getByText(/Turning this off leaves your mesh/)).toBeVisible();
  await toggle.click(); await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(card.getByText("Visible", { exact: true })).toBeVisible();
  await toggle.click(); await expect(toggle).toHaveAttribute("aria-checked", "false");
  expect(posts).toEqual([{ path: "/api/visibility", body: { visible: true } }, { path: "/api/visibility", body: { visible: false } }]);
});
test("relay storage defaults and limits can be changed without exposing credentials", async ({ page }) => {
  const posts = await discoveryFixture(page); await page.goto("/settings/");
  await expect(page.getByLabel("Relay storage limit in GB")).toHaveValue("1");
  await expect(page.getByLabel("Relay data expiry in hours")).toHaveValue("24");
  await page.getByLabel("Relay storage limit in GB").fill("0.25"); await page.getByLabel("Relay data expiry in hours").fill("6");
  await page.getByRole("button", { name: "Save relay storage limits" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Relay storage limits saved" })).toBeVisible();
  expect(posts).toContainEqual({ path: "/api/relay-storage", body: { quotaBytes: 250_000_000, retentionHours: 6 } });
});

test("unavailable visibility keeps the device identity and can recover without claiming it is hidden", async ({ page }) => {
  await discoveryFixture(page);
  let unavailable = true;
  await page.route("**/api/discovery", route => unavailable ? route.fulfill({ status: 503, json: { message: "Service stopped" } }) : route.fallback());
  await page.goto("/");
  const card = page.locator(".map-visibility");
  await expect(card.getByRole("heading", { name: "Laptop", exact: true })).toBeVisible();
  await expect(card.getByText("Status unavailable", { exact: true })).toBeVisible();
  await expect(card.getByRole("switch")).toBeDisabled();
  await expect(card.getByText("Hidden", { exact: true })).toHaveCount(0);
  unavailable = false; await card.getByRole("button", { name: "Try again" }).click();
  await expect(card.getByRole("switch")).toBeEnabled();
  await expect(card.getByRole("alert")).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

for (const legacy of [false, true]) {
  test(`not joining a mesh is a neutral status (${legacy ? "legacy" : "structured"} backend)`, async ({ page }) => {
    await discoveryFixture(page);
    await page.route("**/api/health", route => route.fulfill({ status: 503, json: {
      ...(legacy ? {} : { code: "MESH_NOT_JOINED" }),
      message: "Relay isn't connected to a mesh yet. Join one on the Network map, or check your connection in Settings.",
    } }));
    await page.goto("/");
    const ready = page.locator(".mesh-ready-card");
    await expect(ready).toHaveAttribute("role", "status");
    await expect(ready).toContainText("Ready to join a mesh");
    await expect(page.locator(".connection-notice")).toHaveCount(0);
    await expect(page.locator(".api-error")).toHaveCount(0);
    const notice = page.locator(".connection-notice");
    await page.getByRole("navigation", { name: "Main navigation" }).getByRole("link", { name: "Messages", exact: true }).click();
    await expect(notice.getByRole("link", { name: /Open Network map/ })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  });
}
test("real service failures still show an error", async ({ page }) => {
  await discoveryFixture(page);
  await page.route("**/api/health", route => route.fulfill({ status: 503, json: { code: "SERVICE_UNAVAILABLE", message: "Relay could not start. Check Settings." } }));
  await page.goto("/");
  await expect(page.locator(".api-error")).toHaveAttribute("role", "alert");
  await expect(page.locator(".api-error")).toContainText("Relay could not start");
  await expect(page.locator(".connection-notice")).toHaveCount(0);
});
