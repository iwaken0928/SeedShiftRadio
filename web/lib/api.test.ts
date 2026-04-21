import { describe, expect, it, vi } from "vitest";
import {
  buildApiUrl,
  buildLettersPath,
  buildNextSpeechDirectivePath,
  listLetters,
  mergeAdminHeaders,
  requestJson,
  safeReadError,
} from "@/lib/api";

describe("api helpers", () => {
  it("API URL とクエリ文字列を組み立てる", () => {
    expect(buildApiUrl("/api/health", "http://api.example")).toBe("http://api.example/api/health");
    expect(buildNextSpeechDirectivePath("client/1")).toBe("/api/radio/next-speech-directive?clientId=client%2F1");
    expect(buildLettersPath("station/1", "PENDING")).toBe("/api/letters?stationId=station%2F1&status=PENDING");
    expect(buildLettersPath()).toBe("/api/letters");
  });

  it("admin token がある時だけ header を追加する", () => {
    expect(mergeAdminHeaders(null, { Accept: "application/json" })).toEqual({
      Accept: "application/json",
    });
    expect(mergeAdminHeaders("token-123", { Accept: "application/json" })).toEqual({
      Accept: "application/json",
      "X-Admin-Token": "token-123",
    });
  });

  it("requestJson は base URL と Accept header を付けて JSON を返す", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "http://api.example");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ status: "UP" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(requestJson<{ status: string }>("/api/health", { headers: { "X-Test": "1" } })).resolves.toEqual({
      status: "UP",
    });

    expect(fetchMock).toHaveBeenCalledWith("http://api.example/api/health", {
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "X-Test": "1",
      },
    });
  });

  it("204 response は undefined を返す", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));

    await expect(requestJson<void>("/api/radio/play", { method: "POST" })).resolves.toBeUndefined();
  });

  it("エラーレスポンスの message を優先して例外化する", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ message: "bad request" }), {
        status: 400,
        statusText: "Bad Request",
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(requestJson("/api/radio/tune")).rejects.toThrow("bad request");
  });

  it("safeReadError は message / error / statusText を順に使う", async () => {
    await expect(
      safeReadError(
        new Response(JSON.stringify({ message: "message-body" }), {
          status: 400,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    ).resolves.toBe("message-body");

    await expect(
      safeReadError(
        new Response(JSON.stringify({ error: "error-body" }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    ).resolves.toBe("error-body");

    await expect(new Response("plain text", { status: 503, statusText: "Service Unavailable" }).json()).rejects.toBeDefined();
    await expect(safeReadError(new Response("plain text", { status: 503, statusText: "Service Unavailable" }))).resolves.toBe(
      "Service Unavailable",
    );
  });

  it("listLetters は admin token と query を付けて fetch する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(listLetters("station-a", "ADOPTED")).resolves.toEqual([]);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/letters?stationId=station-a&status=ADOPTED", {
      cache: "no-store",
      headers: {
        Accept: "application/json",
        "X-Admin-Token": "admin-token",
      },
    });
  });
});
