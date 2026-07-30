import { describe, expect, it } from "vitest";
import { buildSettingsExportFilename, buildSettingsExportPayload, parseSettingsImportPayload } from "@/lib/settings-import-export";
import type { SettingsResponse, SettingsUpdateRequest } from "@/lib/types";

describe("settings-import-export", () => {
  it("export payload は response metadata を持たない PUT 互換 JSON にする", () => {
    const current = createSettingsResponse();
    const draft = createUpdateRequest(current);

    const exported = buildSettingsExportPayload(draft);

    expect(exported).toEqual(draft);
    expect(exported).not.toHaveProperty("updatedAt");
    expect(exported).not.toHaveProperty("configPath");
    expect(JSON.stringify(exported)).not.toContain("admin-token-secret");
  });

  it("export filename は schemaVersion と version を含める", () => {
    expect(buildSettingsExportFilename(createUpdateRequest(createSettingsResponse()))).toBe("seedshift-radio-settings-v1-v7.json");
  });

  it("import payload は現在の version に合わせて draft 化する", () => {
    const current = createSettingsResponse({ version: 9 });
    const imported = createUpdateRequest(createSettingsResponse({ version: 2 }));
    imported.server.port = 9090;

    const result = parseSettingsImportPayload({ ...imported, updatedAt: "ignored", configPath: "/ignored/config.json" }, current);

    expect(result.versionAdjusted).toBe(true);
    expect(result.draft.version).toBe(9);
    expect(result.draft.schemaVersion).toBe("v1");
    expect(result.draft.server.port).toBe(9090);
    expect(result.draft).not.toHaveProperty("updatedAt");
    expect(result.draft).not.toHaveProperty("configPath");
  });

  it("schemaVersion が一致しない import は弾く", () => {
    const current = createSettingsResponse({ schemaVersion: "v2" });
    const imported = createUpdateRequest(createSettingsResponse({ schemaVersion: "v1" }));

    expect(() => parseSettingsImportPayload(imported, current)).toThrow("schemaVersion が一致しません");
  });

  it("secret 参照に raw 値らしい文字列を含む import は弾く", () => {
    const current = createSettingsResponse();
    const imported = createUpdateRequest(current);
    imported.providers.musicGen.providers.worker.apiKeyRef = "plain-secret";

    expect(() => parseSettingsImportPayload(imported, current)).toThrow("apiKeyRef は env: または file:");
  });

  it("secret 参照に raw 値らしい文字列を含む export は弾く", () => {
    const current = createSettingsResponse();
    const draft = createUpdateRequest(current);
    draft.security.adminTokenRef = "plain-admin-token";

    expect(() => buildSettingsExportPayload(draft)).toThrow("security.adminTokenRef は env: または file:");

    draft.security.adminTokenRef = "env:SEEDSHIFT_ADMIN_TOKEN";
    draft.providers.musicGen.providers.worker.apiKeyRef = "plain-api-key";

    expect(() => buildSettingsExportPayload(draft)).toThrow("providers.musicGen.providers.worker.apiKeyRef は env: または file:");
  });

  it("未登録 fallback provider を含む import は弾く", () => {
    const current = createSettingsResponse();
    const imported = createUpdateRequest(current);
    imported.providers.tts.fallbackProviders = ["missing"];

    expect(() => parseSettingsImportPayload(imported, current)).toThrow("未登録 provider");
  });
});

