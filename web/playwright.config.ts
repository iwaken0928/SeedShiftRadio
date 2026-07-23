import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.PLAYWRIGHT_BASE_URL?.trim() || "http://127.0.0.1:3001";
const serverURL = new URL(baseURL);
const isCI = /^(1|true)$/i.test(process.env.CI?.trim() || "");
const webServerEnv = {
  HOSTNAME: serverURL.hostname,
  PORT: serverURL.port || "3001",
  SEEDSHIFT_WEB_ADMIN_PASSWORD:
    process.env.E2E_WEB_ADMIN_PASSWORD?.trim() || "playwright-login-password",
  SEEDSHIFT_WEB_SESSION_SECRET:
    process.env.E2E_WEB_SESSION_SECRET?.trim() || "playwright-session-secret-at-least-32-bytes",
  SEEDSHIFT_ADMIN_TOKEN:
    process.env.E2E_ADMIN_TOKEN?.trim() || "playwright-server-admin-token",
  ...(isCI ? { CI: "true" } : {}),
};

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
    env: webServerEnv,
    url: baseURL,
    reuseExistingServer: !isCI,
    timeout: 120_000,
  },
});
