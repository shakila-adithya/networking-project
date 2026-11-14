import { test, expect } from "@playwright/test";

test("all exported routes load with complete assets and correct icon geometry", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  for (const route of ["/", "/messages/", "/files/", "/device/", "/settings/", "/how-it-works/"]) {
    await page.goto(route);
    await expect(
      page.getByRole("navigation", { name: "Main navigation" }),
    ).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    const invalid = await page.locator("img.icon").evaluateAll((images) =>
      images
        .filter((image) => {
          const img = image as HTMLImageElement,
            r = img.getBoundingClientRect();
          return (
            !img.complete ||
            !img.naturalWidth ||
            Math.abs(r.width - img.width) > 1 ||
            Math.abs(r.height - img.height) > 1
          );
        })
        .map((image) => (image as HTMLImageElement).src),
    );
    expect(invalid).toEqual([]);
  }
  expect(errors).toEqual([]);
});

test("file selection survives resetting the file input and produces preview activity", async ({
  page,
}) => {
  await page.goto("/files/");
  await page
    .locator("input[type=file]")
    .setInputFiles({
      name: "smoke.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("preview file"),
    });
  await expect(page.getByText("smoke.txt", { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: "Send to Amaya", exact: true })
    .click();
  await expect(
    page.getByText("To Amaya · preview only", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Waiting for a route", exact: true }).click();
  await expect(page.getByText("smoke.txt", { exact: true })).toBeVisible();
});

test("preview messaging and client navigation work", async ({ page }) => {
  await page.goto("/messages/");
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("Browser smoke check");
  await page.getByRole("button", { name: "Send message", exact: true }).click();
  await expect(
    page.getByText("Browser smoke check", { exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Share files", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Share beyond your range." }),
  ).toBeVisible();
});

test("mobile screens fit the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const route of ["/", "/messages/", "/files/"]) {
    await page.goto(route);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(390);
  }
});

test("live chat sends to the selected backend peer and confirms delivery", async ({
  page,
}) => {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  await page.route("**/api/health", (r) =>
    r.fulfill({
      json: {
        peers: [
          { peerId: "C", source: "local", status: "ALIVE" },
          {
            peerId: "A",
            source: "configured",
            address: "192.168.1.2:5000",
            status: "ALIVE",
          },
        ],
      },
    }),
  );
  await page.route("**/api/files", (r) => r.fulfill({ json: { files: [] } }));
  await page.route("**/api/mesh", (r) =>
    r.fulfill({
      json: { deviceId: "C", transport: "TCP", neighbours: ["A"], inbox: [] },
    }),
  );
  let sent: unknown;
  await page.route("**/api/mesh/chat", async (r) => {
    sent = r.request().postDataJSON();
    await r.fulfill({
      status: 202,
      json: { chatId: "test-chat", status: "PENDING" },
    });
  });
  await page.route("**/api/mesh/chat/test-chat", (r) =>
    r.fulfill({ json: { status: "DELIVERED" } }),
  );
  await page.goto("/messages/");
  await expect(
    page.getByRole("navigation", { name: "Main navigation" }).getByRole("button", { name: "View design preview" }),
  ).toBeVisible();
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("Live API check");
  await page.getByRole("button", { name: "Send message", exact: true }).click();
  await expect(page.getByText("DELIVERED", { exact: true })).toBeVisible();
  expect(sent).toEqual({ to: "A", text: "Live API check" });
});

test("live file sharing never preselects a recipient and keeps going after one fails", async ({
  page,
}) => {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  await page.route("**/api/health", (r) =>
    r.fulfill({
      json: {
        peers: [
          { peerId: "C", source: "local", status: "ALIVE" },
          { peerId: "A", address: "10.0.0.2:5000", source: "configured", status: "ALIVE" },
          { peerId: "B", address: "10.0.0.3:5000", source: "configured", status: "ALIVE" },
        ],
      },
    }),
  );
  await page.route("**/api/files", (r) => {
    if (r.request().method() === "GET") return r.fulfill({ json: { files: [] } });
    return r.fallback();
  });
  await page.route("**/api/mesh", (r) =>
    r.fulfill({
      json: { deviceId: "C", transport: "tcp", neighbours: ["A", "B"], routes: [], inbox: [] },
    }),
  );
  const uploads: string[] = [];
  await page.route("**/api/files?recipient=*", async (r) => {
    const who = new URL(r.request().url()).searchParams.get("recipient")!;
    uploads.push(who);
    if (who === "A")
      return r.fulfill({ status: 400, json: { message: "Selected peer is not registered." } });
    return r.fulfill({ status: 201, json: { shareStatus: "COMPLETE" } });
  });
  await page.goto("/files/");
  await expect(page.locator("button.recipient").first()).toBeVisible();
  // A real device called "A" must not be chosen silently (the design sample's person is also "A").
  await expect(page.locator("button.recipient.checked")).toHaveCount(0);
  await page.locator('input[type="file"]').setInputFiles({
    name: "note.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("hello"),
  });
  await page.locator("button.recipient", { hasText: "A" }).first().click();
  await page.locator("button.recipient", { hasText: "B" }).first().click();
  await page.getByRole("button", { name: /^Send to/ }).click();
  await expect(page.getByText("A: Selected peer is not registered.")).toBeVisible();
  // B still received the file even though A failed first.
  expect(uploads.sort()).toEqual(["A", "B"]);
  await expect(page.getByText("To B ·")).toBeVisible();
});

test("mesh file transfer shows progress, waiting, failure and resume", async ({
  page,
}) => {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  await page.route("**/api/health", (r) =>
    r.fulfill({ json: { peers: [{ peerId: "C", source: "local", status: "ALIVE" }] } }),
  );
  await page.route("**/api/files", (r) => {
    if (r.request().method() === "GET") return r.fulfill({ json: { files: [] } });
    return r.fallback();
  });
  await page.route("**/api/mesh", (r) =>
    r.fulfill({
      json: {
        deviceId: "C",
        transport: "tcp",
        neighbours: ["B"],
        routes: [{ destination: "D", nextHop: "B", cost: 2, hops: 2, valid: true }],
        inbox: [],
      },
    }),
  );
  // D is two hops away: the backend answers with a running transfer instead of "delivered".
  await page.route("**/api/files?recipient=*", (r) =>
    r.fulfill({
      status: 202,
      json: {
        shareStatus: "TRANSFERRING",
        transfers: [{ peer: "D", transferId: "t1" }],
        deliveredTo: [],
        failedPeers: {},
      },
    }),
  );
  const status = (state: string, percent: number, stalled = false, error = "") => ({
    transferId: "t1", state, percent, stalled, error,
  });
  let phase = 0;
  await page.route("**/api/mesh/transfers/t1", (r) => {
    const sequence = [
      status("SENDING", 40),
      status("SENDING", 45, true),
      status("FAILED", 45, false, "no response from D for 45 s (route unavailable); the transfer can be resumed"),
    ];
    return r.fulfill({ json: sequence[Math.min(phase++, sequence.length - 1)] });
  });
  await page.route("**/api/mesh/transfers/t1/resume", (r) =>
    r.fulfill({ status: 202, json: status("SENDING", 46) }),
  );

  await page.goto("/files/");
  await page.locator('input[type="file"]').setInputFiles({
    name: "map.bin", mimeType: "application/octet-stream", buffer: Buffer.alloc(100_000, 1),
  });
  await page
    .locator("button.recipient")
    .filter({ has: page.getByText("D", { exact: true }) })
    .click();
  await page.getByRole("button", { name: /^Send to/ }).click();

  await expect(page.getByText("40%")).toBeVisible();                       // live progress
  await expect(page.getByText(/waiting for a route · 45% sent/)).toBeVisible(); // stalled: no route yet
  await expect(page.getByText(/the transfer can be resumed/)).toBeVisible();   // gave up, resumable
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.getByText("46%")).toBeVisible();                       // running again
});

