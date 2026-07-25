import type { Locator, Page, Route } from "@playwright/test";

export const APP_BASE_URL = process.env.PLAYWRIGHT_BASE_URL?.trim() || "http://127.0.0.1:3001";
export const API_BASE_URL = `${APP_BASE_URL}/api-proxy`;
export const UI_STORE_STORAGE_KEY = "seedshift-radio-web-ui";
const E2E_ADMIN_PASSWORD = process.env.E2E_WEB_ADMIN_PASSWORD?.trim() || "playwright-login-password";

export function appUrl(pathname = "/") {
  return new URL(pathname, APP_BASE_URL).toString();
}

export function apiUrl(pathname: string) {
  return `${API_BASE_URL}${pathname}`;
}

export async function loginAdmin(page: Page) {
  const response = await page.request.post(appUrl("/api/auth/login"), {
    data: { password: E2E_ADMIN_PASSWORD },
    headers: { Origin: new URL(APP_BASE_URL).origin },
  });
  if (!response.ok()) {
    const stage = response.status() === 403 ? "same-origin check" : "credential or session setup";
    throw new Error(`admin login failed during ${stage}: status=${response.status()}, appOrigin=${new URL(APP_BASE_URL).origin}`);
  }
  return response;
}

export function apiRegExp(pathPattern: string) {
  return new RegExp(`^${escapeRegExp(API_BASE_URL)}${pathPattern}$`);
}

export async function clearPersistedUiState(page: Page) {
  await page.addInitScript(({ storageKey }) => {
    window.localStorage.removeItem(storageKey);
  }, { storageKey: UI_STORE_STORAGE_KEY });
}

export async function stubAudioPlayback(page: Page) {
  await page.addInitScript(() => {
    Object.defineProperty(HTMLMediaElement.prototype, "play", {
      configurable: true,
      value() {
        return Promise.resolve();
      },
    });

    Object.defineProperty(HTMLMediaElement.prototype, "pause", {
      configurable: true,
      value() {},
    });

    Object.defineProperty(HTMLMediaElement.prototype, "load", {
      configurable: true,
      value() {},
    });
  });
}

export async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    body: JSON.stringify(body),
    contentType: "application/json; charset=utf-8",
  });
}

export async function fulfillEmpty(route: Route, status = 204) {
  await route.fulfill({
    status,
    body: "",
  });
}

export async function fulfillSse(route: Route, body: string, status = 200) {
  await route.fulfill({
    status,
    body,
    headers: {
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
      "Content-Type": "text/event-stream; charset=utf-8",
    },
  });
}

export async function mockUnavailableStream(page: Page) {
  await page.route(apiUrl("/api/stream/events"), async (route) => {
    await fulfillJson(route, { message: "stream unavailable in this test" }, 503);
  });
}

export function sseEvent({
  id,
  event,
  data,
}: {
  id?: string;
  event?: string;
  data?: unknown;
}) {
  const lines: string[] = [];

  if (id) {
    lines.push(`id: ${id}`);
  }
  if (event) {
    lines.push(`event: ${event}`);
  }
  if (data !== undefined) {
    const payload = typeof data === "string" ? data : JSON.stringify(data);
    for (const line of payload.split("\n")) {
      lines.push(`data: ${line}`);
    }
  }

  return `${lines.join("\n")}\n\n`;
}

export function panelByHeading(page: Page, heading: string) {
  return page.locator("section").filter({
    has: page.getByRole("heading", { name: heading, exact: true }),
  }).first();
}

export function sectionByText(page: Page, text: string) {
  return page.locator("section").filter({ hasText: text }).last();
}

export function inputFollowingLabel(page: Page, labelText: string) {
  return page.locator(`xpath=//label[normalize-space()="${labelText}"]/following::*[self::input][1]`);
}

export function textareaFollowingLabel(page: Page, labelText: string) {
  return page.locator(`xpath=//label[normalize-space()="${labelText}"]/following::*[self::textarea][1]`);
}

export async function fillInput(locator: Locator, value: string) {
  await locator.click();
  await locator.fill(value);
}

export function buildStation(overrides: Record<string, unknown> = {}) {
  return {
    id: "station-night",
    name: "Nocturne FM",
    frequencyMHz: 76.1,
    genre: "Talk",
    isActive: true,
    programmingEnabled: true,
    defaultProgramTemplateId: "tmpl-night",
    ...overrides,
  };
}

export function buildRadioStatus(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: null,
    stationId: null,
    programBlockId: null,
    programTemplateId: null,
    programTitle: null,
    state: "IDLE",
    currentItemId: null,
    bufferReadyCount: 0,
    degraded: false,
    updatedAt: "2026-04-22T00:00:00Z",
    correlationId: null,
    ...overrides,
  };
}

