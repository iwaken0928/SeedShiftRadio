import type { ProviderHealthPayload } from "@/lib/types";

const WORKER_METADATA_KEYS = [
  "adapter",
  "defaultModelProfileId",
  "modelProfileIds",
  "queueSize",
  "queuedJobs",
  "runningJobs",
  "averageJobSeconds",
  "defaultModel",
  "models",
  "statsStatus",
  "modelsStatus",
] as const;

export interface WorkerStatusDetails {
  adapter: string | null;
  defaultModelProfileId: string | null;
  modelProfileIds: string[];
  queueSize: number | null;
  queuedJobs: number | null;
  runningJobs: number | null;
  averageJobSeconds: number | null;
  defaultModel: string | null;
  models: string[];
  statsStatus: string | null;
  modelsStatus: string | null;
}

export interface ProviderMetadataHighlight {
  label: string;
  value: string;
}

export function extractWorkerStatusDetails(health: ProviderHealthPayload): WorkerStatusDetails | null {
  const metadata = asRecord(health.metadata);
  if (!metadata || !WORKER_METADATA_KEYS.some((key) => key in metadata)) {
    return null;
  }

  return {
    adapter: readString(metadata.adapter),
    defaultModelProfileId: readString(metadata.defaultModelProfileId),
    modelProfileIds: readStringArray(metadata.modelProfileIds),
    queueSize: readNumber(metadata.queueSize),
    queuedJobs: readNumber(metadata.queuedJobs),
    runningJobs: readNumber(metadata.runningJobs),
    averageJobSeconds: readNumber(metadata.averageJobSeconds),
    defaultModel: formatValue(metadata.defaultModel),
    models: readStringArray(metadata.models),
    statsStatus: readString(metadata.statsStatus),
    modelsStatus: readString(metadata.modelsStatus),
  };
}

export function getProviderMetadataHighlights(health: ProviderHealthPayload): ProviderMetadataHighlight[] {
  const workerStatus = extractWorkerStatusDetails(health);
  if (!workerStatus) {
    return [];
  }

  return [
    workerStatus.adapter ? { label: "Adapter", value: workerStatus.adapter } : null,
    workerStatus.queueSize != null ? { label: "Queue", value: String(workerStatus.queueSize) } : null,
    workerStatus.runningJobs != null ? { label: "Running", value: String(workerStatus.runningJobs) } : null,
    workerStatus.defaultModelProfileId ? { label: "Profile", value: workerStatus.defaultModelProfileId } : null,
  ].filter((value): value is ProviderMetadataHighlight => value != null);
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function readString(value: unknown): string | null {
  if (typeof value === "string" && value.trim().length > 0) {
    return value;
  }
  return null;
}

function readNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim().length > 0) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((entry) => formatValue(entry))
    .filter((entry): entry is string => entry != null);
}

function formatValue(value: unknown): string | null {
  if (typeof value === "string" && value.trim().length > 0) {
    return value;
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return String(value);
  }
  if (typeof value === "boolean") {
    return value ? "true" : "false";
  }

  const record = asRecord(value);
  if (!record) {
    return null;
  }

  const preferredKeys = ["id", "name", "model", "profileId", "value"];
  for (const key of preferredKeys) {
    const nested = readString(record[key]);
    if (nested) {
      return nested;
    }
  }

  const serialized = JSON.stringify(record);
  return serialized === "{}" ? null : serialized;
}
