import {
  type ClientCapabilitiesRequest,
  type ClientCapabilitiesResponse,
  type ConnectionsTestResponse,
  type HealthResponse,
  type LetterDetail,
  type LetterPublicLookupResponse,
  type LetterStatus,
  type LetterSummary,
  type MonitorSummary,
  type PlaybackEventRequest,
  type ProgramTemplateDetail,
  type ProgramTemplateSummary,
  type ProgramTemplateUpdateRequest,
  type ProgrammingPreviewRequest,
  type ProgrammingPreviewResponse,
  type StationProgrammingResponse,
  type StationProgrammingUpdateRequest,
  type ProgramBlockSummary,
  type QueueSnapshot,
  type RadioStatus,
  type SettingsResponse,
  type SettingsUpdateRequest,
  type SpeechDirective,
  type StationDetail,
  type StationResponse,
  type StationSummary,
  type StationUpdateRequest,
  type TuneRequest,
  type TuneResponse,
} from "@/lib/types";
import { getAdminToken, getApiBaseUrl } from "@/lib/env";

export class ApiRequestError extends Error {
  status: number;
  code: string | null;
  fieldErrors: Record<string, string>;

  constructor(message: string, options: { status: number; code?: string | null; fieldErrors?: Record<string, string> }) {
    super(message);
    this.name = "ApiRequestError";
    this.status = options.status;
    this.code = options.code ?? null;
    this.fieldErrors = options.fieldErrors ?? {};
  }
}

