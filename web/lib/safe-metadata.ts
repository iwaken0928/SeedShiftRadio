export const REDACTED_METADATA_VALUE = "[redacted]";

const MAX_METADATA_VALUE_LENGTH = 160;
const MAX_METADATA_SCAN_DEPTH = 4;

const SENSITIVE_KEY_PATTERNS = [
  /token/i,
  /api[_-]?key/i,
  /secret/i,
  /password/i,
  /credential/i,
  /authorization/i,
  /bearer/i,
  /cookie/i,
  /^auth$/i,
  /prompt/i,
  /lyrics/i,
  /letter.*body/i,
  /letter.*content/i,
  /^body$/i,
  /reply.*text/i,
  /radio.*name/i,
  /provider.*response/i,
  /raw.*response/i,
  /transcript/i,
  /^script$/i,
] as const;

const SENSITIVE_VALUE_PATTERNS = [
  /^Bearer\s+\S+/i,
  /^Basic\s+\S+/i,
  /^sk-[A-Za-z0-9_-]{12,}/i,
  /:\/\/[^/\s:@]+:[^/\s:@]+@/,
] as const;

const SENSITIVE_INLINE_PATTERNS = [
  /\b(api[_-]?key|admin[_-]?token|token|secret|password|authorization|prompt|lyrics|letterBody|radioName|providerResponse|rawResponse)\b\s*[:=]/i,
] as const;

export interface SafeMetadataEntry {
  key: string;
  value: string;
  redacted: boolean;
}

export function getSafeMetadataEntries(metadata?: Record<string, unknown> | null): SafeMetadataEntry[] {
  if (!metadata) {
    return [];
  }

  return Object.entries(metadata).map(([key, value]) => {
    const redacted = shouldRedactMetadataValue(key, value);
    return {
      key,
      value: redacted ? REDACTED_METADATA_VALUE : formatMetadataValue(value),
      redacted,
    };
  });
}

export function formatSafeMetadataValue(key: string, value: unknown): string {
  return shouldRedactMetadataValue(key, value) ? REDACTED_METADATA_VALUE : formatMetadataValue(value);
}

export function formatSafeDisplayText(value: unknown): string {
  return containsSensitiveValue(value, 0) ? REDACTED_METADATA_VALUE : formatMetadataValue(value);
}

export function isSensitiveMetadataKey(key: string): boolean {
  return SENSITIVE_KEY_PATTERNS.some((pattern) => pattern.test(key));
}

function shouldRedactMetadataValue(key: string, value: unknown): boolean {
  return isSensitiveMetadataKey(key) || containsSensitiveValue(value, 0);
}

function containsSensitiveValue(value: unknown, depth: number): boolean {
  if (value == null) {
    return false;
  }
  if (typeof value === "string") {
    return (
      SENSITIVE_VALUE_PATTERNS.some((pattern) => pattern.test(value)) ||
      SENSITIVE_INLINE_PATTERNS.some((pattern) => pattern.test(value))
    );
  }
  if (Array.isArray(value)) {
    return depth < MAX_METADATA_SCAN_DEPTH && value.some((entry) => containsSensitiveValue(entry, depth + 1));
  }
  if (typeof value === "object") {
    if (depth >= MAX_METADATA_SCAN_DEPTH) {
      return false;
    }
    return Object.entries(value as Record<string, unknown>).some(
      ([nestedKey, nestedValue]) => isSensitiveMetadataKey(nestedKey) || containsSensitiveValue(nestedValue, depth + 1),
    );
  }
  return false;
}

function formatMetadataValue(value: unknown): string {
  if (value == null) {
    return "-";
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return "-";
    }
    return truncateMetadataValue(value.map((entry) => formatMetadataValue(entry)).join(", "));
  }
  if (typeof value === "object") {
    return truncateMetadataValue(serializeObject(value));
  }
  return truncateMetadataValue(String(value));
}

function serializeObject(value: object): string {
  try {
    const serialized = JSON.stringify(value);
    return serialized === "{}" ? "-" : serialized;
  } catch {
    return "[unserializable]";
  }
}

function truncateMetadataValue(value: string): string {
  if (value.length <= MAX_METADATA_VALUE_LENGTH) {
    return value;
  }
  return `${value.slice(0, MAX_METADATA_VALUE_LENGTH)}...`;
}
