import { expect, test } from "@playwright/test";
import { appUrl, loginAdmin } from "./fixtures";

test("admin auth: application origin login establishes a server-side session", async ({ page }) => {
  const loginResponse = await loginAdmin(page);
  expect(loginResponse.status()).toBe(200);

  const sessionResponse = await page.request.get(appUrl("/api/auth/session"));
  expect(sessionResponse.status()).toBe(200);
  expect(sessionResponse.headers()["cache-control"]).toContain("no-store");
  await expect(sessionResponse.json()).resolves.toMatchObject({
    authenticated: true,
    csrfToken: expect.any(String),
  });
});

test("admin auth: cross-origin login is rejected before credential validation", async ({ page }) => {
  const response = await page.request.post(appUrl("/api/auth/login"), {
    data: { password: "not-the-configured-password" },
    headers: { Origin: "https://cross-origin.invalid" },
  });

  expect(response.status()).toBe(403);
  await expect(response.json()).resolves.toMatchObject({
    message: "同一 origin から操作してください。",
  });
});
