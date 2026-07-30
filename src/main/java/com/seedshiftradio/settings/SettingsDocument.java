package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SettingsDocument(
		Integer version,
		String schemaVersion,
		Instant updatedAt,
		ServerSettings server,
		PathSettings paths,
		PlayoutSettings playout,
		CacheSettings cache,
		ProgrammingSettings programming,
		ProviderCatalog providers,
		SecuritySettings security,
		FeatureSettings features) {

	public SettingsDocument normalize() {
		return new SettingsDocument(
				version == null || version < 1 ? 1 : version,
				(schemaVersion == null || schemaVersion.isBlank()) ? "2026-04" : schemaVersion,
				updatedAt == null ? Instant.now() : updatedAt,
				server == null ? ServerSettings.defaults() : server.normalize(),
				paths == null ? PathSettings.defaults() : paths.normalize(),
				playout == null ? PlayoutSettings.defaults() : playout.normalize(),
				cache == null ? CacheSettings.defaults() : cache.normalize(),
				programming == null ? ProgrammingSettings.defaults() : programming.normalize(),
				providers == null ? ProviderCatalog.defaults() : providers.normalize(),
				security == null ? SecuritySettings.defaults() : security.normalize(),
				features == null ? FeatureSettings.defaults() : features.normalize());
	}

	public SettingsDocument withVersionAndTimestamp(int newVersion, Instant timestamp) {
		return new SettingsDocument(
				newVersion,
				schemaVersion,
				timestamp,
				server,
				paths,
				playout,
				cache,
				programming,
				providers,
				security,
				features);
	}

	public static SettingsDocument defaults() {
		return new SettingsDocument(
				1,
				"2026-04",
				Instant.now(),
				ServerSettings.defaults(),
				PathSettings.defaults(),
				PlayoutSettings.defaults(),
				CacheSettings.defaults(),
				ProgrammingSettings.defaults(),
				ProviderCatalog.defaults(),
				SecuritySettings.defaults(),
				FeatureSettings.defaults());
	}

	public record ServerSettings(String bindHost, Integer port) {

		public ServerSettings normalize() {
			return new ServerSettings(
					(bindHost == null || bindHost.isBlank()) ? "127.0.0.1" : bindHost,
					(port == null || port < 1) ? 8080 : port);
		}

		static ServerSettings defaults() {
			return new ServerSettings("127.0.0.1", 8080);
		}
	}

	public record PathSettings(String dataRoot, String musicLibrary) {

		public PathSettings normalize() {
			return new PathSettings(
					(dataRoot == null || dataRoot.isBlank()) ? "./data" : dataRoot,
					(musicLibrary == null || musicLibrary.isBlank()) ? "./data/library/music" : musicLibrary);
		}

		static PathSettings defaults() {
			return new PathSettings("./data", "./data/library/music");
		}
	}

	public record PlayoutSettings(
			Integer targetReadyCount,
			Integer minimumReadyCount,
			Integer minReadyDurationMs,
			Integer maxPreparedDurationMs,
			Integer maxPreparedBlocks,
			Integer scriptAheadCount,
			Integer ttsAheadCount,
			Integer musicAheadCount,
			Boolean idlePrefetchEnabled) {

		public PlayoutSettings normalize() {
			return new PlayoutSettings(
					targetReadyCount == null || targetReadyCount < 1 ? 3 : targetReadyCount,
					minimumReadyCount == null || minimumReadyCount < 0 ? 2 : minimumReadyCount,
					minReadyDurationMs == null || minReadyDurationMs < 1 ? 90_000 : minReadyDurationMs,
					maxPreparedDurationMs == null || maxPreparedDurationMs < 1 ? 480_000 : maxPreparedDurationMs,
					maxPreparedBlocks == null || maxPreparedBlocks < 0 ? 2 : maxPreparedBlocks,
					scriptAheadCount == null || scriptAheadCount < 0 ? 4 : scriptAheadCount,
					ttsAheadCount == null || ttsAheadCount < 0 ? 3 : ttsAheadCount,
					musicAheadCount == null || musicAheadCount < 0 ? 2 : musicAheadCount,
					idlePrefetchEnabled == null ? Boolean.TRUE : idlePrefetchEnabled);
		}

		static PlayoutSettings defaults() {
			return new PlayoutSettings(3, 2, 90_000, 480_000, 2, 4, 3, 2, true);
		}
	}

	public record CacheSettings(
			Long scriptMaxBytes,
			Long ttsMaxBytes,
			Long musicMaxBytes,
			Integer scriptRetentionDays,
			Integer ttsRetentionDays,
			Integer musicRetentionDays,
			String scriptReuseScope,
			String ttsReuseScope,
			String musicReuseScope,
			Integer cleanupBatchSize) {

		public CacheSettings normalize() {
			return new CacheSettings(
					scriptMaxBytes == null || scriptMaxBytes < 1 ? 134_217_728L : scriptMaxBytes,
					ttsMaxBytes == null || ttsMaxBytes < 1 ? 536_870_912L : ttsMaxBytes,
					musicMaxBytes == null || musicMaxBytes < 1 ? 2_147_483_648L : musicMaxBytes,
					scriptRetentionDays == null || scriptRetentionDays < 0 ? 7 : scriptRetentionDays,
					ttsRetentionDays == null || ttsRetentionDays < 0 ? 30 : ttsRetentionDays,
					musicRetentionDays == null || musicRetentionDays < 0 ? 30 : musicRetentionDays,
					normalizeReuseScope(scriptReuseScope, "STATION"),
					normalizeReuseScope(ttsReuseScope, "STATION"),
					normalizeReuseScope(musicReuseScope, "GLOBAL"),
					cleanupBatchSize == null || cleanupBatchSize < 1 ? 200 : cleanupBatchSize);
		}

		static CacheSettings defaults() {
			return new CacheSettings(
					134_217_728L,
					536_870_912L,
					2_147_483_648L,
					7,
					30,
					30,
					"STATION",
					"STATION",
					"GLOBAL",
					200);
		}

		private static String normalizeReuseScope(String reuseScope, String fallback) {
			return (reuseScope == null || reuseScope.isBlank()) ? fallback : reuseScope.toUpperCase();
		}
	}

	public record ProgrammingSettings(Integer defaultPlanningHorizonMinutes, Boolean legacyRatioFallback, String seedImportRef) {

		public ProgrammingSettings normalize() {
			return new ProgrammingSettings(
					defaultPlanningHorizonMinutes == null || defaultPlanningHorizonMinutes < 1 ? 20 : defaultPlanningHorizonMinutes,
					legacyRatioFallback == null ? Boolean.TRUE : legacyRatioFallback,
					(seedImportRef == null || seedImportRef.isBlank()) ? "file:./data/config/programming-seed.json" : seedImportRef);
		}

		static ProgrammingSettings defaults() {
			return new ProgrammingSettings(20, true, "file:./data/config/programming-seed.json");
		}
	}

	public record ProviderCatalog(ProviderGroup llm, ProviderGroup tts, ProviderGroup musicGen) {

		public ProviderCatalog normalize() {
			return new ProviderCatalog(
					llm == null ? defaults().llm() : llm.normalize(120_000),
					tts == null ? defaults().tts() : tts.normalize(),
					musicGen == null ? defaults().musicGen() : musicGen.normalize());
		}

		static ProviderCatalog defaults() {
			return new ProviderCatalog(
					new ProviderGroup(
							"ollama",
							List.of(),
							Map.of("ollama", new ProviderEndpoint(
									"http://127.0.0.1:11434",
									"/api/tags",
									120_000,
									List.of("SCRIPT_GEN"),
									"OLLAMA",
									null,
									"qwen3:8b",
									null))),
					new ProviderGroup(
							"voicevox",
							List.of(),
							Map.of("voicevox", new ProviderEndpoint(
									"http://127.0.0.1:50021",
									"/version",
									5_000,
									List.of("TTS_GEN", "VOICEVOX"),
									"VOICEVOX",
									null,
									null,
									null))),
					new ProviderGroup(
							"ace-step",
							List.of(),
							Map.of("ace-step", ProviderEndpoint.aceStepDefaults())));
		}
	}

	public record ProviderGroup(String defaultProvider, List<String> fallbackProviders, Map<String, ProviderEndpoint> providers) {

		public ProviderGroup normalize() {
			return normalize(5_000);
		}

		private ProviderGroup normalize(int defaultTimeoutMs) {
			Map<String, ProviderEndpoint> normalizedProviders = providers == null || providers.isEmpty()
					? Map.of()
					: new LinkedHashMap<>(providers.entrySet().stream()
							.collect(java.util.stream.Collectors.toMap(
									Map.Entry::getKey,
									entry -> entry.getValue() == null ? ProviderEndpoint.defaults(defaultTimeoutMs) : entry.getValue().normalize(defaultTimeoutMs),
									(left, right) -> right,
									LinkedHashMap::new)));
			String normalizedDefault = (defaultProvider == null || defaultProvider.isBlank())
					? normalizedProviders.keySet().stream().findFirst().orElse("default")
					: defaultProvider;
			List<String> normalizedFallbacks = fallbackProviders == null
					? List.of()
					: fallbackProviders.stream()
							.filter(Objects::nonNull)
							.map(String::trim)
							.filter(value -> !value.isBlank())
							.distinct()
							.filter(value -> !value.equals(normalizedDefault))
							.toList();
			return new ProviderGroup(
					normalizedDefault,
					normalizedFallbacks,
					normalizedProviders);
		}
	}

	public record ProviderEndpoint(
			String baseUrl,
			String healthPath,
			Integer timeoutMs,
			List<String> capabilities,
			String adapter,
			String apiKeyRef,
			String defaultModelProfileId,
			Map<String, MusicGenerationModelProfile> modelProfiles) {

		public ProviderEndpoint(String baseUrl, String healthPath, Integer timeoutMs, List<String> capabilities) {
			this(baseUrl, healthPath, timeoutMs, capabilities, null, null, null, null);
		}

		public ProviderEndpoint normalize() {
			return normalize(5_000);
		}

		private ProviderEndpoint normalize(int defaultTimeoutMs) {
			Map<String, MusicGenerationModelProfile> normalizedProfiles = modelProfiles == null || modelProfiles.isEmpty()
					? Map.of()
					: new LinkedHashMap<>(modelProfiles.entrySet().stream()
							.collect(java.util.stream.Collectors.toMap(
									Map.Entry::getKey,
									entry -> entry.getValue() == null
											? MusicGenerationModelProfile.aceJaFast()
											: entry.getValue().normalize(),
									(left, right) -> right,
									LinkedHashMap::new)));
			String normalizedDefaultProfileId = defaultModelProfileId == null || defaultModelProfileId.isBlank()
					? normalizedProfiles.keySet().stream().findFirst().orElse(null)
					: defaultModelProfileId;
			return new ProviderEndpoint(
					(baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1" : baseUrl,
					(healthPath == null || healthPath.isBlank()) ? "/health" : healthPath,
					timeoutMs == null || timeoutMs < 100 ? defaultTimeoutMs : timeoutMs,
					capabilities == null ? List.of() : List.copyOf(capabilities),
					(adapter == null || adapter.isBlank()) ? null : adapter.toUpperCase(java.util.Locale.ROOT),
					apiKeyRef == null || apiKeyRef.isBlank() ? null : apiKeyRef,
					normalizedDefaultProfileId,
					normalizedProfiles);
		}

		static ProviderEndpoint defaults() {
			return defaults(5_000);
		}

		static ProviderEndpoint defaults(int timeoutMs) {
			return new ProviderEndpoint("http://127.0.0.1", "/health", timeoutMs, List.of());
		}

		static ProviderEndpoint aceStepDefaults() {
			return new ProviderEndpoint(
					"http://127.0.0.1:8001",
					"/health",
					10_000,
					List.of("MUSIC_GEN", "ACE_STEP", "JAPANESE_LYRICS"),
					"ACE_STEP",
					"env:ACESTEP_API_KEY",
					"ace-ja-fast",
					MusicGenerationModelProfile.defaultAceStepProfiles());
		}
	}

	public record MusicGenerationModelProfile(
			String model,
			String lmModel,
			Boolean thinking,
			String lyricsLanguage,
			String lyricsTransliterationMode,
			String outputFormat,
			Integer maxDurationSeconds) {

		public MusicGenerationModelProfile normalize() {
			return new MusicGenerationModelProfile(
					(model == null || model.isBlank()) ? "acestep-v15-turbo" : model,
					(lmModel == null || lmModel.isBlank()) ? "acestep-5Hz-lm-0.6B" : lmModel,
					thinking == null ? Boolean.TRUE : thinking,
					(lyricsLanguage == null || lyricsLanguage.isBlank()) ? "ja" : lyricsLanguage,
					normalizeTransliterationMode(lyricsTransliterationMode),
					(outputFormat == null || outputFormat.isBlank()) ? "wav" : outputFormat.toLowerCase(),
					maxDurationSeconds == null || maxDurationSeconds < 10 ? 120 : Math.min(maxDurationSeconds, 600));
		}

		static Map<String, MusicGenerationModelProfile> defaultAceStepProfiles() {
			Map<String, MusicGenerationModelProfile> profiles = new LinkedHashMap<>();
			profiles.put("ace-ja-fast", aceJaFast());
			profiles.put("ace-ja-balanced", new MusicGenerationModelProfile(
					"acestep-v15-turbo",
					"acestep-5Hz-lm-1.7B",
					true,
					"ja",
					"native",
					"wav",
					180));
			profiles.put("ace-ja-xl-fast", new MusicGenerationModelProfile(
					"acestep-v15-xl-turbo",
					"acestep-5Hz-lm-1.7B",
					true,
					"ja",
					"native",
					"wav",
					240));
			profiles.put("ace-ja-xl-quality", new MusicGenerationModelProfile(
					"acestep-v15-xl-sft",
					"acestep-5Hz-lm-4B",
					true,
					"ja",
					"native",
					"wav",
					300));
			return profiles;
		}

		static MusicGenerationModelProfile aceJaFast() {
			return new MusicGenerationModelProfile(
					"acestep-v15-turbo",
					"acestep-5Hz-lm-0.6B",
					true,
					"ja",
					"native",
					"wav",
					120);
		}

		private static String normalizeTransliterationMode(String value) {
			if (value == null || value.isBlank()) {
				return "native";
			}
			String normalized = value.toLowerCase();
			return switch (normalized) {
				case "native", "kana", "romaji" -> normalized;
				default -> "native";
			};
		}
	}

	public record SecuritySettings(String adminTokenRef) {

		public SecuritySettings normalize() {
			return new SecuritySettings(
					(adminTokenRef == null || adminTokenRef.isBlank()) ? "env:SEEDSHIFT_ADMIN_TOKEN" : adminTokenRef);
		}

		static SecuritySettings defaults() {
			return new SecuritySettings("env:SEEDSHIFT_ADMIN_TOKEN");
		}
	}

	public record FeatureSettings(
			StreamingFeatureSettings streaming,
			JobExecutionSettings jobExecution) {

		public FeatureSettings normalize() {
			return new FeatureSettings(
					streaming == null ? StreamingFeatureSettings.defaults() : streaming.normalize(),
					jobExecution == null ? JobExecutionSettings.defaults() : jobExecution.normalize());
		}

		static FeatureSettings defaults() {
			return new FeatureSettings(
					StreamingFeatureSettings.defaults(),
					JobExecutionSettings.defaults());
		}
	}

	public record StreamingFeatureSettings(Boolean placeholderEnabled) {

		public StreamingFeatureSettings normalize() {
			return new StreamingFeatureSettings(placeholderEnabled == null ? Boolean.TRUE : placeholderEnabled);
		}

		static StreamingFeatureSettings defaults() {
			return new StreamingFeatureSettings(true);
		}
	}

	public record JobExecutionSettings(
			Boolean singleGpuMode,
			String resourceGroup,
			Boolean requireAceStepCpuOffload,
			JobExecutionPolicy manual,
			JobExecutionPolicy automatic) {

		public JobExecutionSettings normalize() {
			return new JobExecutionSettings(
					singleGpuMode == null ? Boolean.TRUE : singleGpuMode,
					(resourceGroup == null || resourceGroup.isBlank()) ? "gpu-0" : resourceGroup.trim(),
					requireAceStepCpuOffload == null ? Boolean.TRUE : requireAceStepCpuOffload,
					manual == null ? JobExecutionPolicy.manualDefaults() : manual.normalize(true),
					automatic == null ? JobExecutionPolicy.automaticDefaults() : automatic.normalize(false));
		}

		static JobExecutionSettings defaults() {
			return new JobExecutionSettings(
					true,
					"gpu-0",
					true,
					JobExecutionPolicy.manualDefaults(),
					JobExecutionPolicy.automaticDefaults());
		}
	}

	public record JobExecutionPolicy(
			String waitStrategy,
			Integer resourceWaitTimeoutSeconds,
			Integer providerIdleTimeoutSeconds,
			Integer modelLoadTimeoutSeconds,
			Integer jobTimeoutSeconds,
			Integer pollIntervalMillis,
			Boolean unloadOllamaBeforeMusic,
			Boolean waitForAceStepIdleBeforeLlm) {

		public JobExecutionPolicy normalize(boolean manual) {
			JobExecutionPolicy defaults = manual ? manualDefaults() : automaticDefaults();
			String normalizedStrategy = waitStrategy == null ? "" : waitStrategy.trim().toUpperCase();
			if (!"WAIT".equals(normalizedStrategy) && !"FAIL_FAST".equals(normalizedStrategy)) {
				normalizedStrategy = defaults.waitStrategy();
			}
			return new JobExecutionPolicy(
					normalizedStrategy,
					positiveOrDefault(resourceWaitTimeoutSeconds, defaults.resourceWaitTimeoutSeconds()),
					positiveOrDefault(providerIdleTimeoutSeconds, defaults.providerIdleTimeoutSeconds()),
					positiveOrDefault(modelLoadTimeoutSeconds, defaults.modelLoadTimeoutSeconds()),
					positiveOrDefault(jobTimeoutSeconds, defaults.jobTimeoutSeconds()),
					positiveOrDefault(pollIntervalMillis, defaults.pollIntervalMillis()),
					unloadOllamaBeforeMusic == null ? defaults.unloadOllamaBeforeMusic() : unloadOllamaBeforeMusic,
					waitForAceStepIdleBeforeLlm == null ? defaults.waitForAceStepIdleBeforeLlm() : waitForAceStepIdleBeforeLlm);
		}

		static JobExecutionPolicy manualDefaults() {
			return new JobExecutionPolicy("WAIT", 900, 900, 900, 1_800, 1_000, true, true);
		}

		static JobExecutionPolicy automaticDefaults() {
			return new JobExecutionPolicy("WAIT", 1_800, 900, 900, 1_800, 1_000, true, true);
		}

		private static Integer positiveOrDefault(Integer value, Integer defaultValue) {
			return value == null || value < 1 ? defaultValue : value;
		}
	}
}
