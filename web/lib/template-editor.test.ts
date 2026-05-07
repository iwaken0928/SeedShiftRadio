import { describe, expect, it } from "vitest";
import {
  createBlankProgramTemplateDraft,
  createDuplicatedProgramTemplateDraft,
  createProgramTemplateDraftFromDetail,
  serializeProgramTemplateDraft,
} from "@/lib/template-editor";
import type { ProgramTemplateDetail, ProgramTemplateSummary } from "@/lib/types";

describe("template-editor", () => {
  it("blank template draft は選択中 station を引き継いだ STATION scope で開始できる", () => {
    const draft = createBlankProgramTemplateDraft("station-night");

    expect(draft).toEqual({
      id: "",
      scope: "STATION",
      stationId: "station-night",
      name: "",
      version: 0,
      targetDurationMinutes: 15,
      planningHorizonMinutes: 10,
      isActive: true,
      editorialPolicyText: "{}",
      fallbackTemplateId: null,
      slots: [
        {
          slotId: "opening",
          role: "OPENING",
          constraintMode: "HARD",
          candidateSegmentTypes: ["JINGLE", "TALK"],
          fallbackSegmentTypes: ["TALK"],
          targetDurationMs: 30000,
          slotPolicyText: "{}",
        },
      ],
    });
  });

  it("duplicate template draft は ID を新規候補へ補正し、policy と slot を保持する", () => {
    const draft = createDuplicatedProgramTemplateDraft(
      createTemplateDetail({
        id: "tmpl-night-deep",
        name: "深夜ロングトーク",
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
            slotPolicy: { allowArchiveReplay: false },
          },
        ],
      }),
      [
        createTemplateSummary({ id: "tmpl-night-deep" }),
        createTemplateSummary({ id: "tmpl-night-deep-copy" }),
      ],
    );

    expect(draft.id).toBe("tmpl-night-deep-copy-2");
    expect(draft.name).toBe("深夜ロングトーク Copy");
    expect(draft.version).toBe(0);
    expect(draft.editorialPolicyText).toContain("\"tone\": \"calm\"");
    expect(draft.slots[0].slotPolicyText).toContain("\"allowArchiveReplay\": false");
  });

  it("detail draft を serialize すると JSON text を object へ戻し、GLOBAL scope では stationId を落とす", () => {
    const draft = createProgramTemplateDraftFromDetail(
      createTemplateDetail({
        scope: "GLOBAL",
        stationId: "station-night",
        editorialPolicy: { energy: "bright" },
      }),
    );

    const serialized = serializeProgramTemplateDraft({
      ...draft,
      editorialPolicyText: "{\"energy\":\"bright\",\"tags\":[\"morning\"]}",
      stationId: "station-night",
      slots: [
        {
          ...draft.slots[0],
          slotPolicyText: "{\"preferFreshGeneration\":true}",
        },
      ],
    });

    expect(serialized.stationId).toBeNull();
    expect(serialized.editorialPolicy).toEqual({
      energy: "bright",
      tags: ["morning"],
    });
    expect(serialized.slots[0].slotPolicy).toEqual({
      preferFreshGeneration: true,
    });
  });

  it("serialize は object 以外の JSON を拒否する", () => {
    expect(() =>
      serializeProgramTemplateDraft({
        ...createBlankProgramTemplateDraft(null),
        id: "tmpl-global-morning",
        name: "朝テンプレート",
        editorialPolicyText: "[]",
      }),
    ).toThrow("Editorial Policy は JSON object で指定してください。");
  });
});

function createTemplateSummary(overrides: Partial<ProgramTemplateSummary> = {}): ProgramTemplateSummary {
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

function createTemplateDetail(overrides: Partial<ProgramTemplateDetail> = {}): ProgramTemplateDetail {
  return {
    ...createTemplateSummary(),
    editorialPolicy: {},
    slots: [
      {
        slotId: "opening",
        role: "OPENING",
        constraintMode: "HARD",
        candidateSegmentTypes: ["JINGLE", "TALK"],
        fallbackSegmentTypes: ["TALK"],
        targetDurationMs: 30000,
        slotPolicy: {},
      },
    ],
    ...overrides,
  };
}
