import type { ProviderHealthPayload } from "@/lib/types";
import { formatSafeMetadataValue, isSensitiveMetadataKey, REDACTED_METADATA_VALUE } from "@/lib/safe-metadata";

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
  "modelsInitialized",
  "llmInitialized",
  "loadedModel",
  "loadedLmModel",
  "selectedModel",
  "selectedLmModel",
  "thinkingEnabled",
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
  modelsInitialized: boolean | null;
  llmInitialized: boolean | null;
  loadedModel: string | null;
  loadedLmModel: string | null;
  selectedModel: string | null;
  selectedLmModel: string | null;
  thinkingEnabled: boolean | null;
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
    modelsInitialized: readBoolean(metadata.modelsInitialized),
    llmInitialized: readBoolean(metadata.llmInitialized),
    loadedModel: readString(metadata.loadedModel),
    loadedLmModel: readString(metadata.loadedLmModel),
    selectedModel: readString(metadata.selectedModel),
    selectedLmModel: readString(metadata.selectedLmModel),
    thinkingEnabled: readBoolean(metadata.thinkingEnabled),
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
    workerStatus.modelsInitialized != null
      ? { label: "Model init", value: workerStatus.modelsInitialized ? "READY" : "NOT_READY" }
      : null,
    workerStatus.llmInitialized != null
      ? { label: "LM init", value: workerStatus.llmInitialized ? "READY" : "NOT_READY" }
      : null,
  ].filter((value): value is ProviderMetadataHighlight => value != null);
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function readString(value: unknown): string | null {
  if (typeof value === "string" && value.trim().length > 0) {
    return safeStringValue("workerMetadata", value);
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

function readBoolean(value: unknown): boolean | null {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string" && /^(true|false)$/i.test(value.trim())) {
    return value.trim().toLowerCase() === "true";
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
    return safeStringValue("workerMetadata", value);
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

  if (
    Object.entries(record).some(
      ([key, nestedValue]) => isSensitiveMetadataKey(key) || formatSafeMetadataValue(key, nestedValue) === REDACTED_METADATA_VALUE,
    )
  ) {
    return null;
  }

  const preferredKeys = ["id", "name", "model", "profileId", "value"];
  for (const key of preferredKeys) {
    const nested = readString(record[key]);
    if (nested) {
      return nested;
    }
  }
  return null;
}

function safeStringValue(key: string, value: string): string | null {
  const safeValue = formatSafeMetadataValue(key, value);
  return safeValue === REDACTED_METADATA_VALUE ? null : safeValue;
}
