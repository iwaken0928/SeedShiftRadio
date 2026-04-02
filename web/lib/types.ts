export type PlayoutState = "IDLE" | "PREPARING" | "PLAYING" | "DEGRADED" | "STOPPED" | "ERROR";
export type PlaybackMode = "SERVER_AUDIO" | "CLIENT_TTS";
export type QueueItemStatus = "GENERATING" | "READY" | "PLAYING" | "DONE" | "FAILED" | "SKIPPED";
export type ProgramBlockStatus = "PLANNED" | "ACTIVE" | "DONE" | "FAILED";
export type ConstraintMode = "HARD" | "SOFT";
export type SlotRole = "OPENING" | "TOPIC" | "LETTER" | "MUSIC_BREAK" | "ENDING";
export type SegmentType = "TALK" | "LETTER" | "ANNOUNCEMENT" | "JINGLE" | "MUSIC_LOCAL" | "MUSIC_AI";
export type LetterStatus = "UNREAD" | "PENDING" | "ADOPTED" | "REPLIED";

export interface StationSummary {
  id: string;
  name: string;
  frequencyMHz: number;
  genre: string;
  isActive: boolean;
  programmingEnabled: boolean;
  defaultProgramTemplateId: string | null;
}

export interface StationProgrammingProfile {
  enabled: boolean;
  defaultTemplateId: string | null;
  fallbackStrategy: string;
  planningHorizonMinutes: number;
  preGeneration: PreGenerationProfile;
  replay: ReplayProfile;
  composition: CompositionProfile;
}

export interface StationDetail {
  id: string;
  name: string;
  frequencyMHz: number;
  genre: string;
  languagePersonaId: string;
  defaultVoiceProfileId: string;
  isActive: boolean;
  version: number;
  programming: StationProgrammingProfile;
}

export interface RadioStatus {
  sessionId: string | null;
  stationId: string | null;
  programBlockId: string | null;
  programTemplateId: string | null;
  programTitle: string | null;
  state: PlayoutState;
  currentItemId: string | null;
  bufferReadyCount: number;
  degraded: boolean;
  updatedAt: string;
  correlationId: string | null;
}

export interface QueueSnapshot {
  sessionId: string;
  stationId: string;
  items: QueueItem[];
  correlationId: string | null;
}

export interface QueueItem {
  id: string;
  programBlockId: string | null;
  programSlotId: string | null;
  slotRole: SlotRole;
  type: SegmentType;
  title: string;
  playbackMode: PlaybackMode;
  assetUrl: string | null;
  speechDirectiveId: string | null;
  durationMs: number;
  status: QueueItemStatus;
  correlationId: string;
  assetBanned: boolean;
}

export interface ProgramBlockSummary {
  id: string;
  stationId: string;
  templateId: string | null;
  templateVersion: number | null;
  title: string;
  status: ProgramBlockStatus;
  plannedDurationMs: number;
  remainingSlotCount: number;
  startedAt: string;
  slots: ProgramBlockSlot[];
  correlationId: string | null;
}

export interface ProgramBlockSlot {
  id: string;
  role: SlotRole;
  constraintMode: ConstraintMode;
  resolvedSegmentType: SegmentType;
  targetDurationMs: number;
  status: string;
  slotContext: Record<string, unknown>;
  title: string;
}

export interface SpeechDirective {
  id: string;
  text: string;
  normalizedText: string;
  pronunciationHints: PronunciationHint[];
  emotion: string;
  tempo: string;
  pauseHints: PauseHint[];
  personaRef: string | null;
  voiceHint: string | null;
  correlationId: string | null;
}

export interface PronunciationHint {
  surface: string;
  reading: string;
}

export interface PauseHint {
  position: number;
  durationMs: number;
}

export interface LetterSummary {
  id: string;
  stationId: string | null;
  radioName: string;
  subject: string;
  status: LetterStatus;
  adoptedInSessionId: string | null;
  createdAt: string;
  replies: LetterReplySummary[];
}

export interface LetterDetail extends LetterSummary {
  body: string;
  playHistory: PlayHistoryItem[];
}

export interface LetterReplySummary {
  id: string;
  replyText: string;
  createdAt: string;
}

export interface PlayHistoryItem {
  id: string;
  sessionId: string;
  stationId: string;
  queueItemId: string;
  programBlockId: string | null;
  programSlotId: string | null;
  letterId: string | null;
  segmentType: SegmentType;
  title: string;
  playbackMode: PlaybackMode;
  resultStatus: string;
  correlationId: string;
  playedAt: string;
}

export interface MonitorSummary {
  sessionId: string | null;
  stationId: string | null;
  state: PlayoutState;
  bufferReadyCount: number;
  pendingLetterCount: number;
  degraded: boolean;
  providerHealth: Record<string, ProviderHealthPayload>;
  updatedAt: string;
}

