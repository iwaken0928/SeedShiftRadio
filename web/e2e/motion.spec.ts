import { expect, test } from "@playwright/test";
import {
  apiUrl,
  appUrl,
  buildQueueSnapshot,
  buildRadioStatus,
  fulfillEmpty,
  fulfillJson,
  mockUnavailableStream,
} from "./fixtures";

test("motion: reduced motion and mobile interaction targets stay usable", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 390, height: 844 });
  await mockUnavailableStream(page);

  await page.route(apiUrl("/api/stations"), async (route) => fulfillJson(route, []));
  await page.route(apiUrl("/api/radio/status"), async (route) => fulfillJson(route, buildRadioStatus()));
  await page.route(apiUrl("/api/radio/queue"), async (route) => fulfillJson(route, buildQueueSnapshot({ sessionId: null, stationId: null, items: [] })));
  await page.route(apiUrl("/api/radio/program"), async (route) => fulfillJson(route, { message: "not tuned" }, 404));
  await page.route(apiUrl("/api/clients/capabilities"), async (route) => fulfillEmpty(route));

  await page.goto(appUrl("/"));

  const hero = page.locator("main section").filter({
    has: page.getByRole("heading", { name: "番組再生", exact: true }),
  });
  const queue = page.locator("main section").filter({
    has: page.getByRole("heading", { name: "再生待ち一覧", exact: true }),
  });

  await expect(hero).toHaveClass(/motion-enter/);
  await expect(queue).not.toHaveClass(/motion-enter/);
  await expect(page.getByRole("link", { name: "ラジオ", exact: true })).toHaveAttribute("aria-current", "page");

  const reducedAnimationName = await hero.evaluate((element) => getComputedStyle(element).animationName);
  expect(reducedAnimationName).toBe("surface-fade-in");

  const navBox = await page.getByRole("link", { name: "ラジオ", exact: true }).boundingBox();
  const headerBox = await page.locator("header").boundingBox();
  expect(navBox?.height).toBeGreaterThanOrEqual(44);
  expect(headerBox?.height).toBeLessThanOrEqual(200);

  const hasHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(hasHorizontalOverflow).toBe(false);
});
