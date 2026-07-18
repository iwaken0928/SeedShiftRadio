import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "@/app/api-proxy/[...path]/route";
import { ADMIN_SESSION_COOKIE, createAdminSession, createCsrfToken, readAdminSession } from "@/lib/server/admin-auth";

describe("api proxy auth boundary", () => {
  beforeEach(() => {
    vi.stubEnv("SEEDSHIFT_WEB_SESSION_SECRET", "test-session-secret-at-least-32-bytes");
    vi.stubEnv("SEEDSHIFT_ADMIN_TOKEN", "server-only-admin-token");
  });

  it("未認証の ADMIN request を upstream へ送らず 401 にする", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const response = await GET(request("GET", "/api-proxy/api/settings"), context(["api", "settings"]));
    expect(response.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("unsafe ADMIN request の CSRF 不備を 403 にする", async () => {
    const sessionCookie = createAdminSession();
    const response = await POST(
      request("POST", "/api-proxy/api/settings/test-connections", { cookie: `${ADMIN_SESSION_COOKIE}=${sessionCookie}` }),
      context(["api", "settings", "test-connections"]),
    );
    expect(response.status).toBe(403);
  });

  it("認証済み ADMIN request だけ server token を注入し browser credential を除去する", async () => {
    const sessionCookie = createAdminSession();
    const session = readAdminSession(sessionCookie)!;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ ok: true }));
    const response = await POST(
      request("POST", "/api-proxy/api/settings/test-connections", {
        cookie: `${ADMIN_SESSION_COOKIE}=${sessionCookie}`,
        "x-admin-token": "browser-supplied-token",
        "x-csrf-token": createCsrfToken(session),
      }),
      context(["api", "settings", "test-connections"]),
    );
    expect(response.status).toBe(200);
    const headers = fetchMock.mock.calls[0]![1]!.headers as Headers;
    expect(headers.get("x-admin-token")).toBe("server-only-admin-token");
    expect(headers.has("cookie")).toBe(false);
    expect(headers.has("x-csrf-token")).toBe(false);
  });

  it("PUBLIC request には server token を注入しない", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ status: "UP" }));
    const response = await GET(
      request("GET", "/api-proxy/api/health", { "x-admin-token": "browser-supplied-token" }),
      context(["api", "health"]),
    );
    expect(response.status).toBe(200);
    const headers = fetchMock.mock.calls[0]![1]!.headers as Headers;
    expect(headers.has("x-admin-token")).toBe(false);
  });
});

function request(method: string, path: string, headers: Record<string, string> = {}) {
  return new NextRequest(`http://localhost${path}`, { method, headers });
}

function context(path: string[]) {
  return { params: Promise.resolve({ path }) };
}
