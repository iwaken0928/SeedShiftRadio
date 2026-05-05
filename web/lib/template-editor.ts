import type { ProgramTemplateDetail, ProgramTemplateSummary, ProgramTemplateUpdateRequest, ProgramTemplateSlot } from "@/lib/types";

const DEFAULT_TEMPLATE_DURATION_MINUTES = 15;
const DEFAULT_PLANNING_HORIZON_MINUTES = 10;
const TEMPLATE_ID_MAX_ATTEMPTS = 100;

export type ProgramTemplateSlotDraft = Omit<ProgramTemplateSlot, "slotPolicy"> & {
  slotPolicyText: string;
};

export type ProgramTemplateEditorDraft = Omit<ProgramTemplateUpdateRequest, "editorialPolicy" | "slots"> & {
  editorialPolicyText: string;
  slots: ProgramTemplateSlotDraft[];
};

export function createProgramTemplateDraftFromDetail(template: ProgramTemplateDetail): ProgramTemplateEditorDraft {
  return {
    id: template.id,
    scope: template.scope,
    stationId: template.stationId,
    name: template.name,
    version: template.version,
    targetDurationMinutes: template.targetDurationMinutes,
    planningHorizonMinutes: template.planningHorizonMinutes,
    isActive: template.isActive,
    editorialPolicyText: formatJsonText(template.editorialPolicy),
    fallbackTemplateId: template.fallbackTemplateId,
    slots: template.slots.map(createSlotDraft),
  };
}

export function createBlankProgramTemplateDraft(selectedStationId: string | null): ProgramTemplateEditorDraft {
  return {
    id: "",
    scope: selectedStationId ? "STATION" : "GLOBAL",
    stationId: selectedStationId,
    name: "",
    version: 0,
    targetDurationMinutes: DEFAULT_TEMPLATE_DURATION_MINUTES,
    planningHorizonMinutes: DEFAULT_PLANNING_HORIZON_MINUTES,
    isActive: true,
    editorialPolicyText: "{}",
    fallbackTemplateId: null,
    slots: [createDefaultSlotDraft()],
  };
}

export function createDuplicatedProgramTemplateDraft(
  source: ProgramTemplateDetail,
  templates: ProgramTemplateSummary[],
): ProgramTemplateEditorDraft {
  return {
    ...createProgramTemplateDraftFromDetail(source),
    id: suggestTemplateId(`${source.id}-copy`, templates),
    name: `${source.name} Copy`,
    version: 0,
  };
}

export function cloneProgramTemplateDraft(draft: ProgramTemplateEditorDraft): ProgramTemplateEditorDraft {
  return {
    ...draft,
    slots: draft.slots.map((slot) => ({ ...slot, candidateSegmentTypes: [...slot.candidateSegmentTypes], fallbackSegmentTypes: [...slot.fallbackSegmentTypes] })),
  };
}

export function serializeProgramTemplateDraft(draft: ProgramTemplateEditorDraft): ProgramTemplateUpdateRequest {
  return {
    id: draft.id.trim(),
    scope: draft.scope,
    stationId: draft.scope === "STATION" ? normalizeNullableString(draft.stationId) : null,
    name: draft.name.trim(),
    version: draft.version,
    targetDurationMinutes: draft.targetDurationMinutes,
    planningHorizonMinutes: draft.planningHorizonMinutes,
    isActive: draft.isActive,
    editorialPolicy: parseJsonObject(draft.editorialPolicyText, "Editorial Policy"),
    fallbackTemplateId: normalizeNullableString(draft.fallbackTemplateId),
    slots: draft.slots.map((slot) => ({
      slotId: slot.slotId.trim(),
      role: slot.role,
      constraintMode: slot.constraintMode,
      candidateSegmentTypes: [...slot.candidateSegmentTypes],
      fallbackSegmentTypes: [...slot.fallbackSegmentTypes],
      targetDurationMs: slot.targetDurationMs,
      slotPolicy: parseJsonObject(slot.slotPolicyText, `Slot Policy (${slot.slotId.trim() || "new slot"})`),
    })),
  };
}

export function createDefaultSlotDraft(): ProgramTemplateSlotDraft {
  return {
    slotId: "opening",
    role: "OPENING",
    constraintMode: "HARD",
    candidateSegmentTypes: ["JINGLE", "TALK"],
    fallbackSegmentTypes: ["TALK"],
    targetDurationMs: 30000,
    slotPolicyText: "{}",
  };
}

function createSlotDraft(slot: ProgramTemplateSlot): ProgramTemplateSlotDraft {
  return {
    slotId: slot.slotId,
    role: slot.role,
    constraintMode: slot.constraintMode,
    candidateSegmentTypes: [...slot.candidateSegmentTypes],
    fallbackSegmentTypes: [...slot.fallbackSegmentTypes],
    targetDurationMs: slot.targetDurationMs,
    slotPolicyText: formatJsonText(slot.slotPolicy),
  };
}

function suggestTemplateId(baseId: string, templates: ProgramTemplateSummary[]): string {
  if (!isTemplateIdUsed(baseId, templates)) {
    return baseId;
  }

  for (let index = 2; index <= TEMPLATE_ID_MAX_ATTEMPTS; index += 1) {
    const candidate = `${baseId}-${index}`;
    if (!isTemplateIdUsed(candidate, templates)) {
      return candidate;
    }
  }

  return `${baseId}-${Date.now()}`;
}

function isTemplateIdUsed(templateId: string, templates: ProgramTemplateSummary[]): boolean {
  return templates.some((template) => template.id === templateId);
}

function formatJsonText(value: Record<string, unknown>) {
  return Object.keys(value).length ? JSON.stringify(value, null, 2) : "{}";
}

function parseJsonObject(value: string, label: string): Record<string, unknown> {
  const trimmed = value.trim();
  if (!trimmed) {
    return {};
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    throw new Error(`${label} は JSON object で指定してください。`);
  }

  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error(`${label} は JSON object で指定してください。`);
  }

  return parsed as Record<string, unknown>;
}

function normalizeNullableString(value: string | null) {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}
