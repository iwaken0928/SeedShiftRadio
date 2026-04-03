import {
  type ClientCapabilitiesRequest,
  type ClientCapabilitiesResponse,
  type ConnectionsTestResponse,
  type HealthResponse,
  type LetterDetail,
  type LetterReplySummary,
  type LetterStatus,
  type LetterSummary,
  type MonitorSummary,
  type PlaybackEventRequest,
  type ProgramBlockSummary,
  type QueueSnapshot,
  type RadioStatus,
  type SettingsResponse,
  type SettingsUpdateRequest,
  type SpeechDirective,
  type StationDetail,
  type StationSummary,
  type TuneRequest,
  type TuneResponse,
} from "@/lib/types";
import { getAdminToken, getApiBaseUrl } from "@/lib/env";

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${getApiBaseUrl()}${path}`, {
    cache: "no-store",
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    const message = await safeReadError(response);
    throw new Error(message || `Request failed: ${response.status}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

function withAdminHeaders(headers?: HeadersInit) {
  const token = getAdminToken();
  if (!token) {
    return headers;
  }
  return {
    ...(headers ?? {}),
    "X-Admin-Token": token,
  };
}

async function safeReadError(response: Response) {
  try {
    const payload = await response.json();
    if (typeof payload?.message === "string") {
      return payload.message;
    }
    if (typeof payload?.error === "string") {
      return payload.error;
    }
    return JSON.stringify(payload);
  } catch {
    return response.statusText;
  }
}

export function getApiBase() {
  return getApiBaseUrl();
}

export function listStations() {
  return requestJson<StationSummary[]>("/api/stations");
}

export function getStation(stationId: string) {
  return requestJson<StationDetail>(`/api/stations/${encodeURIComponent(stationId)}`);
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
  const suffix = clientId ? `?clientId=${encodeURIComponent(clientId)}` : "";
  return requestJson<SpeechDirective>(`/api/radio/next-speech-directive${suffix}`);
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
  const params = new URLSearchParams();
  if (stationId) {
    params.set("stationId", stationId);
  }
  if (status) {
    params.set("status", status);
  }
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return requestJson<LetterSummary[]>(`/api/letters${suffix}`, {
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

export function previewProgramming(stationId: string, body: { at: string; pendingLetterCount: number; providerStates?: { musicGen?: string; tts?: string; llm?: string } }) {
  return requestJson(`/api/stations/${encodeURIComponent(stationId)}/programming/preview`, {
    method: "POST",
    body: JSON.stringify(body),
    headers: withAdminHeaders({ "Content-Type": "application/json" }),
  });
}
