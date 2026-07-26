export type PlayoutState = "IDLE" | "PREPARING" | "PLAYING" | "DEGRADED" | "STOPPED" | "ERROR";
export type PlaybackMode = "SERVER_AUDIO" | "CLIENT_TTS";
export type QueueItemStatus = "PLANNED" | "GENERATING" | "READY" | "PLAYING" | "DONE" | "FAILED" | "SKIPPED";
export type ProgramBlockStatus = "PLANNED" | "ACTIVE" | "DONE" | "FAILED";
export type ConstraintMode = "HARD" | "SOFT";
export type SlotRole = "OPENING" | "TOPIC" | "LETTER" | "MUSIC_BREAK" | "ENDING";
export type SegmentType = "TALK" | "LETTER" | "JINGLE" | "MUSIC_LOCAL" | "MUSIC_AI";
export type ContentOrigin = "LIVE_GEN" | "CACHE_REUSED" | "PLACEHOLDER" | "ARCHIVE_REPLAY" | "LOCAL_LIBRARY";
export type LetterStatus = "UNREAD" | "PENDING" | "ADOPTED" | "REPLIED";
export type GeneratedAssetType = "SCRIPT" | "AUDIO" | "MUSIC";

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

export interface StationUpdateRequest {
  version: number;
  id: string;
  name: string;
  frequencyMHz: number;
  genre: string;
  languagePersonaId: string;
  defaultVoiceProfileId: string;
  isActive: boolean;
  programmingEnabled: boolean;
  defaultProgramTemplateId: string | null;
}

export interface StationResponse extends StationUpdateRequest {
  updatedAt: string;
}

export interface StationProgrammingResponse {
  stationId: string;
  version: number;
  enabled: boolean;
  defaultTemplateId: string | null;
  fallbackStrategy: string;
  planningHorizonMinutes: number;
  preGeneration: PreGenerationProfile;
  replay: ReplayProfile;
  composition: CompositionProfile;
  updatedAt: string;
  rules: ProgramRuleSummary[];
}

export interface StationProgrammingUpdateRequest {
  version: number;
  enabled: boolean;
  defaultTemplateId: string | null;
  fallbackStrategy: string;
  planningHorizonMinutes: number;
  preGeneration: PreGenerationProfile;
  replay: ReplayProfile;
  composition: CompositionProfile;
  rules: ProgramRuleUpdateRequest[];
}

export interface ProgramRuleSummary {
  id: string;
  priority: number;
  days: string[];
  startTime: string;
  endTime: string;
  minimumPendingLetters: number;
  requiredProviderStates: string[];
  templateId: string;
}

export interface ProgramRuleUpdateRequest {
  priority: number;
  days: string[];
  startTime: string;
  endTime: string;
  minimumPendingLetters: number;
  requiredProviderStates: string[];
  templateId: string;
}

export interface ProgramTemplateSummary {
  id: string;
  scope: string;
  stationId: string | null;
  name: string;
  version: number;
  targetDurationMinutes: number;
  planningHorizonMinutes: number;
  isActive: boolean;
  fallbackTemplateId: string | null;
}

export interface ProgramTemplateUpdateRequest {
  id: string;
  scope: string;
  stationId: string | null;
  name: string;
  version: number;
  targetDurationMinutes: number;
  planningHorizonMinutes: number;
  isActive: boolean;
  editorialPolicy: Record<string, unknown>;
  fallbackTemplateId: string | null;
  slots: ProgramTemplateSlot[];
}

export interface ProgramTemplateDetail extends ProgramTemplateSummary {
  editorialPolicy: Record<string, unknown>;
  slots: ProgramTemplateSlot[];
}

export interface ProgramTemplateSlot {
  slotId: string;
  role: SlotRole;
  constraintMode: ConstraintMode;
  candidateSegmentTypes: SegmentType[];
  fallbackSegmentTypes: SegmentType[];
  targetDurationMs: number;
  slotPolicy: Record<string, unknown>;
}

export interface ProgrammingPreviewProviderStates {
  musicGen: string;
  tts: string;
  llm: string;
}

export interface ProgrammingPreviewRequest {
  at: string;
  pendingLetterCount: number;
  providerStates: ProgrammingPreviewProviderStates;
  policyDraft?: StationProgrammingUpdateRequest | null;
  templateDraft?: ProgramTemplateUpdateRequest | null;
}

export interface ProgrammingPreviewProgram {
  title: string;
  plannedDurationMs: number;
}

export interface ProgrammingPreviewResponse {
  stationId: string;
  selectedTemplateId: string | null;
  fallbackApplied: boolean;
  program: ProgrammingPreviewProgram;
  slots: ProgrammingPreviewSlot[];
  validationWarnings: ValidationWarning[];
}

export interface ProgrammingPreviewSlot {
  slotId: string;
  role: SlotRole;
  constraintMode: ConstraintMode;
  targetDurationMs: number;
}

