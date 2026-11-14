import { test, expect, Page } from "@playwright/test";

type Options = { links?: Record<string, string>; bluetooth?: boolean; nativeAvailable?: boolean; noLan?: boolean; ownedHotspot?: boolean; samePeer?: boolean; joinCandidates?: boolean };
async function connectionFixture(page: Page, options: Options = {}) {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  const posts: { path: string; body: Record<string, unknown> }[] = [];
  const links = options.links || {};
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "POST") {
      posts.push({ path, body: route.request().postData() ? route.request().postDataJSON() : {} });
      return route.fulfill({ status: 202, json: { message: "Operation requested" } });
    }
    const data: Record<string, unknown> = {
      "/api/health": { peers: [] }, "/api/files": { files: [] },
      "/api/mesh": { deviceId: "Local", neighbours: Object.keys(links), links, names: {}, transport: "tcp+bluetooth", routes: [], inbox: [] },
      "/api/device": { deviceId: "Local", displayName: "Local", customName: "", visible: true, bluetooth: { available: options.bluetooth !== false, reason: options.bluetooth === false ? "Bluetooth adapter is powered off" : "" } },
      "/api/nearby": { bluetoothAvailable: options.bluetooth !== false, scanning: false, lastScanAt: 0, items: [
        { kind: "bluetooth", deviceId: "B", name: "Bluetooth laptop", address: "bt:AA:BB:CC:DD:EE:01", signal: 80, relay: true, state: "AVAILABLE", error: "", hops: null },
        { kind: "lan", deviceId: options.samePeer ? "B" : "L", name: "LAN desktop", address: "192.168.20.4:7000", signal: null, relay: true, state: "AVAILABLE", error: "", hops: null },
      ] },
      "/api/membership": { meshId: options.joinCandidates ? "" : "Community", phase: options.joinCandidates ? "LEFT" : "DISCOVERING", nearby: options.joinCandidates ? [
        { meshId: "Community", peerId: "A", kind: "lan", address: "192.168.20.4", peerPort: 5000, verified: false },
        { meshId: "Community", peerId: "A", kind: "bluetooth", address: "bt:AA:BB:CC:DD:EE:01", peerPort: 0, verified: false },
      ] : [], scanning: false, bluetoothAvailable: options.bluetooth !== false, discoveryError: options.noLan ? "No multicast-capable local network. Connect a LAN or hotspot." : "", error: "" },
      "/api/setup/networks": { networks: options.noLan ? [] : [{ address: "192.168.20.3", label: "wlan0 — 192.168.20.3" }] },
      "/api/radio": { available: options.nativeAvailable !== false, reason: options.nativeAvailable === false ? "Native network service is unavailable on this platform" : "", state: options.ownedHotspot ? "ACTIVE" : "STOPPED", mode: options.ownedHotspot ? "hotspot" : "", adapters: options.nativeAvailable === false ? [] : [
        { path: "/wifi/1", name: "wlan0", type: "wifi", hotspotCapable: true }, { path: "/p2p/1", name: "p2p-wlan0", type: "wifi-direct", hotspotCapable: false },
      ], peers: [{ devicePath: "/p2p/1", address: "AA:BB:CC:DD:EE:02", name: "Direct laptop" }] },
    };
    return route.fulfill({ json: data[path] || {} });
  });
  return posts;
}

test("shows combined nearby results while keeping connection settings separate", async ({ page }) => {
  await connectionFixture(page, { noLan: true });
  await page.goto("/");
  const methods = page.getByRole("region", { name: "Connection methods" });
  await expect(methods.getByRole("tab")).toHaveCount(4);
  const nearby = page.getByRole("region", { name: "Nearby devices", exact: true });
  await expect(nearby).toContainText("Bluetooth laptop");
  await expect(nearby).toContainText("LAN desktop");
  await expect(page.getByText(/No multicast-capable/)).toHaveCount(0);
  await methods.getByRole("tab", { name: /Wi-Fi \/ LAN/ }).click();
  await expect(methods.getByRole("tabpanel")).toContainText("No multicast-capable");
  await expect(methods.getByRole("tabpanel")).toContainText("Bluetooth discovery is separate");
  await expect(nearby).toContainText("LAN desktop");
  await expect(nearby).toContainText("Bluetooth laptop");
});