test("live mode shows the real transport and nearby Bluetooth devices", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  await page.route("**/api/health", (r) =>
    r.fulfill({ json: { peers: [{ peerId: "C", source: "local", status: "ALIVE" }] } }),
  );
  await page.route("**/api/files", (r) => r.fulfill({ json: { files: [] } }));
  await page.route("**/api/mesh", (r) =>
    r.fulfill({
      json: {
        deviceId: "C",
        transport: "tcp+bluetooth",
        neighbours: [],
        routes: [],
        inbox: [],
        bluetooth: {
          adapter: "28:11:A8:30:EA:39",
          advertisedAs: "relay:C",
          lastError: "",
          nearby: [
            { address: "34:09:C9:B1:39:88", name: "soundcore R60i NC", rssi: null, relayDevice: null },
            { address: "AA:BB:CC:DD:EE:01", name: "relay:B", rssi: -55, relayDevice: "B" },
          ],
        },
      },
    }),
  );
  await page.goto("/");
  await expect(page.getByRole("banner").locator(".connection")).toHaveCount(0);
  await expect(page.getByText("NEARBY DEVICES", { exact: true })).toHaveCount(0);
});

// ---- My device, visibility and nearby devices ----

type Device = {
  deviceId: string;
  displayName: string;
  customName: string;
  visible: boolean;
  transport: string;
  bluetooth: Record<string, unknown>;
};
const baseDevice: Device = {
  deviceId: "C",
  displayName: "C",
  customName: "",
  visible: false,
  transport: "tcp+bluetooth",
  bluetooth: { available: true, adapter: "28:11:A8:30:EA:39", advertising: false, advertisedAs: "fedora", error: "" },
};

