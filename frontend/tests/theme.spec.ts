import { test, expect } from "@playwright/test";

test("theme switch persists across navigation and reloads", async ({ page }, testInfo) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/");
  const toggle = page.getByRole("switch", { name: "Light mode", exact: true });
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await toggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await page.getByRole("navigation").getByRole("link", { name: "Settings", exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/$/);
  await expect(page.getByRole("heading", { name: "Settings", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Settings", exact: true })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  expect(await page.evaluate(() => localStorage.getItem("relay-theme"))).toBe("light");
  await page.getByRole("switch", { name: "Light mode", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.screenshot({ path: testInfo.outputPath("settings-dark.png"), fullPage: true });
});

for (const width of [1440, 390]) {
  test(`light mode supports every page at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 960 });
    await page.addInitScript(() => localStorage.setItem("relay-theme", "light"));
    const errors: string[] = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("response", response => { if (response.url().includes("/icons/") && response.status() >= 400) errors.push(`Missing icon: ${response.url()}`); });
    for (const route of ["/", "/messages/", "/files/", "/device/", "/settings/", "/how-it-works/"]) {
      await page.goto(route);
      await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
      await expect(page.getByRole("switch", { name: "Light mode", exact: true })).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      if (route === "/" && width < 851) {
        const map = await page.locator(".map-area").boundingBox();
        const sidebar = await page.locator(".network-inspector").boundingBox();
        expect(map!.y + map!.height).toBeLessThanOrEqual(sidebar!.y);
      }
      const lowContrast = await page.locator("button:not(:disabled), a.button, input:not(:disabled), select:not(:disabled)").evaluateAll(elements => {
        const canvas = document.createElement("canvas"); canvas.width = 1; canvas.height = 1;
        const ctx = canvas.getContext("2d")!;
        const rgba = (color: string) => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = color; ctx.fillRect(0, 0, 1, 1); return Array.from(ctx.getImageData(0, 0, 1, 1).data); };
        const luminance = (rgb: number[]) => rgb.slice(0, 3).map(value => { value /= 255; return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4; }).reduce((sum, value, i) => sum + value * [.2126, .7152, .0722][i], 0);
        return elements.flatMap(element => {
          const rect = element.getBoundingClientRect();
          if (!rect.width || !rect.height || !element.textContent?.trim()) return [];
          const style = getComputedStyle(element);
          if (style.visibility === "hidden" || Number(style.opacity) < .9) return [];
          const ancestors: Element[] = []; for (let item: Element | null = element; item; item = item.parentElement) ancestors.unshift(item);
          let bg = [255, 255, 255];
          for (const item of ancestors) { const color = rgba(getComputedStyle(item).backgroundColor); const alpha = color[3] / 255; bg = bg.map((v, i) => color[i] * alpha + v * (1 - alpha)); }
          const fg = rgba(style.color); const a = luminance(fg), b = luminance(bg);
          const ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
          return ratio < 4.5 ? [{ label: element.textContent.trim().slice(0, 70), ratio: Math.round(ratio * 100) / 100 }] : [];
        });
      });
      expect(lowContrast, route).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath(`${route.replaceAll("/", "") || "map"}-light.png`), fullPage: true });
    }
    expect(errors).toEqual([]);
  });
}
