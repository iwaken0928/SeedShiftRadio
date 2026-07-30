import type {
  CacheSettings,
  FeatureSettings,
  JobExecutionPolicy,
  JobExecutionSettings,
  MusicGenerationModelProfile,
  PathSettings,
  PlayoutSettings,
  ProgrammingSettings,
  ProviderCatalog,
  ProviderEndpoint,
  ProviderGroup,
  SecuritySettings,
  ServerSettings,
  SettingsResponse,
  SettingsUpdateRequest,
  StreamingFeatureSettings,
} from "@/lib/types";

const PROVIDER_GROUP_KEYS = ["llm", "tts", "musicGen"] as const;

export interface SettingsImportResult {
  draft: SettingsUpdateRequest;
  versionAdjusted: boolean;
}

export function buildSettingsExportPayload(draft: SettingsUpdateRequest): SettingsUpdateRequest {
  validateSettingsSecretRefs(draft);
  return cloneSettingsUpdateRequest(draft);
}

export function buildSettingsExportFilename(draft: SettingsUpdateRequest): string {
  const schema = draft.schemaVersion.replace(/[^a-zA-Z0-9._-]/g, "-");
  return `seedshift-radio-settings-${schema}-v${draft.version}.json`;
}

export function parseSettingsImportPayload(value: unknown, current: SettingsResponse): SettingsImportResult {
  const root = readObject(value, "settings JSON");
  const importedVersion = readInteger(root, "version", "version");
  const importedSchemaVersion = readString(root, "schemaVersion", "schemaVersion");

  if (importedSchemaVersion !== current.schemaVersion) {
    throw new Error(`schemaVersion が一致しません。現在は ${current.schemaVersion}、import JSON は ${importedSchemaVersion} です。`);
  }

  return {
    draft: {
      version: current.version,
      schemaVersion: current.schemaVersion,
      server: readServerSettings(root.server),
      paths: readPathSettings(root.paths),
      playout: readPlayoutSettings(root.playout),
      cache: readCacheSettings(root.cache),
      programming: readProgrammingSettings(root.programming),
      providers: readProviderCatalog(root.providers),
      security: readSecuritySettings(root.security),
      features: readFeatureSettings(root.features, current.features),
    },
    versionAdjusted: importedVersion !== current.version,
  };
}

function cloneSettingsUpdateRequest(draft: SettingsUpdateRequest): SettingsUpdateRequest {
  return {
    version: draft.version,
    schemaVersion: draft.schemaVersion,
    server: { ...draft.server },
    paths: { ...draft.paths },
    playout: { ...draft.playout },
    cache: { ...draft.cache },
    programming: { ...draft.programming },
    providers: cloneProviderCatalog(draft.providers),
    security: { ...draft.security },
    features: {
      streaming: { ...draft.features.streaming },
      jobExecution: {
        ...draft.features.jobExecution,
        manual: { ...draft.features.jobExecution.manual },
        automatic: { ...draft.features.jobExecution.automatic },
      },
    },
  };
}

function cloneProviderCatalog(catalog: ProviderCatalog): ProviderCatalog {
  return {
    llm: cloneProviderGroup(catalog.llm),
    tts: cloneProviderGroup(catalog.tts),
    musicGen: cloneProviderGroup(catalog.musicGen),
  };
}

function cloneProviderGroup(group: ProviderGroup): ProviderGroup {
  return {
    defaultProvider: group.defaultProvider,
    fallbackProviders: [...group.fallbackProviders],
    providers: Object.fromEntries(
      Object.entries(group.providers).map(([providerKey, endpoint]) => [providerKey, cloneProviderEndpoint(endpoint)]),
    ),
  };
}

function cloneProviderEndpoint(endpoint: ProviderEndpoint): ProviderEndpoint {
  const clone: ProviderEndpoint = {
    baseUrl: endpoint.baseUrl,
    healthPath: endpoint.healthPath,
    timeoutMs: endpoint.timeoutMs,
    capabilities: [...endpoint.capabilities],
  };
  if (endpoint.adapter !== undefined) {
    clone.adapter = endpoint.adapter;
  }
  if (endpoint.apiKeyRef !== undefined) {
    clone.apiKeyRef = endpoint.apiKeyRef;
  }
  if (endpoint.defaultModelProfileId !== undefined) {
    clone.defaultModelProfileId = endpoint.defaultModelProfileId;
  }
  if (endpoint.modelProfiles !== undefined) {
    clone.modelProfiles = cloneModelProfiles(endpoint.modelProfiles);
  }
  return clone;
}