export function buildQueueItem(overrides: Record<string, unknown> = {}) {
  return {
    id: "queue-001",
    programBlockId: "block-night-001",
    programSlotId: "slot-opening-001",
    slotRole: "OPENING",
    type: "TALK",
    title: "Night Shift Intro",
    playbackMode: "SERVER_AUDIO",
    assetUrl: "/api/assets/audio/asset-001.wav",
    speechDirectiveId: null,
    durationMs: 32000,
    status: "READY",
    correlationId: "corr-night-001",
    assetBanned: false,
    contentOrigin: "PLACEHOLDER",
    preparedAt: "2026-04-22T00:00:01Z",
    replayOfPlayHistoryId: null,
    letterId: null,
    ...overrides,
  };
}

export function buildQueueSnapshot(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: "session-night-001",
    stationId: "station-night",
    items: [buildQueueItem()],
    correlationId: "corr-night-001",
    ...overrides,
  };
}

export function buildProgramBlock(overrides: Record<string, unknown> = {}) {
  return {
    id: "block-night-001",
    stationId: "station-night",
    templateId: "tmpl-night",
    templateVersion: 1,
    title: "Night Session",
    status: "ACTIVE",
    plannedDurationMs: 900000,
    remainingSlotCount: 3,
    startedAt: "2026-04-22T00:00:00Z",
    slots: [
      {
        id: "slot-opening-001",
        slotId: "opening-main",
        role: "OPENING",
        constraintMode: "HARD",
        resolvedSegmentType: "TALK",
        targetDurationMs: 30000,
        status: "READY",
        slotContext: {},
        title: "Night Shift Intro",
      },
    ],
    correlationId: "corr-night-001",
    ...overrides,
  };
}

export function buildSpeechDirective(overrides: Record<string, unknown> = {}) {
  return {
    id: "speech-001",
    text: "Welcome back to SeedShiftRadio.",
    normalizedText: "Welcome back to SeedShiftRadio.",
    pronunciationHints: [],
    emotion: "CALM",
    tempo: "MEDIUM",
    pauseHints: [],
    personaRef: null,
    voiceHint: null,
    correlationId: "corr-night-001",
    ...overrides,
  };
}

export function buildPublicPlayHistory(overrides: Record<string, unknown> = {}) {
  return {
    id: "play-history-001",
    sessionId: "session-night-001",
    stationId: "station-night",
    segmentType: "LETTER",
    title: "Listener Mail Spotlight",
    resultStatus: "DONE",
    playedAt: "2026-04-22T00:15:00Z",
    ...overrides,
  };
}

export function buildPublicLetter(overrides: Record<string, unknown> = {}) {
  return {
    id: "letter-001",
    stationId: null,
    radioName: "Listener Zero",
    subject: "Need a night playlist",
    status: "ADOPTED",
    adoptedInSessionId: "session-night-001",
    createdAt: "2026-04-22T00:10:00Z",
    playHistory: [buildPublicPlayHistory()],
    ...overrides,
  };
}

export function buildSettingsResponse(overrides: Record<string, unknown> = {}) {
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
            adapter: "OLLAMA",
            defaultModelProfileId: "qwen3:8b",
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
    },
    ...overrides,
  };
}

export function buildStationDetail(overrides: Record<string, unknown> = {}) {
  return {
    id: "station-night",
    name: "Nocturne FM",
    frequencyMHz: 76.1,
    genre: "Talk",
    languagePersonaId: "persona-night",
    defaultVoiceProfileId: "voice-night",
    isActive: true,
    version: 3,
    programming: {
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
    },
    ...overrides,
  };
}

export function buildStationProgrammingResponse(overrides: Record<string, unknown> = {}) {
  return {
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
    rules: [
      {
        id: "rule-night-001",
        priority: 100,
        days: ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"],
        startTime: "00:00",
        endTime: "23:59",
        minimumPendingLetters: 0,
        requiredProviderStates: [],
        templateId: "tmpl-night",
      },
    ],
    ...overrides,
  };
}

export function buildProgramTemplateSummary(overrides: Record<string, unknown> = {}) {
  return {
    id: "tmpl-night",
    scope: "STATION",
    stationId: "station-night",
    name: "Night Talk",
    version: 2,
    targetDurationMinutes: 20,
    planningHorizonMinutes: 15,
    isActive: true,
    fallbackTemplateId: "tmpl-global-fallback",
    ...overrides,
  };
}

export function buildProgramTemplateDetail(overrides: Record<string, unknown> = {}) {
  return {
    ...buildProgramTemplateSummary(),
    editorialPolicy: {
      tone: "calm",
      topics: ["night", "coding"],
    },
    slots: [
      {
        slotId: "opening",
        role: "OPENING",
        constraintMode: "HARD",
        candidateSegmentTypes: ["JINGLE", "TALK"],
        fallbackSegmentTypes: ["TALK"],
        targetDurationMs: 30000,
        slotPolicy: {
          allowArchiveReplay: false,
        },
      },
    ],
    ...overrides,
  };
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