export interface ProviderHealthPayload {
  providerType: string;
  providerKey: string | null;
  status: "UP" | "DEGRADED" | "DOWN";
  lastCheckedAt: string | null;
  responseTimeMs: number | null;
  message: string;
  capabilities: string[];
  baseUrl: string | null;
}

export interface HealthResponse {
  status: "UP" | "DEGRADED" | "DOWN";
  checkedAt: string;
  stationCount: number;
  sessionCount: number;
  queueCount: number;
  latestEventId: string | null;
  currentSessionId: string | null;
  providerHealth: Record<string, ProviderHealthPayload>;
}

export interface SettingsResponse {
  version: number;
  schemaVersion: string;
  updatedAt: string;
  configPath: string;
  server: ServerSettings;
  paths: PathSettings;
  playout: PlayoutSettings;
  cache: CacheSettings;
  programming: ProgrammingSettings;
  providers: ProviderCatalog;
  security: SecuritySettings;
  features: FeatureSettings;
}

export interface ServerSettings {
  host: string;
  port: number;
  corsAllowedOrigins: string[];
}

export interface PathSettings {
  dataRoot: string;
  musicLibrary: string;
}

export interface PlayoutSettings {
  targetReadyCount: number;
  minimumReadyCount: number;
  minReadyDurationMs: number;
  maxPreparedDurationMs: number;
  maxPreparedBlocks: number;
  scriptAheadCount: number;
  ttsAheadCount: number;
  musicAheadCount: number;
}

export interface CacheSettings {
  scriptMaxBytes: number;
  ttsMaxBytes: number;
  musicMaxBytes: number;
  scriptRetentionDays: number;
  ttsRetentionDays: number;
  musicRetentionDays: number;
  scriptReuseScope: string;
  ttsReuseScope: string;
  musicReuseScope: string;
  cleanupBatchSize: number;
}

export interface ProgrammingSettings {
  defaultTemplateId: string | null;
  fallbackStrategy: string;
  planningHorizonMinutes: number;
}

export interface ProviderCatalog {
  llm: ProviderGroup;
  tts: ProviderGroup;
  musicGen: ProviderGroup;
}

export interface ProviderGroup {
  defaultProvider: string;
  fallbackProviders: string[];
  providers: Record<string, ProviderEndpoint>;
}

export interface ProviderEndpoint {
  baseUrl: string;
  healthPath: string;
  timeoutMs: number;
  capabilities: string[];
}

export interface SecuritySettings {
  adminTokenRequired: boolean;
  adminTokenRef: string | null;
}

export interface FeatureSettings {
  streaming: StreamingFeatureSettings;
}

export interface StreamingFeatureSettings {
  placeholderEnabled: boolean;
}

export interface PreGenerationProfile {
  mode: string;
  maxPreparedMinutes: number;
  maxPreparedBlocks: number;
  preferCacheReuse: boolean;
}

export interface ReplayProfile {
  intensity: string;
  eligibleSegmentTypes: SegmentType[];
  minimumAssetAgeHours: number;
  cooldownHours: number;
  maxReplaySharePercent: number;
  excludeLetterSegments: boolean;
}

export interface CompositionProfile {
  targetSegmentShares: Record<string, number>;
  maxConsecutiveTalkSegments: number;
  musicBreakIntervalMinutes: number;
  letterPriorityBoostThreshold: number;
  allowSoftFallbackRetiming: boolean;
}

export interface ConnectionsTestResponse {
  checkedAt: string;
  providers: Record<string, ProviderHealthPayload>;
}

export interface EventMessage<T = unknown> {
  id: string | null;
  event: string;
  data: T;
}

export interface ClientCapabilitiesRequest {
  clientId: string;
  clientType: string;
  supportsClientSideTts: boolean;
  supportedVoiceEngines: string[];
  preferredPlaybackMode: PlaybackMode;
  localVoiceProfiles: { engine: string; profileKey: string }[];
}

export interface ClientCapabilitiesResponse extends ClientCapabilitiesRequest {
  acceptedAt: string;
  correlationId: string | null;
}

export interface TuneRequest {
  stationId: string;
  requestedBy: string;
  resumePlayback: boolean;
}

export interface TuneResponse {
  sessionId: string;
  stationId: string;
  state: PlayoutState;
  queueWarmupStarted: boolean;
  correlationId: string | null;
}

export interface PlaybackEventRequest {
  sessionId: string;
  itemId: string;
  eventType: "SEGMENT_STARTED" | "SEGMENT_ENDED" | "SEGMENT_ERROR" | "PLAYBACK_STOPPED";
}

export interface PlaybackEventResponse {
  accepted: boolean;
}