function cloneModelProfiles(profiles: Record<string, MusicGenerationModelProfile>): Record<string, MusicGenerationModelProfile> {
  return Object.fromEntries(Object.entries(profiles).map(([profileId, profile]) => [profileId, { ...profile }]));
}

function readServerSettings(value: unknown): ServerSettings {
  const record = readObject(value, "server");
  return {
    bindHost: readString(record, "bindHost", "server.bindHost"),
    port: readInteger(record, "port", "server.port"),
  };
}

function readPathSettings(value: unknown): PathSettings {
  const record = readObject(value, "paths");
  return {
    dataRoot: readString(record, "dataRoot", "paths.dataRoot"),
    musicLibrary: readString(record, "musicLibrary", "paths.musicLibrary"),
  };
}

function readPlayoutSettings(value: unknown): PlayoutSettings {
  const record = readObject(value, "playout");
  return {
    targetReadyCount: readInteger(record, "targetReadyCount", "playout.targetReadyCount"),
    minimumReadyCount: readInteger(record, "minimumReadyCount", "playout.minimumReadyCount"),
    minReadyDurationMs: readInteger(record, "minReadyDurationMs", "playout.minReadyDurationMs"),
    maxPreparedDurationMs: readInteger(record, "maxPreparedDurationMs", "playout.maxPreparedDurationMs"),
    maxPreparedBlocks: readInteger(record, "maxPreparedBlocks", "playout.maxPreparedBlocks"),
    scriptAheadCount: readInteger(record, "scriptAheadCount", "playout.scriptAheadCount"),
    ttsAheadCount: readInteger(record, "ttsAheadCount", "playout.ttsAheadCount"),
    musicAheadCount: readInteger(record, "musicAheadCount", "playout.musicAheadCount"),
    idlePrefetchEnabled: readBoolean(record, "idlePrefetchEnabled", "playout.idlePrefetchEnabled"),
  };
}

function readCacheSettings(value: unknown): CacheSettings {
  const record = readObject(value, "cache");
  return {
    scriptMaxBytes: readInteger(record, "scriptMaxBytes", "cache.scriptMaxBytes"),
    ttsMaxBytes: readInteger(record, "ttsMaxBytes", "cache.ttsMaxBytes"),
    musicMaxBytes: readInteger(record, "musicMaxBytes", "cache.musicMaxBytes"),
    scriptRetentionDays: readInteger(record, "scriptRetentionDays", "cache.scriptRetentionDays"),
    ttsRetentionDays: readInteger(record, "ttsRetentionDays", "cache.ttsRetentionDays"),
    musicRetentionDays: readInteger(record, "musicRetentionDays", "cache.musicRetentionDays"),
    scriptReuseScope: readString(record, "scriptReuseScope", "cache.scriptReuseScope"),
    ttsReuseScope: readString(record, "ttsReuseScope", "cache.ttsReuseScope"),
    musicReuseScope: readString(record, "musicReuseScope", "cache.musicReuseScope"),
    cleanupBatchSize: readInteger(record, "cleanupBatchSize", "cache.cleanupBatchSize"),
  };
}

function readProgrammingSettings(value: unknown): ProgrammingSettings {
  const record = readObject(value, "programming");
  return {
    defaultPlanningHorizonMinutes: readInteger(record, "defaultPlanningHorizonMinutes", "programming.defaultPlanningHorizonMinutes"),
    legacyRatioFallback: readBoolean(record, "legacyRatioFallback", "programming.legacyRatioFallback"),
    seedImportRef: readString(record, "seedImportRef", "programming.seedImportRef"),
  };
}

function readProviderCatalog(value: unknown): ProviderCatalog {
  const record = readObject(value, "providers");
  return {
    llm: readProviderGroup(record.llm, "providers.llm"),
    tts: readProviderGroup(record.tts, "providers.tts"),
    musicGen: readProviderGroup(record.musicGen, "providers.musicGen"),
  };
}