/** Intercepts the API of a running node. `device` is mutated by POST /api/device like the real node does. */
async function mockNode(page: import("@playwright/test").Page, device: Device, nearbyItems: unknown[] = []) {
  await page.addInitScript(() => localStorage.setItem("relay-mode", "live"));
  await page.route("**/api/health", (r) =>
    r.fulfill({ json: { peers: [{ peerId: "C", source: "local", status: "ALIVE" }] } }),
  );
  await page.route("**/api/files", (r) => r.fulfill({ json: { files: [] } }));
  await page.route("**/api/mesh", (r) =>
    r.fulfill({ json: { deviceId: "C", deviceName: device.displayName, names: {}, transport: "tcp+bluetooth", neighbours: [], routes: [], inbox: [] } }),
  );
  const posts: { path: string; body: unknown }[] = [];
  await page.route("**/api/membership", r => r.fulfill({ json: { meshId: "Community", phase: "DISCOVERING", nearby: [], bluetoothAvailable: true, scanning: false, error: "", discoveryError: "" } }));
  await page.route("**/api/membership/scan", r => {
    posts.push({ path: "/api/membership/scan", body: r.request().postDataJSON() });
    return r.fulfill({ status: 202, json: { message: "Search started" } });
  });
  await page.route("**/api/radio", r => r.fulfill({ json: { available: false, state: "STOPPED", adapters: [], peers: [] } }));
  const visibility = () => ({ deviceId: device.deviceId, visible: device.visible, displayName: device.customName, bluetoothAvailable: !!device.bluetooth.available, bluetoothReason: device.bluetooth.reason || "", phase: "DISCOVERING", error: "" });
  await page.route("**/api/discovery", r => r.fulfill({ json: { devices: [], meshes: [], visibility: visibility() } }));
  await page.route("**/api/visibility", r => {
    const body = r.request().postDataJSON(); posts.push({ path: "/api/visibility", body }); device.visible = body.visible;
    return r.fulfill({ json: visibility() });
  });
  await page.route("**/api/device", async (r) => {
    if (r.request().method() === "POST") {
      const body = r.request().postDataJSON();
      posts.push({ path: "/api/device", body });
      if (typeof body.displayName === "string" && body.displayName.length > 32)
        return r.fulfill({ status: 400, json: { message: "The device name can be at most 32 characters." } });
      if (body.displayName !== undefined) {
        device.customName = body.displayName;
        device.displayName = body.displayName || "C";
      }
      if (body.visible !== undefined) {
        device.visible = body.visible;
        device.bluetooth = {
          ...device.bluetooth,
          advertising: body.visible,
          advertisedAs: body.visible ? (device.customName ? `${device.customName} [relay:C]` : "relay:C") : "fedora",
        };
      }
    }
    return r.fulfill({ json: device });
  });
  const nearby = { bluetoothAvailable: true, scanning: false, lastScanAt: 1760000000000, items: nearbyItems };
  await page.route("**/api/nearby", (r) => r.fulfill({ json: nearby }));
  await page.route("**/api/bluetooth/scan", (r) => {
    posts.push({ path: "/api/bluetooth/scan", body: null });
    return r.fulfill({ status: 202, json: { scanning: true } });
  });
  await page.route("**/api/mesh/connect", (r) => {
    posts.push({ path: "/api/mesh/connect", body: r.request().postDataJSON() });
    return r.fulfill({ status: 202, json: { connecting: "B" } });
  });
  return posts;
}