export interface ValidationWarning {
  code: string;
  message: string;
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
  contentOrigin: ContentOrigin;
  preparedAt: string | null;
  replayOfPlayHistoryId: string | null;
  letterId: string | null;
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
  slotId: string;
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

export interface LetterSubmissionRecord {
  id: string;
  stationId: string | null;
  radioName: string;
  subject: string;
  status: LetterStatus;
  createdAt: string;
}

export interface LetterPublicLookupResponse {
  letters: LetterPublicSummary[];
}

export interface LetterPublicSummary {
  id: string;
  stationId: string | null;
  radioName: string;
  subject: string;
  status: LetterStatus;
  adoptedInSessionId: string | null;
  createdAt: string;
  playHistory: LetterPublicPlayHistorySummary[];
}

export interface LetterPublicPlayHistorySummary {
  id: string;
  sessionId: string;
  stationId: string;
  segmentType: SegmentType;
  title: string;
  resultStatus: string;
  playedAt: string;
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
  contentOrigin: ContentOrigin;
  replayOfPlayHistoryId: string | null;
  playedAt: string;
}

export interface MonitorSummary {
  sessionId: string | null;
  stationId: string | null;
  state: PlayoutState;
  bufferReadyCount: number;
  queueReadyDurationMs: number;
  pendingLetterCount: number;
  degraded: boolean;
  providerHealth: Record<string, ProviderHealthPayload>;
  cache: CacheMetricsSnapshot;
  archive: ArchiveMetricsSnapshot;
  runningJobs: MonitorProviderJob[];
  recentErrors: MonitorProviderJob[];
  auditEvents: MonitorAuditEvent[];
  updatedAt: string;
}

export type PreGenerationRequestStatus = "QUEUED" | "RUNNING" | "MATERIALIZED" | "FAILED";

export interface PreGenerationResponse {
  id: string;
  stationId: string;
  sessionId: string;
  programTemplateId: string | null;
  targetProgramCount: number;
  includeSpeech: boolean;
  includeMusic: boolean;
  status: PreGenerationRequestStatus;
  materializedProgramCount: number;
  materializedSegmentCount: number;
  queuedMusicCount: number;
  errorCode: string | null;
  requestedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface StationContentInventory {
  stationId: string;
  stationName: string;
  active: boolean;
  programmingEnabled: boolean;
  applicableProgramTemplateCount: number;
  programCount: number;
  preGeneratedProgramCount: number;
  generatedAssetCount: number;
  generatedAssetBytes: number;
  scriptAssetCount: number;
  audioAssetCount: number;
  musicAssetCount: number;
  musicAssetBytes: number;
  latestProgramAt: string | null;
  latestAssetAt: string | null;
  latestPreGeneration: PreGenerationResponse | null;
}

export interface ManagementDashboardResponse {
  system: MonitorSummary;
  stationCount: number;
  activeStationCount: number;
  programTemplateCount: number;
  stations: StationContentInventory[];
  recentPreGenerations: PreGenerationResponse[];
  updatedAt: string;
}

export interface PreGenerationRequest {
  programTemplateId: string | null;
  targetProgramCount: number;
  includeSpeech: boolean;
  includeMusic: boolean;
}

export interface ArchiveMetricsSnapshot {
  eligibleArchiveCount: number;
  totalArchiveCount: number;
  archiveReplayCount: number;
  totalPlaybackCount: number;
  archiveReplayRate: number;
}

export interface CacheMetricsSnapshot {
  checkedAt: string;
  assetCount: number;
  byteSize: number;
  cacheHitCount: number;
  cacheHitRate: number;
  expiredAssetCount: number;
  byType: Record<GeneratedAssetType, CacheTypeMetrics>;
}

export interface CacheTypeMetrics {
  assetType: GeneratedAssetType;
  assetCount: number;
  byteSize: number;
  cacheHitCount: number;
  cacheHitRate: number;
}

export interface MonitorProviderJob {
  id: string;
  jobType: "SCRIPT_GEN" | "TTS_GEN" | "MUSIC_GEN";
  providerType: "LLM" | "TTS" | "MUSIC";
  providerKey: string | null;
  queueItemId: string | null;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  externalRef: string | null;
  errorCode: string | null;
  startedAt: string | null;
  endedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MonitorAuditEvent {
  id: string;
  eventType: string;
  occurredAt: string;
  summary: string;
}

export interface OperationalEvent {
  id: string;
  level: "INFO" | "WARN" | "ERROR";
  category: string;
  eventType: string;
  sourceId: string | null;
  correlationId: string | null;
  providerType: string | null;
  providerKey: string | null;
  errorCode: string | null;
  message: string;
  occurredAt: string;
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
  metadata?: Record<string, unknown> | null;
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

export interface SettingsUpdateRequest {
  version: number;
  schemaVersion: string;
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
  bindHost: string;
  port: number;
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
  idlePrefetchEnabled: boolean;
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
  defaultPlanningHorizonMinutes: number;
  legacyRatioFallback: boolean;
  seedImportRef: string;
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
  adapter?: string;
  apiKeyRef?: string | null;
  defaultModelProfileId?: string | null;
  modelProfiles?: Record<string, MusicGenerationModelProfile>;
}

export interface MusicGenerationModelProfile {
  model: string;
  lmModel: string;
  thinking: boolean;
  lyricsLanguage: string;
  lyricsTransliterationMode: string;
  outputFormat: string;
  maxDurationSeconds: number;
}

export interface SecuritySettings {
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
  clientId: string;
  sessionId: string;
  itemId: string;
  eventType: "SEGMENT_STARTED" | "SEGMENT_ENDED" | "SEGMENT_ERROR" | "PLAYBACK_STOPPED";
  occurredAt: string;
}

export interface PlaybackEventResponse {
  accepted: boolean;
}
