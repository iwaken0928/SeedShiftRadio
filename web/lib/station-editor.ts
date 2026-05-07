import type { ProgramTemplateSummary, StationDetail, StationProgrammingResponse, StationSummary, StationUpdateRequest } from "@/lib/types";

const STATION_ID_MAX_ATTEMPTS = 100;
const DEFAULT_STATION_FREQUENCY = 80.0;
const FREQUENCY_STEP = 0.1;

export function createBlankStationDraft(stations: StationSummary[]): StationUpdateRequest {
  return {
    version: 0,
    id: "",
    name: "",
    frequencyMHz: findAvailableFrequency(stations, DEFAULT_STATION_FREQUENCY),
    genre: "",
    languagePersonaId: "",
    defaultVoiceProfileId: "",
    isActive: true,
    programmingEnabled: false,
    defaultProgramTemplateId: null,
  };
}

export function createDuplicatedStationDraft(
  source: StationDetail,
  stations: StationSummary[],
  templates: ProgramTemplateSummary[],
): StationUpdateRequest {
  const defaultTemplateId = resolveDuplicatedDefaultTemplateId(source.programming.defaultTemplateId, templates);

  return {
    version: 0,
    id: suggestStationId(`${source.id}-copy`, stations),
    name: `${source.name} Copy`,
    frequencyMHz: findAvailableFrequency(stations, source.frequencyMHz + FREQUENCY_STEP),
    genre: source.genre,
    languagePersonaId: source.languagePersonaId,
    defaultVoiceProfileId: source.defaultVoiceProfileId,
    isActive: source.isActive,
    programmingEnabled: false,
    defaultProgramTemplateId: defaultTemplateId,
  };
}

export function cloneStationDraft(draft: StationUpdateRequest): StationUpdateRequest {
  return { ...draft };
}

export function applyProgrammingSummaryToStationDraft(
  draft: StationUpdateRequest,
  programming: Pick<StationProgrammingResponse, "enabled" | "defaultTemplateId">,
): StationUpdateRequest {
  return {
    ...draft,
    programmingEnabled: programming.enabled,
    defaultProgramTemplateId: programming.defaultTemplateId,
  };
}

function resolveDuplicatedDefaultTemplateId(
  templateId: string | null,
  templates: ProgramTemplateSummary[],
): string | null {
  if (!templateId) {
    return null;
  }

  const template = templates.find((entry) => entry.id === templateId);
  return template?.scope === "GLOBAL" ? template.id : null;
}

function suggestStationId(baseId: string, stations: StationSummary[]): string {
  if (!isStationIdUsed(baseId, stations)) {
    return baseId;
  }

  for (let index = 2; index <= STATION_ID_MAX_ATTEMPTS; index += 1) {
    const candidate = `${baseId}-${index}`;
    if (!isStationIdUsed(candidate, stations)) {
      return candidate;
    }
  }

  return `${baseId}-${Date.now()}`;
}

function findAvailableFrequency(stations: StationSummary[], start: number): number {
  let candidate = normalizeFrequency(start);

  for (let attempt = 0; attempt < 1000; attempt += 1) {
    if (!isFrequencyUsed(candidate, stations)) {
      return candidate;
    }
    candidate = normalizeFrequency(candidate + FREQUENCY_STEP);
  }

  return normalizeFrequency(start);
}

function isStationIdUsed(stationId: string, stations: StationSummary[]): boolean {
  return stations.some((station) => station.id === stationId);
}

function isFrequencyUsed(frequencyMHz: number, stations: StationSummary[]): boolean {
  return stations.some((station) => Math.abs(station.frequencyMHz - frequencyMHz) < 0.0001);
}

function normalizeFrequency(value: number): number {
  return Number(value.toFixed(1));
}