function readProviderGroup(value: unknown, label: string): ProviderGroup {
  const record = readObject(value, label);
  const providers = readProviderEndpoints(record.providers, `${label}.providers`);
  const defaultProvider = readString(record, "defaultProvider", `${label}.defaultProvider`);
  const fallbackProviders = readStringArray(record, "fallbackProviders", `${label}.fallbackProviders`);

  if (!Object.hasOwn(providers, defaultProvider)) {
    throw new Error(`${label}.defaultProvider は providers に存在する key を指定してください。`);
  }
  const missingFallback = fallbackProviders.find((providerKey) => !Object.hasOwn(providers, providerKey));
  if (missingFallback) {
    throw new Error(`${label}.fallbackProviders に未登録 provider '${missingFallback}' が含まれています。`);
  }

  return {
    defaultProvider,
    fallbackProviders,
    providers,
  };
}

function readProviderEndpoints(value: unknown, label: string): Record<string, ProviderEndpoint> {
  const record = readObject(value, label);
  return Object.fromEntries(Object.entries(record).map(([providerKey, endpoint]) => [providerKey, readProviderEndpoint(endpoint, `${label}.${providerKey}`)]));
}

function readProviderEndpoint(value: unknown, label: string): ProviderEndpoint {
  const record = readObject(value, label);
  return {
    baseUrl: readString(record, "baseUrl", `${label}.baseUrl`),
    healthPath: readString(record, "healthPath", `${label}.healthPath`),
    timeoutMs: readInteger(record, "timeoutMs", `${label}.timeoutMs`),
    capabilities: readStringArray(record, "capabilities", `${label}.capabilities`),
    adapter: readOptionalString(record, "adapter", `${label}.adapter`),
    apiKeyRef: readNullableSecretRef(record, "apiKeyRef", `${label}.apiKeyRef`),
    defaultModelProfileId: readOptionalNullableString(record, "defaultModelProfileId", `${label}.defaultModelProfileId`),
    modelProfiles: readModelProfiles(record.modelProfiles ?? {}, `${label}.modelProfiles`),
  };
}

function readModelProfiles(value: unknown, label: string): Record<string, MusicGenerationModelProfile> {
  const record = readObject(value, label);
  return Object.fromEntries(Object.entries(record).map(([profileId, profile]) => [profileId, readModelProfile(profile, `${label}.${profileId}`)]));
}

function readModelProfile(value: unknown, label: string): MusicGenerationModelProfile {
  const record = readObject(value, label);
  return {
    model: readString(record, "model", `${label}.model`),
    lmModel: readString(record, "lmModel", `${label}.lmModel`),
    thinking: readBoolean(record, "thinking", `${label}.thinking`),
    lyricsLanguage: readString(record, "lyricsLanguage", `${label}.lyricsLanguage`),
    lyricsTransliterationMode: readString(record, "lyricsTransliterationMode", `${label}.lyricsTransliterationMode`),
    outputFormat: readString(record, "outputFormat", `${label}.outputFormat`),
    maxDurationSeconds: readInteger(record, "maxDurationSeconds", `${label}.maxDurationSeconds`),
  };
}

function readSecuritySettings(value: unknown): SecuritySettings {
  const record = readObject(value, "security");
  return {
    adminTokenRef: readNullableSecretRef(record, "adminTokenRef", "security.adminTokenRef"),
  };
}

function readFeatureSettings(value: unknown, fallback: FeatureSettings): FeatureSettings {
  const record = readObject(value, "features");
  return {
    streaming: readStreamingFeatureSettings(record.streaming),
    jobExecution: record.jobExecution === undefined
      ? {
          ...fallback.jobExecution,
          manual: { ...fallback.jobExecution.manual },
          automatic: { ...fallback.jobExecution.automatic },
        }
      : readJobExecutionSettings(record.jobExecution),
  };
}

function readStreamingFeatureSettings(value: unknown): StreamingFeatureSettings {
  const record = readObject(value, "features.streaming");
  return {
    placeholderEnabled: readBoolean(record, "placeholderEnabled", "features.streaming.placeholderEnabled"),
  };
}