function createSettingsResponse(overrides: Partial<SettingsResponse> = {}): SettingsResponse {
  return {
    version: 7,
    schemaVersion: "v1",
    updatedAt: "2026-04-23T00:00:00Z",
    configPath: "/srv/seedshift/config.json",
    server: {
      bindHost: "127.0.0.1",
      port: 8080,
    },
    paths: {
      dataRoot: "/srv/seedshift",
      musicLibrary: "/srv/seedshift/music",
    },
    playout: {
      targetReadyCount: 3,
      minimumReadyCount: 1,
      minReadyDurationMs: 30000,
      maxPreparedDurationMs: 300000,
      maxPreparedBlocks: 6,
      scriptAheadCount: 2,
      ttsAheadCount: 2,
      musicAheadCount: 1,
      idlePrefetchEnabled: true,
    },
    cache: {
      scriptMaxBytes: 1048576,
      ttsMaxBytes: 2097152,
      musicMaxBytes: 4194304,
      scriptRetentionDays: 7,
      ttsRetentionDays: 7,
      musicRetentionDays: 14,
      scriptReuseScope: "SESSION",
      ttsReuseScope: "STATION",
      musicReuseScope: "GLOBAL",
      cleanupBatchSize: 100,
    },
    programming: {
      defaultPlanningHorizonMinutes: 120,
      legacyRatioFallback: false,
      seedImportRef: "file:/srv/seedshift/seeds.json",
    },
    providers: {
      llm: {
        defaultProvider: "ollama",
        fallbackProviders: [],
        providers: {
          ollama: {
            baseUrl: "http://127.0.0.1:11434",
            healthPath: "/api/tags",
            timeoutMs: 5000,
            capabilities: ["SCRIPT"],
          },
        },
      },
      tts: {
        defaultProvider: "voicevox",
        fallbackProviders: [],
        providers: {
          voicevox: {
            baseUrl: "http://127.0.0.1:50021",
            healthPath: "/version",
            timeoutMs: 5000,
            capabilities: ["TTS"],
          },
        },
      },
      musicGen: {
        defaultProvider: "worker",
        fallbackProviders: [],
        providers: {
          worker: {
            baseUrl: "http://127.0.0.1:8091",
            healthPath: "/health",
            timeoutMs: 10000,
            capabilities: ["MUSIC_GEN"],
            adapter: "MUSICGEN_WORKER",
            apiKeyRef: "env:MUSICGEN_API_KEY",
            defaultModelProfileId: "default",
            modelProfiles: {
              default: {
                model: "facebook/musicgen-small",
                lmModel: "gpt2",
                thinking: false,
                lyricsLanguage: "ja",
                lyricsTransliterationMode: "kana",
                outputFormat: "wav",
                maxDurationSeconds: 30,
              },
            },
          },
        },
      },
    },
    security: {
      adminTokenRef: "env:SEEDSHIFT_ADMIN_TOKEN",
    },
    features: {
      streaming: {
        placeholderEnabled: true,
      },
      jobExecution: {
        singleGpuMode: true,
        resourceGroup: "gpu-0",
        requireAceStepCpuOffload: true,
        manual: {
          waitStrategy: "WAIT",
          resourceWaitTimeoutSeconds: 900,
          providerIdleTimeoutSeconds: 900,
          modelLoadTimeoutSeconds: 900,
          jobTimeoutSeconds: 1800,
          pollIntervalMillis: 1000,
          unloadOllamaBeforeMusic: true,
          waitForAceStepIdleBeforeLlm: true,
        },
        automatic: {
          waitStrategy: "WAIT",
          resourceWaitTimeoutSeconds: 1800,
          providerIdleTimeoutSeconds: 900,
          modelLoadTimeoutSeconds: 900,
          jobTimeoutSeconds: 1800,
          pollIntervalMillis: 1000,
          unloadOllamaBeforeMusic: true,
          waitForAceStepIdleBeforeLlm: true,
        },
      },
    },
    ...overrides,
  };
}

function createUpdateRequest(settings: SettingsResponse): SettingsUpdateRequest {
  return {
    version: settings.version,
    schemaVersion: settings.schemaVersion,
    server: { ...settings.server },
    paths: { ...settings.paths },
    playout: { ...settings.playout },
    cache: { ...settings.cache },
    programming: { ...settings.programming },
    providers: {
      llm: {
        defaultProvider: settings.providers.llm.defaultProvider,
        fallbackProviders: [...settings.providers.llm.fallbackProviders],
        providers: { ...settings.providers.llm.providers },
      },
      tts: {
        defaultProvider: settings.providers.tts.defaultProvider,
        fallbackProviders: [...settings.providers.tts.fallbackProviders],
        providers: { ...settings.providers.tts.providers },
      },
      musicGen: {
        defaultProvider: settings.providers.musicGen.defaultProvider,
        fallbackProviders: [...settings.providers.musicGen.fallbackProviders],
        providers: { ...settings.providers.musicGen.providers },
      },
    },
    security: { ...settings.security },
    features: {
      streaming: { ...settings.features.streaming },
      jobExecution: {
        ...settings.features.jobExecution,
        manual: { ...settings.features.jobExecution.manual },
        automatic: { ...settings.features.jobExecution.automatic },
      },
    },
  };
}
