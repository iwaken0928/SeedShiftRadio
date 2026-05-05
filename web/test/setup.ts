import { afterEach, beforeEach, vi } from "vitest";

const envKeys = [
  "NEXT_PUBLIC_API_BASE_URL",
  "NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN",
  "NEXT_PUBLIC_ADMIN_TOKEN",
] as const;

let envSnapshot: Record<(typeof envKeys)[number], string | undefined>;

beforeEach(() => {
  envSnapshot = Object.fromEntries(envKeys.map((key) => [key, process.env[key]])) as Record<
    (typeof envKeys)[number],
    string | undefined
  >;
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  vi.resetModules();

  for (const key of envKeys) {
    const value = envSnapshot[key];
    if (value == null) {
      delete process.env[key];
      continue;
    }
    process.env[key] = value;
  }
});
