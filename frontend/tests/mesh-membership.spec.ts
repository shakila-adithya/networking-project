import { test, expect, Page } from "@playwright/test";

async function mockMembership(page: Page, meshesNearby = true) {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  let meshId = "";
  let phase = "LEFT";
  const calls: { path: string; body: Record<string, unknown> }[] = [];
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const body = route.request().postDataJSON() || {};
    if (route.request().method() === "POST") calls.push({ path, body });
    if (path === "/api/membership/join") {
      if (body.password !== "correct password for mesh") return route.fulfill({ status: 409, json: { message: "Wrong password; mesh authentication failed" } });
      meshId = "NearbyMesh"; phase = "CONNECTED";
      return route.fulfill({ status: 202, json: { message: "Mesh authenticated" } });
    }
    if (path === "/api/membership/create") { meshId = String(body.meshId); phase = "DISCOVERING"; return route.fulfill({ status: 202, json: { message: "Mesh created" } }); }
    if (path === "/api/membership/leave") { meshId = ""; phase = "LEFT"; return route.fulfill({ json: { message: "Left mesh" } }); }
    if (path.startsWith("/api/radio/") || path === "/api/membership/scan") return route.fulfill({ status: 202, json: { message: "Operation requested" } });
    const data: Record<string, unknown> = {
      "/api/health": { peers: [] }, "/api/files": { files: [] }, "/api/mesh": { deviceId: "Local", neighbours: [], routes: [], links: {}, inbox: [] },
      "/api/device": { deviceId: "Local", displayName: "Local", customName: "", visible: false, transport: "tcp", bluetooth: { available: false, reason: "No adapter" } },
      "/api/nearby": { bluetoothAvailable: false, items: [], scanning: false, lastScanAt: 0 },
      "/api/membership": { meshId, phase, nearby: meshesNearby ? [{ meshId: "NearbyMesh", peerId: "Other", address: "192.168.50.2", peerPort: 5000, verified: false }] : [], error: "", discoveryError: "" },
      "/api/setup/networks": { networks: [{ address: "192.168.50.3", label: "Local Wi-Fi" }] },
      "/api/radio": { available: true, state: "STOPPED", mode: "", reason: "", adapters: [{ path: "/device/1", name: "wlan0", type: "wifi", hotspotCapable: true }], peers: [] },
    };
    return route.fulfill({ json: data[path] || {} });
  });
  return calls;
}

test("preview keeps membership and radio operations separate from live hardware", async ({ page }) => {
  const calls: string[] = [];
  page.on("request", request => { if (request.url().includes("/api/membership") || request.url().includes("/api/radio")) calls.push(request.url()); });
  await page.goto("/");
  const panel = page.getByRole("region", { name: "Relay mesh membership" });
  await expect(panel.locator(".connection-preview-note")).toBeVisible();
  await expect(panel.getByRole("button", { name: "Scan nearby", exact: true })).toBeEnabled();
  await panel.getByRole("button", { name: "Scan nearby", exact: true }).click();
  expect(calls).toEqual([]);
});

