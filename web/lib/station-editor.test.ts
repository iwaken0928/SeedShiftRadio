import { describe, expect, it } from "vitest";
import { applyProgrammingSummaryToStationDraft, createBlankStationDraft, createDuplicatedStationDraft } from "@/lib/station-editor";
import type { ProgramTemplateSummary, StationDetail, StationProgrammingResponse, StationSummary, StationUpdateRequest } from "@/lib/types";

describe("station-editor", () => {
  it("blank station draft は最初の未使用周波数で新規局ドラフトを作る", () => {
    const draft = createBlankStationDraft([
      createStationSummary({ id: "station-a", frequencyMHz: 80.0 }),
      createStationSummary({ id: "station-b", frequencyMHz: 80.1 }),
    ]);

    expect(draft).toEqual({
      version: 0,
      id: "",
      name: "",
      frequencyMHz: 80.2,
      genre: "",
      languagePersonaId: "",
      defaultVoiceProfileId: "",
      isActive: true,
      programmingEnabled: false,
      defaultProgramTemplateId: null,
    });
  });

  it("duplicate station draft は ID と周波数をずらし、station scope template は引き継がない", () => {
    const draft = createDuplicatedStationDraft(
      createStationDetail({
        id: "station-night",
        name: "Midnight Echo",
        frequencyMHz: 81.3,
        genre: "ambient",
        languagePersonaId: "persona-night",
        defaultVoiceProfileId: "voice-night",
        isActive: false,
        programming: {
          enabled: true,
          defaultTemplateId: "tmpl-station-night",
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
      }),
      [
        createStationSummary({ id: "station-night", frequencyMHz: 81.3 }),
        createStationSummary({ id: "station-night-copy", frequencyMHz: 81.4 }),
      ],
      [
        createTemplate({ id: "tmpl-station-night", scope: "STATION", stationId: "station-night" }),
        createTemplate({ id: "tmpl-global", scope: "GLOBAL" }),
      ],
    );

    expect(draft).toEqual({
      version: 0,
      id: "station-night-copy-2",
      name: "Midnight Echo Copy",
      frequencyMHz: 81.5,
      genre: "ambient",
      languagePersonaId: "persona-night",
      defaultVoiceProfileId: "voice-night",
      isActive: false,
      programmingEnabled: false,
      defaultProgramTemplateId: null,
    });
  });

  it("duplicate station draft は global template だけを控えめに引き継ぐ", () => {
    const draft = createDuplicatedStationDraft(
      createStationDetail({
        id: "station-day",
        programming: {
          enabled: true,
          defaultTemplateId: "tmpl-global",
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
        },
      }),
      [createStationSummary({ id: "station-day", frequencyMHz: 77.7 })],
      [createTemplate({ id: "tmpl-global", scope: "GLOBAL" })],
    );

    expect(draft.defaultProgramTemplateId).toBe("tmpl-global");
    expect(draft.programmingEnabled).toBe(false);
  });

  it("programming 保存後は station draft の summary だけを同期できる", () => {
    const draft = applyProgrammingSummaryToStationDraft(createStationUpdateRequest(), createProgrammingResponse({
      enabled: true,
      defaultTemplateId: "tmpl-global",
    }));

    expect(draft).toMatchObject({
      id: "station-default",
      name: "Station Default",
      programmingEnabled: true,
      defaultProgramTemplateId: "tmpl-global",
    });
  });
});

function createStationSummary(overrides: Partial<StationSummary> = {}): StationSummary {
  return {
    id: "station-default",
    name: "Station Default",
    frequencyMHz: 77.0,
    genre: "talk",
    isActive: true,
    programmingEnabled: false,
    defaultProgramTemplateId: null,
    ...overrides,
  };
}

function createStationDetail(overrides: Partial<StationDetail> = {}): StationDetail {
  return {
    id: "station-default",
    name: "Station Default",
    frequencyMHz: 77.7,
    genre: "talk",
    languagePersonaId: "persona-default",
    defaultVoiceProfileId: "voice-default",
    isActive: true,
    version: 4,
    programming: {
      enabled: false,
      defaultTemplateId: null,
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

function createTemplate(overrides: Partial<ProgramTemplateSummary> = {}): ProgramTemplateSummary {
  return {
    id: "tmpl-default",
    scope: "GLOBAL",
    stationId: null,
    name: "Default Template",
    version: 1,
    targetDurationMinutes: 20,
    planningHorizonMinutes: 15,
    isActive: true,
    fallbackTemplateId: null,
    ...overrides,
  };
}

function createStationUpdateRequest(overrides: Partial<StationUpdateRequest> = {}): StationUpdateRequest {
  return {
    version: 4,
    id: "station-default",
    name: "Station Default",
    frequencyMHz: 77.7,
    genre: "talk",
    languagePersonaId: "persona-default",
    defaultVoiceProfileId: "voice-default",
    isActive: true,
    programmingEnabled: false,
    defaultProgramTemplateId: null,
    ...overrides,
  };
}

function createProgrammingResponse(overrides: Partial<StationProgrammingResponse> = {}): StationProgrammingResponse {
  return {
    stationId: "station-default",
    version: 3,
    enabled: false,
    defaultTemplateId: null,
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
    updatedAt: "2026-04-25T00:00:00Z",
    rules: [],
    ...overrides,
  };
}