function readJobExecutionSettings(value: unknown): JobExecutionSettings {
  const record = readObject(value, "features.jobExecution");
  return {
    singleGpuMode: readBoolean(record, "singleGpuMode", "features.jobExecution.singleGpuMode"),
    resourceGroup: readString(record, "resourceGroup", "features.jobExecution.resourceGroup"),
    requireAceStepCpuOffload: readBoolean(
      record,
      "requireAceStepCpuOffload",
      "features.jobExecution.requireAceStepCpuOffload",
    ),
    manual: readJobExecutionPolicy(record.manual, "features.jobExecution.manual"),
    automatic: readJobExecutionPolicy(record.automatic, "features.jobExecution.automatic"),
  };
}

function readJobExecutionPolicy(value: unknown, label: string): JobExecutionPolicy {
  const record = readObject(value, label);
  const waitStrategy = readString(record, "waitStrategy", `${label}.waitStrategy`);
  if (waitStrategy !== "WAIT" && waitStrategy !== "FAIL_FAST") {
    throw new Error(`${label}.waitStrategy は WAIT または FAIL_FAST である必要があります。`);
  }
  return {
    waitStrategy,
    resourceWaitTimeoutSeconds: readInteger(record, "resourceWaitTimeoutSeconds", `${label}.resourceWaitTimeoutSeconds`),
    providerIdleTimeoutSeconds: readInteger(record, "providerIdleTimeoutSeconds", `${label}.providerIdleTimeoutSeconds`),
    modelLoadTimeoutSeconds: readInteger(record, "modelLoadTimeoutSeconds", `${label}.modelLoadTimeoutSeconds`),
    jobTimeoutSeconds: readInteger(record, "jobTimeoutSeconds", `${label}.jobTimeoutSeconds`),
    pollIntervalMillis: readInteger(record, "pollIntervalMillis", `${label}.pollIntervalMillis`),
    unloadOllamaBeforeMusic: readBoolean(record, "unloadOllamaBeforeMusic", `${label}.unloadOllamaBeforeMusic`),
    waitForAceStepIdleBeforeLlm: readBoolean(
      record,
      "waitForAceStepIdleBeforeLlm",
      `${label}.waitForAceStepIdleBeforeLlm`,
    ),
  };
}

function readObject(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} は object である必要があります。`);
  }
  return value as Record<string, unknown>;
}

function readString(record: Record<string, unknown>, key: string, label: string): string {
  const value = record[key];
  if (typeof value !== "string") {
    throw new Error(`${label} は string である必要があります。`);
  }
  return value;
}

function readOptionalString(record: Record<string, unknown>, key: string, label: string): string | undefined {
  if (!Object.hasOwn(record, key) || record[key] === undefined) {
    return undefined;
  }
  return readString(record, key, label);
}

function readOptionalNullableString(record: Record<string, unknown>, key: string, label: string): string | null {
  if (!Object.hasOwn(record, key) || record[key] == null) {
    return null;
  }
  return readString(record, key, label);
}

function readNullableSecretRef(record: Record<string, unknown>, key: string, label: string): string | null {
  const value = readOptionalNullableString(record, key, label);
  return normalizeSecretRef(value, label);
}

function validateSettingsSecretRefs(draft: SettingsUpdateRequest) {
  normalizeSecretRef(draft.security.adminTokenRef, "security.adminTokenRef");

  for (const groupKey of PROVIDER_GROUP_KEYS) {
    const group = draft.providers[groupKey];
    for (const [providerKey, endpoint] of Object.entries(group.providers)) {
      normalizeSecretRef(endpoint.apiKeyRef ?? null, `providers.${groupKey}.providers.${providerKey}.apiKeyRef`);
    }
  }
}

function normalizeSecretRef(value: string | null, label: string): string | null {
  if (value === null) {
    return null;
  }
  if (value.trim() === "") {
    return null;
  }
  if (!value.startsWith("env:") && !value.startsWith("file:")) {
    throw new Error(`${label} は env: または file: で始まる参照値だけ指定できます。`);
  }
  return value;
}

function readInteger(record: Record<string, unknown>, key: string, label: string): number {
  const value = record[key];
  if (typeof value !== "number" || !Number.isInteger(value)) {
    throw new Error(`${label} は integer である必要があります。`);
  }
  return value;
}

function readBoolean(record: Record<string, unknown>, key: string, label: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") {
    throw new Error(`${label} は boolean である必要があります。`);
  }
  return value;
}

function readStringArray(record: Record<string, unknown>, key: string, label: string): string[] {
  const value = record[key];
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new Error(`${label} は string[] である必要があります。`);
  }
  return [...value];
}
