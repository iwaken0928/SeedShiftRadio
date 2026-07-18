import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.PLAYWRIGHT_BASE_URL?.trim() || "http://127.0.0.1:3001";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  reporter: "list",
  use: {
    baseURL,
    headless: true,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
      },
    },
  ],
  webServer: {
    command: "npm run start:e2e",
    env: {
      SEEDSHIFT_WEB_ADMIN_PASSWORD: "playwright-login-password",
      SEEDSHIFT_WEB_SESSION_SECRET: "playwright-session-secret-at-least-32-bytes",
      SEEDSHIFT_ADMIN_TOKEN: "playwright-server-admin-token",
    },
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