test("My device lets the user set the name and offers no visibility control", async ({ page }) => {
  const device = { ...baseDevice };
  const posts = await mockNode(page, device);
  await page.goto("/settings/");
  await expect(page.getByRole("heading", { name: "Your device name" })).toBeVisible();
  await expect(
    page.getByRole("navigation", { name: "Main navigation" }).getByRole("link", { name: "Settings", exact: true }),
  ).toHaveClass(/active/);
  await expect(page.locator(".device-identity small")).toContainText("Device ID");
  // visibility lives on the Network map, not here
  await expect(page.getByRole("switch")).toHaveCount(0);
  await expect(page.getByText(/use the visibility switch/)).toBeVisible();

  const save = page.getByRole("button", { name: "Save", exact: true });
  await expect(save).toBeDisabled(); // nothing changed yet
  await page.getByLabel("Device name").fill("Maya's laptop");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  expect(posts).toEqual([{ path: "/api/device", body: { displayName: "Maya's laptop" } }]);
  await expect(page.getByText("Maya's laptop").first()).toBeVisible();
});

test("My device refuses a name that is too long and shows the node's error", async ({ page }) => {
  const posts = await mockNode(page, { ...baseDevice });
  await page.goto("/settings/");
  await page.getByLabel("Device name").fill("x".repeat(33));
  await expect(page.getByText("33/32 characters")).toBeVisible();
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
  expect(posts).toEqual([]);
});

test("visibility is always available on the map and hiding warns that it leaves the mesh", async ({ page }) => {
  const device = { ...baseDevice, customName: "Maya's laptop", displayName: "Maya's laptop" };
  const posts = await mockNode(page, device);
  await page.goto("/");
  await expect(page.getByRole("switch")).toHaveCount(1); // no map selection is needed
  await page.getByRole("button", { name: "Inspect Maya's laptop" }).click();
  const toggle = page.getByRole("switch", { name: "Visible to nearby devices" });
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await expect(page.locator(".map-visibility").getByText("Hidden", { exact: true })).toBeVisible();
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(page.getByText("Other Relay devices can find your device on their map.", { exact: true })).toBeVisible();
  expect(posts).toEqual([{ path: "/api/visibility", body: { visible: true } }]);
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  expect(posts.at(-1)).toEqual({ path: "/api/visibility", body: { visible: false } });
});

test("visibility remains available for LAN when Bluetooth is unavailable", async ({ page }) => {
  const device = { ...baseDevice, transport: "tcp", bluetooth: { available: false, reason: "Bluetooth is turned off (adapter 28:11:A8:30:EA:39)" } };
  await mockNode(page, device);
  await page.goto("/");
  await page.getByRole("button", { name: "Inspect You" }).click();
  await expect(page.getByRole("switch", { name: "Visible to nearby devices" })).toBeEnabled();
  await expect(page.locator(".map-visibility").getByText(/Bluetooth is turned off/)).toBeVisible();
});