test("join authenticates unverified mesh and never puts passwords in browser storage", async ({ page }) => {
  const calls = await mockMembership(page);
  await page.goto("/");
  const panel = page.getByRole("region", { name: "Relay mesh membership" });
  await expect(panel.getByText(/Unverified name/)).toBeVisible();
  await panel.getByRole("button", { name: "Join mesh NearbyMesh" }).click();
  await panel.getByLabel("Mesh local network").selectOption("192.168.50.3");
  await panel.getByLabel("Mesh password", { exact: true }).fill("incorrect password for mesh");
  await panel.getByRole("button", { name: "Authenticate and join" }).click();
  await expect(panel.locator("#mesh-membership").getByRole("alert")).toContainText("Wrong password");
  await expect(panel.getByLabel("Mesh password", { exact: true })).toBeVisible();
  await panel.getByLabel("Mesh password", { exact: true }).fill("correct password for mesh");
  await panel.getByRole("button", { name: "Authenticate and join" }).click();
  await expect(panel.getByText("Connected", { exact: true })).toBeVisible();
  await expect(panel.getByRole("button", { name: "Leave mesh" })).toBeVisible();
  await expect(panel.getByLabel("Mesh password", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain("password for mesh");
  expect(calls.filter(c => c.path.endsWith("/join"))).toHaveLength(2);
  await panel.getByRole("button", { name: "Leave mesh" }).click();
  await expect(panel.getByRole("button", { name: "Create mesh", exact: true })).toBeVisible();
});

test("hotspot requires acknowledgement before changing the current Wi-Fi", async ({ page }) => {
  const calls = await mockMembership(page);
  await page.goto("/");
  const panel = page.getByRole("region", { name: "Relay mesh membership" });
  await panel.getByRole("tab", { name: /Hotspot/ }).click();
  await expect(panel.getByText(/This may disconnect my current Wi-Fi network/)).toBeVisible();
  await panel.getByLabel("Hotspot adapter").selectOption("/device/1");
  await panel.getByLabel("Hotspot password", { exact: true }).fill("radio-only-password");
  const start = panel.getByRole("button", { name: "Start hotspot" });
  await expect(start).toBeDisabled();
  expect(calls).toHaveLength(0);
  await panel.getByRole("checkbox").check();
  await start.click();
  await expect(panel.getByLabel("Hotspot password", { exact: true })).toHaveValue("");
  expect(calls).toContainEqual({ path: "/api/radio/hotspot", body: { devicePath: "/device/1", ssid: "Relay offline", password: "radio-only-password", confirmed: true } });
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain("radio-only-password");
});

test("live membership and radio forms fit a mobile viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockMembership(page);
  await page.goto("/");
  const panel = page.getByRole("region", { name: "Relay mesh membership" });
  await panel.getByRole("button", { name: "Join mesh NearbyMesh" }).click();
  await panel.getByRole("tab", { name: /Hotspot/ }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  for (const label of ["Mesh name", "Mesh password", "Hotspot password"]) {
    const box = await panel.getByLabel(label, { exact: true }).boundingBox();
    expect(box).not.toBeNull();
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  }
});


test("existing meshes come first and joining shows only detected connection choices", async ({ page }) => {
  await mockMembership(page);
  await page.goto("/");
  const membership = page.getByRole("region", { name: "Mesh membership", exact: true });
  await expect(membership.getByRole("heading", { name: "Find a mesh to join" })).toBeVisible();
  const join = membership.getByRole("button", { name: "Join mesh NearbyMesh" });
  const create = membership.getByRole("button", { name: "Create mesh", exact: true });
  expect((await join.boundingBox())!.y).toBeLessThan((await create.boundingBox())!.y);
  await join.click();
  await expect(membership.getByRole("button", { name: /Wi-Fi \/ LAN Found this mesh/ })).toHaveAttribute("aria-pressed", "true");
  await expect(membership.getByRole("button", { name: /Bluetooth Not detected/ })).toBeDisabled();
  await membership.getByText("Need another connection?", { exact: true }).click();
  await membership.getByRole("button", { name: "Set up hotspot", exact: true }).click();
  await expect(page.getByRole("tab", { name: /Hotspot/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("checkbox")).not.toBeChecked();
});

test("when no mesh is found, creating starts a password-protected group for others to join", async ({ page }) => {
  const calls = await mockMembership(page, false);
  await page.goto("/");
  const membership = page.getByRole("region", { name: "Mesh membership", exact: true });
  await expect(membership.getByText(/No meshes found yet/)).toBeVisible();
  await membership.getByRole("button", { name: "Create mesh", exact: true }).click();
  await expect(membership.getByText(/Other devices can find it and join with that password/)).toBeVisible();
  await membership.getByLabel("Mesh name", { exact: true }).fill("My-group");
  await membership.getByLabel("Mesh local network").selectOption("192.168.50.3");
  await membership.getByLabel("Mesh password", { exact: true }).fill("new password for my mesh");
  await membership.getByRole("button", { name: "Create this mesh", exact: true }).click();
  expect(calls).toContainEqual({ path: "/api/membership/create", body: { meshId: "My-group", password: "new password for my mesh", localAddress: "192.168.50.3" } });
  await expect(membership.getByRole("button", { name: "Leave mesh", exact: true })).toBeVisible();
  await expect(membership.getByLabel("Mesh password", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain("new password for my mesh");
});
