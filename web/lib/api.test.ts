import { describe, expect, it, vi } from "vitest";
import {
  ApiRequestError,
  buildApiUrl,
  buildLettersPath,
  buildNextSpeechDirectivePath,
  createProgramTemplate,
  createStation,
  listLetters,
  mergeAdminHeaders,
  previewProgramming,
  requestJson,
  safeReadError,
  updateProgramTemplate,
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

  it("requestJson は fieldErrors を持つ ApiRequestError を返す", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          code: "VALIDATION_ERROR",
          message: "入力値を確認してください。",
          details: {
            fieldErrors: {
              "slots[0].slotId": "slotId が重複しています。",
              fallbackTemplateId: "fallbackTemplateId が循環しています。",
            },
          },
        }),
        {
          status: 400,
          statusText: "Bad Request",
          headers: { "Content-Type": "application/json" },
        },
      ),
    );

    await expect(requestJson("/api/program-templates")).rejects.toMatchObject({
      name: "ApiRequestError",
      message: "入力値を確認してください。",
      status: 400,
      code: "VALIDATION_ERROR",
      fieldErrors: {
        "slots[0].slotId": "slotId が重複しています。",
        fallbackTemplateId: "fallbackTemplateId が循環しています。",
      },
    } satisfies Partial<ApiRequestError>);
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

  it("previewProgramming は未保存 draft を含む JSON body を付けて POST する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          stationId: "station-night",
          selectedTemplateId: "tmpl-preview-draft",
          fallbackApplied: false,
          program: {
            title: "Preview Draft Template",
            plannedDurationMs: 45000,
          },
          slots: [
            {
              slotId: "opening-draft",
              role: "OPENING",
              constraintMode: "HARD",
              targetDurationMs: 45000,
            },
          ],
          validationWarnings: [],
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    const body: Parameters<typeof previewProgramming>[1] = {
      at: "2026-04-26T21:00",
      pendingLetterCount: 2,
      providerStates: {
        musicGen: "UP",
        tts: "UP",
        llm: "UP",
      },
      policyDraft: {
        version: 3,
        enabled: true,
        defaultTemplateId: "tmpl-preview-draft",
        fallbackStrategy: "LEGACY_RATIO",
        planningHorizonMinutes: 30,
        preGeneration: {
          mode: "ASSISTED",
          maxPreparedMinutes: 12,
          maxPreparedBlocks: 2,
          preferCacheReuse: true,
        },
        replay: {
          intensity: "LIGHT",
          eligibleSegmentTypes: ["MUSIC_AI", "MUSIC_LOCAL"],
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
        rules: [
          {
            priority: 100,
            days: ["MON", "TUE", "WED"],
            startTime: "20:00",
            endTime: "23:59",
            minimumPendingLetters: 1,
            requiredProviderStates: [],
            templateId: "tmpl-preview-draft",
          },
        ],
      },
      templateDraft: {
        id: "tmpl-preview-draft",
        scope: "STATION",
        stationId: "station-night",
        name: "Preview Draft Template",
        version: 0,
        targetDurationMinutes: 18,
        planningHorizonMinutes: 12,
        isActive: true,
        editorialPolicy: {
          tone: "bright",
        },
        fallbackTemplateId: "tmpl-global-fallback",
        slots: [
          {
            slotId: "opening-draft",
            role: "OPENING",
            constraintMode: "HARD",
            candidateSegmentTypes: ["JINGLE", "TALK"],
            fallbackSegmentTypes: ["TALK"],
            targetDurationMs: 45000,
            slotPolicy: {
              preferFreshGeneration: true,
            },
          },
        ],
      },
    };

    await previewProgramming("station/night", body);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/stations/station%2Fnight/programming/preview", {
      cache: "no-store",
      method: "POST",
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

  it("createStation は admin token と JSON body を付けて POST する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "station-dawn",
          version: 1,
          name: "Dawn Signals",
          frequencyMHz: 77.7,
          genre: "talk",
          languagePersonaId: "persona-dawn",
          defaultVoiceProfileId: "voice-dawn",
          isActive: true,
          programmingEnabled: false,
          defaultProgramTemplateId: null,
          updatedAt: "2026-04-23T00:00:00Z",
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    const body: Parameters<typeof createStation>[0] = {
      version: 0,
      id: "station-dawn",
      name: "Dawn Signals",
      frequencyMHz: 77.7,
      genre: "talk",
      languagePersonaId: "persona-dawn",
      defaultVoiceProfileId: "voice-dawn",
      isActive: true,
      programmingEnabled: false,
      defaultProgramTemplateId: null,
    };

    await createStation(body);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/stations", {
      cache: "no-store",
      method: "POST",
      body: JSON.stringify(body),
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-Admin-Token": "admin-token",
      },
    });
  });

  it("updateProgramTemplate は admin token と JSON body を付けて PUT する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "tmpl-night-deep",
          scope: "STATION",
          stationId: "station-night",
          name: "深夜ロングトーク",
          version: 4,
          targetDurationMinutes: 25,
          planningHorizonMinutes: 18,
          isActive: true,
          editorialPolicy: {
            tone: "calm",
            topics: ["night", "coding"],
          },
          fallbackTemplateId: "tmpl-night-regular",
          slots: [
            {
              slotId: "open",
              role: "OPENING",
              constraintMode: "HARD",
              candidateSegmentTypes: ["JINGLE", "TALK"],
              fallbackSegmentTypes: ["TALK"],
              targetDurationMs: 30000,
              slotPolicy: { allowArchiveReplay: false },
            },
          ],
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    const body: Parameters<typeof updateProgramTemplate>[1] = {
      id: "tmpl-night-deep",
      scope: "STATION",
      stationId: "station-night",
      name: "深夜ロングトーク",
      version: 3,
      targetDurationMinutes: 25,
      planningHorizonMinutes: 18,
      isActive: true,
      editorialPolicy: {
        tone: "calm",
        topics: ["night", "coding"],
      },
      fallbackTemplateId: "tmpl-night-regular",
      slots: [
        {
          slotId: "open",
          role: "OPENING",
          constraintMode: "HARD",
          candidateSegmentTypes: ["JINGLE", "TALK"],
          fallbackSegmentTypes: ["TALK"],
          targetDurationMs: 30000,
          slotPolicy: { allowArchiveReplay: false },
        },
        {
          slotId: "letter-main",
          role: "LETTER",
          constraintMode: "SOFT",
          candidateSegmentTypes: ["LETTER", "TALK"],
          fallbackSegmentTypes: ["TALK"],
          targetDurationMs: 120000,
          slotPolicy: { preferFreshGeneration: true },
        },
      ],
    };

    await updateProgramTemplate("tmpl/night/deep", body);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/program-templates/tmpl%2Fnight%2Fdeep", {
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

  it("createProgramTemplate は admin token と JSON body を付けて POST する", async () => {
    vi.stubEnv("NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN", "admin-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "tmpl-global-morning",
          scope: "GLOBAL",
          stationId: null,
          name: "朝のテンポ番組",
          version: 1,
          targetDurationMinutes: 15,
          planningHorizonMinutes: 10,
          isActive: true,
          editorialPolicy: {
            energy: "bright",
            topicTags: ["morning", "news-lite"],
          },
          fallbackTemplateId: null,
          slots: [
            {
              slotId: "open",
              role: "OPENING",
              constraintMode: "HARD",
              candidateSegmentTypes: ["JINGLE", "TALK"],
              fallbackSegmentTypes: ["TALK"],
              targetDurationMs: 15000,
              slotPolicy: { preferFreshGeneration: true },
            },
          ],
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    const body: Parameters<typeof createProgramTemplate>[0] = {
      id: "tmpl-global-morning",
      scope: "GLOBAL",
      stationId: null,
      name: "朝のテンポ番組",
      version: 0,
      targetDurationMinutes: 15,
      planningHorizonMinutes: 10,
      isActive: true,
      editorialPolicy: {
        energy: "bright",
        topicTags: ["morning", "news-lite"],
      },
      fallbackTemplateId: null,
      slots: [
        {
          slotId: "open",
          role: "OPENING",
          constraintMode: "HARD",
          candidateSegmentTypes: ["JINGLE", "TALK"],
          fallbackSegmentTypes: ["TALK"],
          targetDurationMs: 15000,
          slotPolicy: { preferFreshGeneration: true },
        },
        {
          slotId: "music-break",
          role: "MUSIC_BREAK",
          constraintMode: "SOFT",
          candidateSegmentTypes: ["MUSIC_LOCAL", "MUSIC_AI"],
          fallbackSegmentTypes: ["JINGLE"],
          targetDurationMs: 180000,
          slotPolicy: { allowArchiveReplay: true },
        },
      ],
    };

    await createProgramTemplate(body);

    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:8080/api/program-templates", {
      cache: "no-store",
      method: "POST",
      body: JSON.stringify(body),
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-Admin-Token": "admin-token",
      },
    });
  });
});