test("reports active transports separately from available adapters", async ({ page }) => {
  await connectionFixture(page, { links: { B: "bluetooth", C: "hotspot", L: "tcp" }, ownedHotspot: true });
  await page.goto("/");
  const methods = page.getByRole("region", { name: "Connection methods" });
  for (const label of [/^Bluetooth/, /^Hotspot/, /^Wi-Fi \/ LAN/]) await expect(methods.getByRole("tab", { name: label })).toContainText("1 active link");
  await expect(methods.getByRole("tab", { name: /^Wi-Fi Direct/ })).toContainText("Available");
  await expect(page.getByText("Active links: Bluetooth · Hotspot · TCP")).toBeVisible();
  await methods.getByRole("tab", { name: /^Hotspot/ }).click();
  await expect(methods.getByRole("tabpanel")).toContainText("Running");
  await expect(methods.getByLabel("Hotspot password", { exact: true })).toHaveCount(0);
  await expect(methods.getByRole("button", { name: "Stop hotspot", exact: true })).toBeDisabled();
});

test("Wi-Fi Direct uses its own scan and requires acknowledgement to connect", async ({ page }) => {
  const posts = await connectionFixture(page);
  await page.goto("/");
  const methods = page.getByRole("region", { name: "Connection methods" });
  await methods.getByRole("tab", { name: /^Wi-Fi Direct/ }).click();
  await page.getByRole("button", { name: "Scan nearby", exact: true }).click();
  await expect(methods.getByRole("button", { name: "Connect device", exact: true })).toBeDisabled();
  await methods.getByRole("checkbox").check();
  await methods.getByRole("button", { name: "Connect device", exact: true }).click();
  expect(posts).toEqual([
    { path: "/api/membership/scan", body: {} },
    { path: "/api/radio/scan", body: {} },
    { path: "/api/radio/connect", body: { devicePath: "/p2p/1", address: "AA:BB:CC:DD:EE:02", confirmed: true } },
  ]);
});

test("unavailable methods explain platform limitations without offering a hotspot form", async ({ page }) => {
  await connectionFixture(page, { bluetooth: false, nativeAvailable: false });
  await page.goto("/");
  const methods = page.getByRole("region", { name: "Connection methods" });
  await expect(methods.getByRole("tabpanel")).toContainText("Bluetooth adapter is powered off");
  await expect(page.getByRole("button", { name: "Scan nearby", exact: true })).toBeEnabled();
  await expect(methods.getByRole("button", { name: "Scan Bluetooth" })).toHaveCount(0);
  await methods.getByRole("tab", { name: /^Hotspot/ }).click();
  await expect(methods.getByRole("tabpanel")).toContainText("Native network service is unavailable on this platform");
  await expect(methods.getByLabel("Hotspot password", { exact: true })).toHaveCount(0);
});

