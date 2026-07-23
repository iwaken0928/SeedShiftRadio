import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  createAdminSession,
  createCsrfToken,
  readAdminSession,
  sessionCookieOptions,
  verifyCsrfToken,
  verifyLoginPassword,
} from "@/lib/server/admin-auth";

describe("admin auth", () => {
  beforeEach(() => {
    vi.stubEnv("SEEDSHIFT_WEB_SESSION_SECRET", "test-session-secret-at-least-32-bytes");
    vi.stubEnv("SEEDSHIFT_WEB_ADMIN_PASSWORD", "login-password");
  });

  it("署名済み session と session 固有 CSRF token を検証する", () => {
    const now = Date.UTC(2026, 6, 19);
    const encoded = createAdminSession(now);
    const session = readAdminSession(encoded, now);
    expect(session).not.toBeNull();
    expect(verifyCsrfToken(session!, createCsrfToken(session!))).toBe(true);
    expect(verifyCsrfToken(session!, "invalid")).toBe(false);
  });

  it("改ざんまたは期限切れ session を拒否する", () => {
    const now = Date.UTC(2026, 6, 19);
    const encoded = createAdminSession(now);
    expect(readAdminSession(`${encoded}x`, now)).toBeNull();
    expect(readAdminSession(encoded, now + 9 * 60 * 60 * 1000)).toBeNull();
  });

  it("login password を timing-safe 比較する", () => {
    expect(verifyLoginPassword("login-password")).toBe(true);
    expect(verifyLoginPassword("wrong-password")).toBe(false);
  });

  it("session Cookie の Secure 属性を公開 protocol に合わせる", () => {
    expect(sessionCookieOptions(true).secure).toBe(true);
    expect(sessionCookieOptions(false).secure).toBe(false);
  });

  it("production では 32 bytes 未満の session secret を拒否する", () => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("SEEDSHIFT_WEB_SESSION_SECRET", "too-short");
    expect(() => createAdminSession()).toThrow("at least 32 bytes");
  });
});
