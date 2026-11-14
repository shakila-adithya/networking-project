import { test, expect, Page } from "@playwright/test";

const settings = { peerId: "laptop-A", clusterId: "family-mesh", address: "192.168.1.9", peerPort: 5010, restPort: 6010, discoveryGroup: "239.255.42.99", discoveryPort: 45555 };
async function mock(page: Page, phase = "ERROR", running = false) {
  const calls: { path: string; body: unknown }[] = [];
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "POST") {
      calls.push({ path, body: route.request().postDataJSON() });
      if (path === "/api/setup/device") return route.fulfill({ json: { deviceId: "laptop-A", customName: route.request().postDataJSON().displayName } });
      return route.fulfill({ json: { message: "Network saved. Relay is reconnecting. Your mesh password and files are kept." } });
    }
    if (!running && path === "/api/device") return route.fulfill({ status: 503, json: { message: "Not connected yet" } });
    const responses: Record<string, unknown> = {
      "/api/setup": { configured: phase !== "UNCONFIGURED", phase, settings: phase === "UNCONFIGURED" ? undefined : settings, error: "" },
      "/api/setup/networks": { networks: [{ address: "192.168.1.20", label: "Wi-Fi — 192.168.1.20" }], ports: { peerPort: 5010, restPort: 6010 } },
      "/api/setup/device": { deviceId: "laptop-A", customName: "My laptop" },
      "/api/device": { deviceId: "laptop-A", customName: "My laptop", displayName: "My laptop", visible: false, bluetooth: { available: false } },
      "/api/health": { peers: [] }, "/api/mesh": { deviceId: "laptop-A", neighbours: [], links: [], routes: [], inbox: [] },
      "/api/files": { files: [] }, "/api/nearby": { items: [] },
    };
    return route.fulfill({ json: responses[path] || {} });
  });
  return calls;
}