test("method tabs support keyboard navigation and aligned mobile hotspot fields", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await connectionFixture(page);
  await page.goto("/");
  const methods = page.getByRole("region", { name: "Connection methods" });
  await methods.getByRole("tab", { name: /^Bluetooth/ }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(methods.getByRole("tab", { name: /^Wi-Fi \/ LAN/ })).toHaveAttribute("aria-selected", "true");
  await page.keyboard.press("End");
  await expect(methods.getByRole("tab", { name: /^Hotspot/ })).toHaveAttribute("aria-selected", "true");
  const panel = methods.getByRole("tabpanel");
  const fields = await Promise.all(["Hotspot adapter", "Hotspot name", "Hotspot password"].map(label => panel.getByLabel(label, { exact: true }).boundingBox()));
  expect(fields.every(box => !!box)).toBeTruthy();
  for (const field of fields) { expect(Math.abs(field!.x - fields[0]!.x)).toBeLessThan(1); expect(Math.abs(field!.width - fields[0]!.width)).toBeLessThan(1); }
  const checkbox = await panel.getByRole("checkbox").boundingBox();
  expect(checkbox!.width).toBe(16);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test("one peer with multiple candidate transports has a single map node", async ({ page }) => {
  await connectionFixture(page, { samePeer: true });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Inspect Bluetooth laptop" })).toHaveCount(1);
  await expect(page.getByRole("button", { name: "Inspect LAN desktop" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Inspect Bluetooth laptop" })).toContainText("Bluetooth + Wi-Fi / LAN");
});

test("a discovered mesh groups its transports and joins the explicitly selected candidate", async ({ page }) => {
  const posts = await connectionFixture(page, { joinCandidates: true });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Join mesh Community" })).toHaveCount(1);
  await page.getByRole("button", { name: "Join mesh Community" }).click();
  await page.getByRole("button", { name: /Bluetooth Found this mesh/ }).click();
  await expect(page.getByLabel("Mesh local network", { exact: true })).toHaveCount(0);
  await page.getByLabel("Mesh password", { exact: true }).fill("mesh-only-candidate-password");
  await page.getByRole("button", { name: "Authenticate and join" }).click();
  expect(posts).toContainEqual({ path: "/api/membership/join", body: {
    meshId: "Community", password: "mesh-only-candidate-password", localAddress: "192.168.20.3", peerId: "A", address: "bt:AA:BB:CC:DD:EE:01",
  } });
});


test("local connections are refreshed manually rather than on the map polling timer", async ({ page }) => {
  await page.clock.install();
  const posts = await connectionFixture(page);
  let checks = 0;
  let address = "192.168.20.3";
  await page.route("**/api/setup/networks", route => {
    checks++;
    return route.fulfill({ json: { networks: [{ address, label: `Wi-Fi — ${address}` }] } });
  });
  await page.goto("/");
  const methods = page.getByRole("region", { name: "Connection methods" });
  await methods.getByRole("tab", { name: /Wi-Fi \/ LAN/ }).click();
  await expect(methods.getByRole("tabpanel")).toContainText("192.168.20.3");
  const before = checks;
  address = "10.0.0.5";
  await page.clock.runFor(12100);
  expect(checks).toBe(before);
  await methods.getByRole("button", { name: "Refresh connections", exact: true }).click();
  await expect(methods.getByRole("tabpanel")).toContainText("10.0.0.5");
  expect(checks).toBeGreaterThan(before);
  expect(posts).toEqual([]);
});

test("one top scan checks local network, Bluetooth and supported Wi-Fi Direct without changing connections", async ({ page }) => {
  const posts = await connectionFixture(page);
  let networkReads = 0;
  page.on("request", request => { if (new URL(request.url()).pathname === "/api/setup/networks") networkReads++; });
  await page.goto("/");
  await expect.poll(() => networkReads).toBeGreaterThan(0);
  const previousReads = networkReads;
  const sidebar = page.locator(".network-inspector");
  await expect(sidebar.locator(".map-visibility")).toHaveCount(1);
  await expect(page.locator(".map-heading .map-visibility")).toHaveCount(0);
  await sidebar.getByRole("button", { name: "Scan nearby", exact: true }).click();
  await expect.poll(() => posts.length).toBe(2);
  expect(posts.map(post => post.path)).toEqual(["/api/membership/scan", "/api/radio/scan"]);
  await expect.poll(() => networkReads).toBeGreaterThan(previousReads);
  const scan = await sidebar.locator(".nearby-scan-card").boundingBox();
  const device = await sidebar.locator(".map-visibility").boundingBox();
  expect(scan!.y).toBeLessThan(device!.y);
});

test("nearby scan still searches the LAN when Bluetooth and native radio support are unavailable", async ({ page }) => {
  const posts = await connectionFixture(page, { bluetooth: false, nativeAvailable: false });
  await page.goto("/");
  await page.getByRole("button", { name: "Scan nearby", exact: true }).click();
  await expect.poll(() => posts.length).toBe(1);
  expect(posts).toEqual([{ path: "/api/membership/scan", body: {} }]);
  await expect(page.getByText("Search started. Nearby devices and meshes appear on the map as they are found.")).toBeVisible();
});