export function buildApiUrl(path: string, apiBase = getApiBaseUrl()) {
  return `${apiBase}${path}`;
}

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(buildApiUrl(path), {
    cache: "no-store",
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    const payload = await readApiError(response);
    throw new ApiRequestError(payload.message || `Request failed: ${response.status}`, {
      status: response.status,
      code: payload.code,
      fieldErrors: payload.fieldErrors,
    });
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export function mergeAdminHeaders(adminToken: string | null, headers?: HeadersInit) {
  if (!adminToken) {
    return headers;
  }
  return {
    ...(headers ?? {}),
    "X-Admin-Token": adminToken,
  };
}

export function withAdminHeaders(headers?: HeadersInit) {
  return mergeAdminHeaders(getAdminToken(), headers);
}

export async function safeReadError(response: Response) {
  const payload = await readApiError(response);
  return payload.message;
}

async function readApiError(response: Response) {
  try {
    const payload = await response.json();
    if (payload && typeof payload === "object") {
      return {
        message:
          typeof payload.message === "string"
            ? payload.message
            : typeof payload.error === "string"
              ? payload.error
              : response.statusText || `Request failed: ${response.status}`,
        code: typeof payload.code === "string" ? payload.code : null,
        fieldErrors: extractFieldErrors((payload as { details?: unknown }).details),
      };
    }
  } catch {
    // fall through to statusText fallback
  }

  return {
    message: response.statusText || `Request failed: ${response.status}`,
    code: null,
    fieldErrors: {},
  };
}

function extractFieldErrors(details: unknown) {
  if (!details || typeof details !== "object" || Array.isArray(details)) {
    return {};
  }
  const rawFieldErrors = (details as { fieldErrors?: unknown }).fieldErrors;
  if (!rawFieldErrors || typeof rawFieldErrors !== "object" || Array.isArray(rawFieldErrors)) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(rawFieldErrors).filter((entry): entry is [string, string] => typeof entry[1] === "string"),
  );
}

export function getApiBase() {
  return getApiBaseUrl();
}

export function buildNextSpeechDirectivePath(clientId?: string) {
  const suffix = clientId ? `?clientId=${encodeURIComponent(clientId)}` : "";
  return `/api/radio/next-speech-directive${suffix}`;
}

export function buildLettersPath(stationId?: string, status?: LetterStatus) {
  const params = new URLSearchParams();
  if (stationId) {
    params.set("stationId", stationId);
  }
  if (status) {
    params.set("status", status);
  }
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return `/api/letters${suffix}`;
}

export function listStations() {
  return requestJson<StationSummary[]>("/api/stations");
}

export function createStation(body: StationUpdateRequest) {
  return requestJson<StationResponse>("/api/stations", {
    method: "POST",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function getStation(stationId: string) {
  return requestJson<StationDetail>(`/api/stations/${encodeURIComponent(stationId)}`);
}

export function updateStation(stationId: string, body: StationUpdateRequest) {
  return requestJson<StationResponse>(`/api/stations/${encodeURIComponent(stationId)}`, {
    method: "PUT",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function getStationProgramming(stationId: string) {
  return requestJson<StationProgrammingResponse>(`/api/stations/${encodeURIComponent(stationId)}/programming`, {
    headers: withAdminHeaders(),
  });
}

export function updateStationProgramming(stationId: string, body: StationProgrammingUpdateRequest) {
  return requestJson<StationProgrammingResponse>(`/api/stations/${encodeURIComponent(stationId)}/programming`, {
    method: "PUT",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function listProgramTemplates() {
  return requestJson<ProgramTemplateSummary[]>("/api/program-templates", {
    headers: withAdminHeaders(),
  });
}

export function getProgramTemplate(templateId: string) {
  return requestJson<ProgramTemplateDetail>(`/api/program-templates/${encodeURIComponent(templateId)}`, {
    headers: withAdminHeaders(),
  });
}

export function createProgramTemplate(body: ProgramTemplateUpdateRequest) {
  return requestJson<ProgramTemplateDetail>("/api/program-templates", {
    method: "POST",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function updateProgramTemplate(templateId: string, body: ProgramTemplateUpdateRequest) {
  return requestJson<ProgramTemplateDetail>(`/api/program-templates/${encodeURIComponent(templateId)}`, {
    method: "PUT",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function getRadioStatus() {
  return requestJson<RadioStatus>("/api/radio/status");
}

export function getRadioQueue() {
  return requestJson<QueueSnapshot>("/api/radio/queue");
}

export function getRadioProgram() {
  return requestJson<ProgramBlockSummary>("/api/radio/program");
}

export function getNextSpeechDirective(clientId?: string) {
  return requestJson<SpeechDirective>(buildNextSpeechDirectivePath(clientId));
}

export function tuneRadio(request: TuneRequest) {
  return requestJson<TuneResponse>("/api/radio/tune", {
    method: "POST",
    body: JSON.stringify(request),
    headers: { "Content-Type": "application/json" },
  });
}

export function startPlayback() {
  return requestJson<RadioStatus>("/api/radio/play", { method: "POST" });
}

export function stopPlayback() {
  return requestJson<RadioStatus>("/api/radio/stop", { method: "POST" });
}

export function sendPlaybackEvent(request: PlaybackEventRequest) {
  return requestJson<void>("/api/radio/playback-events", {
    method: "POST",
    body: JSON.stringify(request),
    headers: { "Content-Type": "application/json" },
  });
}

export function registerClientCapabilities(request: ClientCapabilitiesRequest) {
  return requestJson<ClientCapabilitiesResponse>("/api/clients/capabilities", {
    method: "POST",
    body: JSON.stringify(request),
    headers: { "Content-Type": "application/json" },
  });
}

export function listLetters(stationId?: string, status?: LetterStatus) {
  return requestJson<LetterSummary[]>(buildLettersPath(stationId, status), {
    headers: withAdminHeaders(),
  });
}

export function getLetter(letterId: string) {
  return requestJson<LetterDetail>(`/api/letters/${encodeURIComponent(letterId)}`, {
    headers: withAdminHeaders(),
  });
}

export function createLetter(
  body: {
    stationId: string | null;
    radioName: string;
    subject: string;
    body: string;
  },
  options?: { idempotencyKey?: string },
) {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (options?.idempotencyKey) {
    headers["Idempotency-Key"] = options.idempotencyKey;
  }
  return requestJson<{ id: string; status: LetterStatus; createdAt: string }>("/api/letters", {
    method: "POST",
    body: JSON.stringify(body),
    headers,
  });
}

export function lookupLetterPublicHistory(letterIds: string[]) {
  return requestJson<LetterPublicLookupResponse>("/api/letters/public/history", {
    method: "POST",
    body: JSON.stringify({ letterIds }),
    headers: { "Content-Type": "application/json" },
  });
}

export function updateLetterStatus(letterId: string, body: { status: LetterStatus; sessionId?: string | null }) {
  return requestJson(`/api/letters/${encodeURIComponent(letterId)}/status`, {
    method: "POST",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function replyLetter(letterId: string, body: { replyText: string }) {
  return requestJson<{ id: string; createdAt: string }>(`/api/letters/${encodeURIComponent(letterId)}/reply`, {
    method: "POST",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function getMonitorSummary() {
  return requestJson<MonitorSummary>("/api/monitor/summary", {
    headers: withAdminHeaders(),
  });
}

export function getHealth() {
  return requestJson<HealthResponse>("/api/health");
}

export function getSettings() {
  return requestJson<SettingsResponse>("/api/settings", {
    headers: withAdminHeaders(),
  });
}

export function updateSettings(body: SettingsUpdateRequest) {
  return requestJson<SettingsResponse>("/api/settings", {
    method: "PUT",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}

export function testConnections() {
  return requestJson<ConnectionsTestResponse>("/api/settings/test-connections", {
    method: "POST",
    headers: withAdminHeaders(),
  });
}

export function previewProgramming(stationId: string, body: ProgrammingPreviewRequest) {
  return requestJson<ProgrammingPreviewResponse>(`/api/stations/${encodeURIComponent(stationId)}/programming/preview`, {
    method: "POST",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}