test("settings explains a changed network and repairs it without asking for the saved password", async ({ page }) => {
  const calls = await mock(page);
  await page.goto("/settings/");
  await expect(page.getByRole("heading", { name: "Your network changed" })).toBeVisible();
  await expect(page.getByText("Updating interrupts current Relay transfers.", { exact: false })).toBeVisible();
  await expect(page.getByText("Cluster setup", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Connection to use")).toHaveValue("192.168.1.20");
  await page.getByRole("button", { name: "Use this connection", exact: true }).click();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ path: "/api/setup/network", body: { address: "192.168.1.20" } });
  await expect(page.getByRole("status").filter({ hasText: "Relay is reconnecting using this connection" })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test("device name can be changed while the mesh service is stopped", async ({ page }) => {
  const calls = await mock(page, "UNCONFIGURED");
  await page.goto("/settings/");
  await expect(page.getByRole("heading", { name: "Join your first mesh" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Create or join a mesh", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Use this connection", exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Device name", { exact: true })).toHaveValue("My laptop");
  await page.getByLabel("Device name", { exact: true }).fill("Sam's laptop");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ path: "/api/setup/device", body: { displayName: "Sam's laptop" } });
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
});

test("manual setup is tucked away and clears the password after saving", async ({ page }) => {
  const calls = await mock(page);
  await page.goto("/settings/");
  await expect(page.getByLabel("Mesh password", { exact: true })).not.toBeVisible();
  await page.locator(".settings-advanced > summary").click();
  await page.getByText("Set up a mesh manually", { exact: true }).click();
  await page.getByLabel("Local network", { exact: true }).selectOption("192.168.1.20");
  await page.getByLabel("Mesh password", { exact: true }).fill("test-mesh-password-123");
  await page.getByRole("button", { name: "Save manual settings and reconnect" }).click();
  await expect.poll(() => calls.length).toBe(2);
  expect(calls.map(c => c.path)).toEqual(["/api/setup/apply", "/api/setup/restart"]);
  await expect(page.getByLabel("Mesh password", { exact: true })).toHaveValue("");
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain("test-mesh-password-123");
});

test("leaving a mesh cannot be undone by the network repair control", async ({ page }) => {
  const calls = await mock(page, "LEFT");
  await page.goto("/settings/");
  await expect(page.getByRole("heading", { name: "You left the mesh" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Use this connection", exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Create or join a mesh" })).toHaveCount(0);
  await expect(page.getByLabel("Connection to use")).toHaveCount(0);
  await page.locator(".settings-advanced > summary").click();
  await expect(page.getByRole("button", { name: "Restart Relay", exact: true })).toHaveCount(0);
  expect(calls).toEqual([]);
});

test("preview settings do not read or change the live device", async ({ page }) => {
  let setupRequests = 0;
  await page.route("**/api/setup**", route => { setupRequests++; return route.fulfill({ json: {} }); });
  await page.goto("/settings/");
  await expect(page.getByRole("heading", { name: "This is a design preview" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Use this connection", exact: true })).toBeDisabled();
  await page.locator(".settings-advanced > summary").click();
  await page.getByText("Set up a mesh manually", { exact: true }).click();
  await expect(page.getByRole("button", { name: "Save manual settings and reconnect" })).toBeDisabled();
  expect(setupRequests).toBe(0);
});


test("connection lookup failure can be retried manually", async ({ page }) => {
  await page.clock.install();
  await mock(page);
  let fail = true;
  await page.route("**/api/setup/networks", route => fail
    ? route.fulfill({ status: 503, json: { message: "Network check unavailable." } })
    : route.fulfill({ json: { networks: [{ address: "192.168.1.20", label: "Wi-Fi — 192.168.1.20" }], ports: { peerPort: 5010, restPort: 6010 } } }));
  await page.goto("/settings/");
  await expect(page.getByRole("alert").filter({ hasText: "Couldn't check your connections" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Use this connection", exact: true })).toBeDisabled();
  fail = false;
  await page.getByRole("button", { name: "Refresh connections", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Couldn't check your connections" })).toHaveCount(0);
  await expect(page.getByLabel("Connection to use")).toHaveValue("192.168.1.20");
  await expect(page.getByRole("button", { name: "Use this connection", exact: true })).toBeEnabled();
});


for (const phase of ["UNCONFIGURED", "LEFT"]) {
  test(`connections refresh manually without joining or restarting in ${phase}`, async ({ page }) => {
    await page.clock.install();
    const calls = await mock(page, phase);
    let address = "192.168.1.20";
    let checks = 0;
    await page.route("**/api/setup/networks", route => {
      checks++;
      return route.fulfill({ json: { networks: [{ address, label: `Wi-Fi — ${address}` }], ports: { peerPort: 5010, restPort: 6010 } } });
    });
    await page.goto("/settings/");
    const found = page.getByRole("list", { name: "Connections found" });
    await expect(found).toContainText("192.168.1.20");
    await expect(page.getByRole("button", { name: "Refresh connections", exact: true })).toBeEnabled();
    const before = checks;
    address = "10.0.0.5";
    await page.clock.runFor(9100);
    await page.evaluate(() => { window.dispatchEvent(new Event("focus")); window.dispatchEvent(new Event("online")); document.dispatchEvent(new Event("visibilitychange")); });
    expect(checks).toBe(before);
    await expect(found).toContainText("192.168.1.20");
    await page.getByRole("button", { name: "Refresh connections", exact: true }).click();
    await expect(found).toContainText("10.0.0.5");
    await expect(found).not.toContainText("192.168.1.20");
    expect(checks).toBeGreaterThan(before);
    await expect(page.getByRole("button", { name: "Use this connection", exact: true })).toHaveCount(0);
    expect(calls).toEqual([]);
  });
}


test("manual refresh preserves a chosen connection without background network checks", async ({ page }) => {
  await page.clock.install();
  const calls = await mock(page, "CONNECTED", true);
  let checks = 0;
  await page.route("**/api/setup/networks", route => {
    checks++;
    return route.fulfill({ json: { networks: [
      { address: "192.168.1.20", label: "Wi-Fi — 192.168.1.20" },
      { address: "10.0.0.5", label: "Ethernet — 10.0.0.5" },
    ], ports: { peerPort: 5010, restPort: 6010 } } });
  });
  await page.goto("/settings/");
  await page.getByLabel("Connection to use").selectOption("10.0.0.5");
  const before = checks;
  await page.clock.runFor(3100);
  expect(checks).toBe(before);
  await page.getByRole("button", { name: "Refresh connections", exact: true }).click();
  await expect.poll(() => checks).toBeGreaterThan(before);
  await expect(page.getByLabel("Connection to use")).toHaveValue("10.0.0.5");
  expect(calls).toEqual([]);
  await page.getByRole("navigation", { name: "Main navigation" }).getByRole("link", { name: "Messages", exact: true }).click();
  await expect(page).toHaveURL(/messages/);
  const stopped = checks;
  await page.clock.runFor(9100);
  expect(checks).toBe(stopped);
});