test("nearby devices are scanned and connected from the map, not a list", async ({ page }) => {
  const posts = await mockNode(page, { ...baseDevice }, [
    { kind: "bluetooth", deviceId: "A", name: "Amaya's phone", address: "bt:AA:BB:CC:DD:EE:01", signal: 80, rssi: -60, relay: true, state: "CONNECTED", error: "", hops: 1 },
    { kind: "bluetooth", deviceId: "B", name: "Basil's laptop", address: "bt:AA:BB:CC:DD:EE:02", signal: 50, rssi: -75, relay: true, state: "AVAILABLE", error: "", hops: null },
    { kind: "bluetooth", deviceId: "D", name: "Dani", address: "bt:AA:BB:CC:DD:EE:03", signal: 20, rssi: -88, relay: true, state: "FAILED", error: "cannot connect to AA:BB:CC:DD:EE:03: the device refused the connection", hops: null },
    { kind: "bluetooth", deviceId: null, name: "soundcore R60i NC", address: "bt:34:09:C9:B1:39:88", signal: null, rssi: null, relay: false, state: "OTHER", error: "", hops: null },
  ]);
  await page.goto("/");
  await expect(page.getByRole("region", { name: "Nearby devices" })).toHaveCount(0);
  await page.getByRole("button", { name: "Scan nearby", exact: true }).click();
  await page.getByRole("button", { name: "Inspect Basil's laptop" }).click();
  await page.locator(".connect-card").getByRole("button", { name: "Connect" }).click();
  expect(posts).toEqual([
    { path: "/api/membership/scan", body: {} },
    { path: "/api/mesh/connect", body: { deviceId: "B", candidateAddress: "bt:AA:BB:CC:DD:EE:02" } },
  ]);
});

test("a discovered device on the map shows its details and a Connect button", async ({ page }) => {
  const posts = await mockNode(page, { ...baseDevice }, [
    { kind: "bluetooth", deviceId: "B", name: "Basil's laptop", address: "bt:AA:BB:CC:DD:EE:02", signal: 50, rssi: -75, relay: true, state: "AVAILABLE", error: "", hops: null },
  ]);
  await page.goto("/");
  await page.getByRole("button", { name: "Inspect Basil's laptop" }).click();
  await expect(page.getByText(/Not connected yet/).first()).toBeVisible();
  await page.locator(".connect-card").getByRole("button", { name: "Connect" }).click();
  expect(posts).toEqual([{ path: "/api/mesh/connect", body: { deviceId: "B", candidateAddress: "bt:AA:BB:CC:DD:EE:02" } }]);
});

test("the design preview works without a node: name, visibility, scan and connect", async ({ page }) => {
  await page.goto("/settings/");
  await page.getByLabel("Device name").fill("Preview Phone");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await page.goto("/");
  await page.getByRole("button", { name: "Inspect You" }).click();
  const toggle = page.getByRole("switch", { name: "Visible to nearby devices" });
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await page.getByRole("button", { name: "Reset inspector" }).click();
  await page.getByRole("button", { name: "Scan nearby", exact: true }).click();
  await expect(page.getByRole("button", { name: "Scanning…" })).toBeVisible();
});

test("selecting this device before its live ID loads keeps the visibility inspector open", async ({ page }) => {
  await mockNode(page, { ...baseDevice, bluetooth: { available: false, reason: "Bluetooth is turned off" } });
  let release!: () => void;
  const ready = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/mesh", async route => {
    await ready;
    await route.fulfill({ json: { deviceId: "C", deviceName: "C", names: {}, transport: "tcp", neighbours: [], routes: [], inbox: [] } });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Inspect You" }).click();
  await expect(page.getByRole("switch", { name: "Visible to nearby devices" })).toBeVisible();
  release();
  await expect(page.locator(".map-visibility").getByText(/Bluetooth is turned off/)).toBeVisible();
  await expect(page.getByRole("switch", { name: "Visible to nearby devices" })).toBeEnabled();
});

test("How Relay works is its own page and the navigation stays centred", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Chat and share files with no internet" })).toHaveCount(0);
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  const box = (await nav.boundingBox())!;
  expect(Math.abs(box.x + box.width / 2 - 720)).toBeLessThan(40);
  await nav.getByRole("link", { name: "How Relay works" }).click();
  await expect(page).toHaveURL(/how-it-works/);
  await expect(page.getByRole("heading", { name: "Chat and share files with no internet" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Ways to connect" })).toBeVisible();
});
