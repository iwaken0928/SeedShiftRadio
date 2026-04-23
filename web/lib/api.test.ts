import { describe, expect, it, vi } from "vitest";
import {
  buildApiUrl,
  buildLettersPath,
  buildNextSpeechDirectivePath,
  listLetters,
  mergeAdminHeaders,
  requestJson,
  safeReadError,
  updateStation,
  updateStationProgramming,
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

  it("updateStationProgramming は admin token と JSON body を付けて PUT する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          stationId: "station-night",
          version: 3,
          enabled: true,
          defaultTemplateId: "tmpl-night",
          fallbackStrategy: "LEGACY_RATIO",
          planningHorizonMinutes: 20,
          preGeneration: {
            mode: "ASSISTED",
            maxPreparedMinutes: 12,
            maxPreparedBlocks: 2,
            preferCacheReuse: true,
          },
          replay: {
            intensity: "LIGHT",
            eligibleSegmentTypes: ["MUSIC_AI"],
            minimumAssetAgeHours: 6,
            cooldownHours: 72,
            maxReplaySharePercent: 20,
            excludeLetterSegments: true,
          },
          composition: {
            targetSegmentShares: { talk: 40, letter: 20, music: 35, jingle: 5 },
            maxConsecutiveTalkSegments: 2,
            musicBreakIntervalMinutes: 8,
            letterPriorityBoostThreshold: 4,
            allowSoftFallbackRetiming: true,
          },
          updatedAt: "2026-04-23T00:00:00Z",
          rules: [],
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    const body: Parameters<typeof updateStationProgramming>[1] = {
      version: 2,
      enabled: false,
      defaultTemplateId: null,
      fallbackStrategy: "LEGACY_RATIO",
      planningHorizonMinutes: 15,
      preGeneration: {
        mode: "REALTIME_ONLY",
        maxPreparedMinutes: 0,
        maxPreparedBlocks: 0,
        preferCacheReuse: false,
      },
      replay: {
        intensity: "OFF",
        eligibleSegmentTypes: ["MUSIC_LOCAL"],
        minimumAssetAgeHours: 0,
        cooldownHours: 0,
        maxReplaySharePercent: 0,
        excludeLetterSegments: true,
      },
      composition: {
        targetSegmentShares: { talk: 60, letter: 10, music: 25, jingle: 5 },
        maxConsecutiveTalkSegments: 3,
        musicBreakIntervalMinutes: 10,
        letterPriorityBoostThreshold: 2,
        allowSoftFallbackRetiming: false,
      },
      rules: [],
    };

    await updateStationProgramming("station/night", body);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/stations/station%2Fnight/programming", {
      cache: "no-store",
      method: "PUT",
      body: JSON.stringify(body),
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-Admin-Token": "admin-token",
      },
    });
  });

  it("updateStation は admin token と JSON body を付けて PUT する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "station-night",
          version: 5,
          name: "Midnight Echo",
          frequencyMHz: 82.5,
          genre: "ambient",
          languagePersonaId: "persona-night",
          defaultVoiceProfileId: "voice-night",
          isActive: true,
          programmingEnabled: true,
          defaultProgramTemplateId: "tmpl-night",
          updatedAt: "2026-04-23T00:00:00Z",
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    const body: Parameters<typeof updateStation>[1] = {
      version: 4,
      id: "station-night",
      name: "Midnight Echo",
      frequencyMHz: 82.5,
      genre: "ambient",
      languagePersonaId: "persona-night",
      defaultVoiceProfileId: "voice-night",
      isActive: true,
      programmingEnabled: true,
      defaultProgramTemplateId: "tmpl-night",
    };

    await updateStation("station/night", body);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/stations/station%2Fnight", {
      cache: "no-store",
      method: "PUT",
      body: JSON.stringify(body),
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-Admin-Token": "admin-token",
      },
    });
  });
});
