import { test, expect, type Page } from "@playwright/test";

const map = (page: Page) => page.getByRole("region", { name: "Interactive network map" });
async function drag(page: Page, x: number, y: number, dx: number, dy: number) {
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx, y + dy, { steps: 12 });
  await page.mouse.up();
}

test("devices remain clickable after panning, and dragging a device never opens its details", async ({ page }) => {
  await page.goto("/");
  const canvas = map(page), box = (await canvas.boundingBox())!;
  const maya = page.getByRole("button", { name: "Inspect Maya", exact: true });
  const before = (await maya.boundingBox())!;
  await drag(page, box.x + 25, box.y + 160, 75, 35);
  const after = (await maya.boundingBox())!;
  expect(after.x - before.x).toBeCloseTo(75, -1);
  expect(after.y - before.y).toBeCloseTo(35, -1);
  await expect(page.getByRole("button", { name: "Reset inspector" })).toHaveCount(0);
  await maya.click();
  await expect(page.locator(".network-inspector .mesh-identity h2")).toHaveText("Maya");
  await expect(maya).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Reset inspector" }).click();
  const tile = (await maya.locator(".device-tile").boundingBox())!;
  await drag(page, tile.x + tile.width / 2, tile.y + tile.height / 2, 45, 15);
  await expect(page.getByRole("button", { name: "Reset inspector" })).toHaveCount(0);
  await maya.click();
  await expect(page.locator(".network-inspector .mesh-identity h2")).toHaveText("Maya");
});

test("wheel zoom stays anchored to the cursor, controls have limits and fit restores the map", async ({ page }) => {
  await page.goto("/");
  const canvas = map(page), box = (await canvas.boundingBox())!;
  const beforeScroll = await page.evaluate(() => window.scrollY);
  const you = page.getByRole("button", { name: "Inspect You", exact: true }).locator(".device-tile");
  const before = (await you.boundingBox())!;
  await page.mouse.move(box.x + box.width * 400 / 1050, box.y + box.height * 335 / 760);
  await page.mouse.wheel(0, -100);
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("135%");
  const after = (await you.boundingBox())!;
  // The node's center stays under the cursor while its tile grows.
  expect(after.x + after.width / 2).toBeCloseTo(before.x + before.width / 2, 0);
  expect(await page.evaluate(() => window.scrollY)).toBe(beforeScroll);
  await page.getByRole("button", { name: "Fit network", exact: true }).click();
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("100%");
  const restored = (await you.boundingBox())!;
  expect(restored.x).toBeCloseTo(before.x, 0);
  for (let i = 0; i < 6; i++) {
    const button = page.getByRole("button", { name: "Zoom in", exact: true });
    if (await button.isEnabled()) await button.click();
  }
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("250%");
  await expect(page.getByRole("button", { name: "Zoom in", exact: true })).toBeDisabled();
  for (let i = 0; i < 10; i++) {
    const button = page.getByRole("button", { name: "Zoom out", exact: true });
    if (await button.isEnabled()) await button.click();
  }
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("50%");
  await expect(page.getByRole("button", { name: "Zoom out", exact: true })).toBeDisabled();
});

test("keyboard navigation, Find me, search and blank-area deselection work together", async ({ page }) => {
  await page.goto("/");
  const canvas = map(page), box = (await canvas.boundingBox())!;
  await canvas.focus();
  await page.keyboard.press("+");
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("125%");
  await page.keyboard.press("ArrowRight");
  await page.getByRole("button", { name: "Find me", exact: true }).click();
  const you = (await page.getByRole("button", { name: "Inspect You", exact: true }).boundingBox())!;
  expect(you.x + you.width / 2).toBeCloseTo(box.x + box.width / 2, 0);
  await page.getByRole("textbox", { name: "Find a device" }).fill("Ravi");
  await page.getByRole("group", { name: "Found devices" }).getByRole("button").click();
  await expect(page.locator(".network-inspector .mesh-identity h2")).toHaveText("Ravi");
  const ravi = (await page.getByRole("button", { name: "Inspect Ravi", exact: true }).boundingBox())!;
  expect(ravi.x + ravi.width / 2).toBeCloseTo(box.x + box.width / 2, 0);
  await canvas.focus();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: "Reset inspector" })).toHaveCount(0);
  await page.keyboard.press("0");
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("100%");
  await page.getByRole("button", { name: "Inspect Ravi", exact: true }).click();
  await page.mouse.click(box.x + 15, box.y + 140);
  await expect(page.getByRole("button", { name: "Reset inspector" })).toHaveCount(0);
  await page.getByRole("textbox", { name: "Find a device" }).fill("missing-device");
  await expect(page.getByText("No devices found. Try another name.")).toBeVisible();
});

test("touch drag and pinch navigate without selecting a device, and resize recovers the view", async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true });
  const page = await context.newPage();
  await page.goto("/");
  const canvas = map(page);
  await canvas.scrollIntoViewIfNeeded();
  const box = (await canvas.boundingBox())!;
  const client = await context.newCDPSession(page);
  const touch = (type: "touchStart" | "touchMove" | "touchEnd" | "touchCancel", points: { x: number; y: number; id: number }[]) => client.send("Input.dispatchTouchEvent", { type, touchPoints: points });
  const x = box.x + box.width / 2, y = box.y + 170;
  await touch("touchStart", [{ x, y, id: 1 }]);
  await touch("touchMove", [{ x: x + 30, y: y + 30, id: 1 }]);
  await touch("touchEnd", []);
  await expect(page.getByRole("button", { name: "Reset inspector" })).toHaveCount(0);
  await touch("touchStart", [{ x: x - 35, y, id: 1 }, { x: x + 35, y, id: 2 }]);
  await touch("touchMove", [{ x: x - 70, y, id: 1 }, { x: x + 70, y, id: 2 }]);
  await touch("touchEnd", []);
  await expect(page.getByLabel("Map zoom", { exact: true })).not.toHaveText("100%");
  await expect(page.getByRole("button", { name: "Reset inspector" })).toHaveCount(0);
  await page.getByRole("button", { name: "Fit network", exact: true }).tap();
  await page.getByRole("button", { name: "Inspect You", exact: true }).tap();
  await expect(page.locator(".network-inspector .mesh-identity h2")).toHaveText("You");
  await page.getByRole("button", { name: "Zoom in", exact: true }).tap();
  await page.setViewportSize({ width: 700, height: 900 });
  await expect(page.getByLabel("Map zoom", { exact: true })).toHaveText("100%");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(700);
  await context.close();
});
