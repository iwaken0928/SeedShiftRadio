package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.ScriptGenerationService;
import com.seedshiftradio.radio.ScriptDirectiveSnapshot;

@ExtendWith(MockitoExtension.class)
class AssetServiceTests {

	@TempDir
	Path tempDir;

	@Mock
	RadioSettingsStore settingsStore;

	@Mock
	ProviderRegistry providerRegistry;

	@Mock
	TtsProvider ttsProvider;

	@Mock
	ScriptGenerationService scriptGenerationService;

	@Mock
	GeneratedAssetService generatedAssetService;

	@Mock
	ProviderJobService providerJobService;

	@Mock
	PlaceholderAudioFactory placeholderAudioFactory;

	@Mock
	TtsRuntimeProfileResolver ttsRuntimeProfileResolver;

	AssetService assetService;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient().when(settingsStore.load()).thenReturn(settingsDocument());
		SpeechAssetGenerationService speechAssetGenerationService = new SpeechAssetGenerationService(
				settingsStore,
				providerRegistry,
				ttsProvider,
				scriptGenerationService,
				generatedAssetService,
				providerJobService,
				ttsRuntimeProfileResolver);
		assetService = new AssetService(
				settingsStore,
				generatedAssetService,
				placeholderAudioFactory,
				speechAssetGenerationService);
	}

	@Test
	void loadAudioTouchesGeneratedAssetWhenFileExists() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument());
		Path assetPath = tempDir.resolve("data").resolve("assets").resolve("audio").resolve("asset-1.wav");
		Files.createDirectories(assetPath.getParent());
		byte[] bytes = "asset-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(assetPath, bytes);
		when(generatedAssetService.resolveLegacyAudioPath("asset-1")).thenReturn(assetPath);
		when(generatedAssetService.resolveAudioAssetPath("asset-1")).thenReturn(Optional.of(assetPath));

		byte[] loaded = assetService.loadAudio("asset-1");

		assertArrayEquals(bytes, loaded);
		verify(generatedAssetService).touchAsset("asset-1");
	}

	@Test
	void prepareMusicFailureFallbackUsesLocalMusicLibraryAssetWhenAvailable() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument());
		Path localMusic = tempDir.resolve("data").resolve("library").resolve("music").resolve("night-drive.wav");
		Files.createDirectories(localMusic.getParent());
		Files.write(localMusic, "wav".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-local-music");
		when(generatedAssetService.registerExistingAsset(
				eq(GeneratedAssetType.MUSIC),
				eq(localMusic.toAbsolutePath().normalize()),
				eq("server:music-library"),
				eq("queue-1"),
				nullable(String.class),
				anyMap())).thenReturn(asset);
		QueueItemEntity item = queueItem("queue-1", 45_000);

		AssetService.MusicFailureFallback fallback = assetService.prepareMusicFailureFallback(item, "PROVIDER_TIMEOUT");

		assertEquals(SegmentType.MUSIC_LOCAL, fallback.segmentType());
		assertEquals("asset-local-music", fallback.assetId());
		assertEquals("/api/assets/audio/asset-local-music.wav", fallback.assetUrl());
		assertEquals("MUSIC_LOCAL_FALLBACK", fallback.contentOrigin());
		assertEquals("ローカルBGM: night-drive", fallback.title());
	}

	@Test
	void prepareMusicFailureFallbackUsesPlaceholderJingleWhenLibraryIsUnavailable() {
		when(settingsStore.load()).thenReturn(settingsDocument());
		byte[] wav = "fallback-wav".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(placeholderAudioFactory.createFallbackWav(30_000)).thenReturn(wav);
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-fallback-jingle");
		when(generatedAssetService.createAudioAsset(
				eq(wav),
				eq("server:music-fallback"),
				eq("queue-2"),
				nullable(String.class),
				anyMap())).thenReturn(asset);
		QueueItemEntity item = queueItem("queue-2", 30_000);

		AssetService.MusicFailureFallback fallback = assetService.prepareMusicFailureFallback(item, "PROVIDER_TIMEOUT");

		assertEquals(SegmentType.JINGLE, fallback.segmentType());
		assertEquals("asset-fallback-jingle", fallback.assetId());
		assertEquals("/api/assets/audio/asset-fallback-jingle.wav", fallback.assetUrl());
		assertEquals("JINGLE_FALLBACK", fallback.contentOrigin());
		assertEquals("フォールバックジングル", fallback.title());
	}

	@Test
	void ensureQueueAudioAssetUsesLocalMusicAssetForMusicLocalItems() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument());
		Path localMusic = tempDir.resolve("data").resolve("library").resolve("music").resolve("night-drive.wav");
		Files.createDirectories(localMusic.getParent());
		Files.write(localMusic, "wav".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-local-music");
		when(generatedAssetService.registerExistingAsset(
				eq(GeneratedAssetType.MUSIC),
				eq(localMusic.toAbsolutePath().normalize()),
				eq("server:music-library"),
				eq("queue-3"),
				nullable(String.class),
				anyMap())).thenReturn(asset);
		QueueItemEntity item = newQueueItemEntity();
		item.setId("queue-3");
		item.setSegmentType(SegmentType.MUSIC_LOCAL);
		item.setDurationMs(45_000);
		item.setTitle("BGM");

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-local-music", item.getAssetId());
		assertEquals("/api/assets/audio/asset-local-music.wav", item.getAssetUrl());
		assertEquals("ローカルBGM: night-drive", item.getTitle());
		assertEquals("MUSIC_LOCAL_FALLBACK", item.getContentOrigin());
	}

	@Test
	void ensureQueueAudioAssetFallsBackToPlaceholderForMusicLocalItemsWithoutLibrary() {
		when(settingsStore.load()).thenReturn(settingsDocument());
		byte[] wav = "music-local-placeholder".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(placeholderAudioFactory.createFallbackWav(45_000)).thenReturn(wav);
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-music-local-placeholder");
		when(generatedAssetService.createAudioAsset(
				eq(wav),
				eq("server:music-local-placeholder"),
				eq("queue-4"),
				nullable(String.class),
				anyMap())).thenReturn(asset);
		QueueItemEntity item = newQueueItemEntity();
		item.setId("queue-4");
		item.setSegmentType(SegmentType.MUSIC_LOCAL);
		item.setDurationMs(45_000);
		item.setTitle("BGM");

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-music-local-placeholder", item.getAssetId());
		assertEquals("/api/assets/audio/asset-music-local-placeholder.wav", item.getAssetUrl());
		assertEquals("BGM", item.getTitle());
		assertEquals("MUSIC_LOCAL_PLACEHOLDER", item.getContentOrigin());
	}

	@Test
	void ensureQueueAudioAssetFallsBackFromIrodoriTimeoutToVoicevoxWithoutProfileContext() {
		QueueItemEntity item = ttsQueueItem("queue-tts-irodori-timeout");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(scriptSnapshot());
		TtsRuntimeProfile runtimeProfile = new TtsRuntimeProfile(
				"voice-night", "station-night", "irodori", "IRODORI_TTS", "night-main", "calm",
				new java.math.BigDecimal("1.10"), Map.of("responseFormat", "wav"), null, null);
		when(ttsRuntimeProfileResolver.resolve(item)).thenReturn(Optional.of(runtimeProfile));
		ProviderRegistry.ResolvedProvider irodori = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS, "tts", "irodori", "http://127.0.0.1:8088", "/health", 1_000,
				List.of("TTS_GEN", "IRODORI_TTS"), false);
		ProviderRegistry.ResolvedProvider voicevox = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS, "tts", "voicevox", "http://127.0.0.1:50021", "/version", 1_000,
				List.of("TTS_GEN", "VOICEVOX"), true);
		when(providerRegistry.resolveChain(ProviderType.TTS, "irodori")).thenReturn(List.of(irodori, voicevox));
		ProviderJobEntity irodoriJob = providerJob("provider-job-irodori-timeout");
		ProviderJobEntity voicevoxJob = providerJob("provider-job-voicevox-fallback");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN), eq(ProviderType.TTS), eq("irodori"),
				eq(item.getId()), eq(item.getCorrelationId()))).thenReturn(irodoriJob);
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN), eq(ProviderType.TTS), eq("voicevox"),
				eq(item.getId()), eq(item.getCorrelationId()))).thenReturn(voicevoxJob);
		when(ttsProvider.synthesize(eq(irodori), eq(item), any(), eq(runtimeProfile)))
				.thenThrow(new TtsSynthesisException(ProviderErrorCode.PROVIDER_TIMEOUT, "timeout"));
		byte[] wav = "voicevox-wav".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(ttsProvider.synthesize(eq(voicevox), eq(item), any(), nullable(TtsRuntimeProfile.class)))
				.thenReturn(new TtsProvider.SynthesizedAudio(wav, "voicevox:fingerprint", Map.of(
						"adapter", "VOICEVOX",
						"text", "本文です。",
						"voiceHint", "VOICEVOX:4:normal",
						"speakerKey", "4",
						"apiKey", "secret")));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-voicevox-fallback");
		when(generatedAssetService.createAudioAsset(
				eq(wav), eq("voicevox:fingerprint"), eq(item.getId()), eq(voicevoxJob.getId()),
				anyString(),
				argThat(metadata -> "PROVIDER_TIMEOUT".equals(metadata.get("fallbackErrorCode"))
						&& !metadata.containsKey("text")
						&& !metadata.containsKey("voiceHint")
						&& !metadata.containsKey("speakerKey")
						&& !metadata.containsKey("apiKey"))))
				.thenReturn(asset);

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-voicevox-fallback", item.getAssetId());
		verify(providerJobService).markFailed(irodoriJob.getId(), ProviderErrorCode.PROVIDER_TIMEOUT);
		verify(ttsProvider).synthesize(eq(voicevox), eq(item), any(), nullable(TtsRuntimeProfile.class));
	}

	@Test
	void ensureQueueAudioAssetUsesVoicevoxFallbackWhenRuntimeProfileConsentIsInvalid() {
		QueueItemEntity item = ttsQueueItem("queue-tts-invalid-consent");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(scriptSnapshot());
		TtsSynthesisException consentFailure = new TtsSynthesisException(
				ProviderErrorCode.VOICE_CONSENT_REQUIRED,
				"consent required");
		when(ttsRuntimeProfileResolver.resolve(item)).thenThrow(consentFailure);
		ProviderRegistry.ResolvedProvider irodori = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS, "tts", "irodori", "http://127.0.0.1:8088", "/health", 1_000,
				List.of("TTS_GEN", "IRODORI_TTS"), false);
		ProviderRegistry.ResolvedProvider voicevox = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS, "tts", "voicevox", "http://127.0.0.1:50021", "/version", 1_000,
				List.of("TTS_GEN", "VOICEVOX"), true);
		when(providerRegistry.resolveChain(ProviderType.TTS)).thenReturn(List.of(irodori, voicevox));
		ProviderJobEntity providerJob = providerJob("provider-job-safe-voicevox");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN), eq(ProviderType.TTS), eq("voicevox"),
				eq(item.getId()), eq(item.getCorrelationId()))).thenReturn(providerJob);
		byte[] wav = "voicevox-wav".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(ttsProvider.synthesize(eq(voicevox), eq(item), any(), nullable(TtsRuntimeProfile.class)))
				.thenReturn(new TtsProvider.SynthesizedAudio(wav, "voicevox:fingerprint", Map.of("adapter", "VOICEVOX")));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-safe-voicevox");
		when(generatedAssetService.createAudioAsset(
				eq(wav), eq("voicevox:fingerprint"), eq(item.getId()), eq(providerJob.getId()),
				anyString(),
				argThat(metadata -> Boolean.TRUE.equals(metadata.get("fallbackProviderUsed"))
						&& "VOICE_CONSENT_REQUIRED".equals(metadata.get("fallbackErrorCode")))))
				.thenReturn(asset);

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-safe-voicevox", item.getAssetId());
		verify(ttsProvider, never()).synthesize(eq(irodori), eq(item), any(), nullable(TtsRuntimeProfile.class));
		verify(ttsProvider).synthesize(eq(voicevox), eq(item), any(), nullable(TtsRuntimeProfile.class));
	}

	@Test
	void ensureQueueAudioAssetSkipsIncompatiblePreferredProviderAndUsesMatchingAdapter() {
		QueueItemEntity item = ttsQueueItem("queue-tts-profile");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(scriptSnapshot());
		TtsRuntimeProfile runtimeProfile = new TtsRuntimeProfile(
				"voice-night",
				"station-night",
				"voicevox",
				"IRODORI_TTS",
				"night-main",
				"calm",
				new java.math.BigDecimal("1.10"),
				Map.of("responseFormat", "wav"),
				null,
				null);
		when(ttsRuntimeProfileResolver.resolve(item)).thenReturn(Optional.of(runtimeProfile));
		ProviderRegistry.ResolvedProvider voicevox = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"voicevox",
				"http://127.0.0.1:50021",
				"/version",
				1_000,
				List.of("TTS_GEN", "VOICEVOX"),
				false);
		ProviderRegistry.ResolvedProvider irodori = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"irodori",
				"http://127.0.0.1:8088",
				"/health",
				1_000,
				List.of("TTS_GEN", "IRODORI_TTS"),
				false);
		when(providerRegistry.resolveChain(ProviderType.TTS, "voicevox")).thenReturn(List.of(voicevox, irodori));
		ProviderJobEntity providerJob = providerJob("provider-job-irodori");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("irodori"),
				eq(item.getId()),
				eq(item.getCorrelationId()))).thenReturn(providerJob);
		byte[] wav = "irodori-wav".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(ttsProvider.synthesize(eq(irodori), eq(item), any(), eq(runtimeProfile)))
				.thenReturn(new TtsProvider.SynthesizedAudio(wav, "irodori:fingerprint", Map.of("voiceProfileId", "voice-night")));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-irodori");
		when(generatedAssetService.createAudioAsset(
				eq(wav),
				eq("irodori:fingerprint"),
				eq(item.getId()),
				eq(providerJob.getId()),
				anyString(),
				argThat(metadata -> "voice-night".equals(metadata.get("voiceProfileId")))))
				.thenReturn(asset);

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-irodori", item.getAssetId());
		verify(ttsProvider, never()).synthesize(eq(voicevox), eq(item), any(), eq(runtimeProfile));
		verify(ttsProvider).synthesize(eq(irodori), eq(item), any(), eq(runtimeProfile));
		verify(providerJobService).markSucceeded(providerJob.getId());
	}

	@Test
	void ensureQueueAudioAssetFallsBackToPlaceholderWhenTtsProviderFails() {
		when(settingsStore.load()).thenReturn(settingsDocument());
		ProviderRegistry.ResolvedProvider voicevoxProvider = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"voicevox",
				"http://127.0.0.1:50021",
				"/version",
				1_000,
				List.of("TTS_GEN", "VOICEVOX"),
				false);
		when(ttsRuntimeProfileResolver.resolve(any())).thenReturn(Optional.empty());
		when(providerRegistry.resolveChain(ProviderType.TTS, null)).thenReturn(List.of(voicevoxProvider));
		QueueItemEntity item = newQueueItemEntity();
		item.setId("queue-tts-fallback");
		item.setSessionId("session-tts");
		item.setSegmentType(SegmentType.TALK);
		item.setSlotRole(SlotRole.TOPIC);
		item.setDurationMs(10_000);
		item.setCorrelationId("corr-tts-fallback");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(new ScriptDirectiveSnapshot(
				"本文です。",
				"本文です。",
				List.of(),
				"calm",
				"medium",
				List.of(),
				"persona-night-main",
				"VOICEVOX:4:normal",
				List.of()));
		ProviderJobEntity failedJob = providerJob("provider-job-failed");
		ProviderJobEntity placeholderJob = providerJob("provider-job-placeholder");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("voicevox"),
				eq("queue-tts-fallback"),
				eq("corr-tts-fallback"))).thenReturn(failedJob);
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("seedshift-placeholder"),
				eq("queue-tts-fallback"),
				eq("corr-tts-fallback"))).thenReturn(placeholderJob);
		when(ttsProvider.synthesize(eq(voicevoxProvider), eq(item), any(), nullable(TtsRuntimeProfile.class)))
				.thenThrow(new TtsSynthesisException("PROVIDER_TIMEOUT", "timeout"));
		byte[] wav = "placeholder-wav".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(ttsProvider.synthesize(argThat(provider -> "seedshift-placeholder".equals(provider.providerKey())), eq(item), any(), nullable(TtsRuntimeProfile.class)))
				.thenReturn(new TtsProvider.SynthesizedAudio(wav, "seedshift-placeholder:placeholder", Map.of("placeholder", true)));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-tts-placeholder");
		when(generatedAssetService.createAudioAsset(
				eq(wav),
				eq("seedshift-placeholder:placeholder"),
				eq("queue-tts-fallback"),
				eq("provider-job-placeholder"),
				anyString(),
				argThat(metadata -> Boolean.TRUE.equals(metadata.get("fallbackProviderUsed"))
						&& "PROVIDER_TIMEOUT".equals(metadata.get("fallbackErrorCode")))))
				.thenReturn(asset);

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-tts-placeholder", item.getAssetId());
		assertEquals("/api/assets/audio/asset-tts-placeholder.wav", item.getAssetUrl());
		assertEquals("PLACEHOLDER", item.getContentOrigin());
		verify(providerJobService).markFailed("provider-job-failed", ProviderErrorCode.PROVIDER_TIMEOUT);
		verify(providerJobService).markSucceeded("provider-job-placeholder");
	}

	@ParameterizedTest
	@EnumSource(value = ProviderErrorCode.class, names = {
			"PROVIDER_REJECTED",
			"PROVIDER_AUTH_FAILED",
			"PROVIDER_INTERRUPTED"
	})
	void ensureQueueAudioAssetSkipsExternalFallbackAndUsesPlaceholderForNonRecoverableTtsFailure(ProviderErrorCode errorCode) {
		when(settingsStore.load()).thenReturn(settingsDocument());
		ProviderRegistry.ResolvedProvider primary = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"primary",
				"http://127.0.0.1:50021",
				"/health",
				1_000,
				List.of("TTS_GEN"),
				false);
		ProviderRegistry.ResolvedProvider fallback = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"fallback",
				"http://127.0.0.1:50022",
				"/health",
				1_000,
				List.of("TTS_GEN"),
				true);
		when(ttsRuntimeProfileResolver.resolve(any())).thenReturn(Optional.empty());
		when(providerRegistry.resolveChain(ProviderType.TTS, null)).thenReturn(List.of(primary, fallback));
		QueueItemEntity item = ttsQueueItem("queue-tts-rejected");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(scriptSnapshot());
		ProviderJobEntity failedJob = providerJob("provider-job-rejected");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("primary"),
				eq(item.getId()),
				eq(item.getCorrelationId()))).thenReturn(failedJob);
		ProviderJobEntity placeholderJob = providerJob("provider-job-non-recoverable-placeholder");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("seedshift-placeholder"),
				eq(item.getId()),
				eq(item.getCorrelationId()))).thenReturn(placeholderJob);
		TtsSynthesisException expected = new TtsSynthesisException(errorCode, "non-recoverable");
		when(ttsProvider.synthesize(eq(primary), eq(item), any(), nullable(TtsRuntimeProfile.class))).thenThrow(expected);
		byte[] wav = "safe-local-placeholder".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(ttsProvider.synthesize(argThat(provider -> "seedshift-placeholder".equals(provider.providerKey())), eq(item), any(), nullable(TtsRuntimeProfile.class)))
				.thenReturn(new TtsProvider.SynthesizedAudio(wav, "seedshift-placeholder:placeholder", Map.of("placeholder", true)));
		GeneratedAssetEntity asset = new GeneratedAssetEntity();
		asset.setId("asset-non-recoverable-placeholder");
		when(generatedAssetService.createAudioAsset(
				eq(wav),
				eq("seedshift-placeholder:placeholder"),
				eq(item.getId()),
				eq(placeholderJob.getId()),
				anyString(),
				argThat(metadata -> Boolean.TRUE.equals(metadata.get("fallbackProviderUsed"))
						&& errorCode.name().equals(metadata.get("fallbackErrorCode")))))
				.thenReturn(asset);

		assetService.ensureQueueAudioAsset(item);

		assertEquals("asset-non-recoverable-placeholder", item.getAssetId());
		assertEquals("PLACEHOLDER", item.getContentOrigin());
		verify(providerJobService).markFailed("provider-job-rejected", errorCode);
		verify(ttsProvider, never()).synthesize(eq(fallback), eq(item), any(), nullable(TtsRuntimeProfile.class));
		verify(providerJobService, never()).createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("fallback"),
				eq(item.getId()),
				eq(item.getCorrelationId()));
		verify(providerJobService).markSucceeded("provider-job-non-recoverable-placeholder");
	}

	@Test
	void ensureQueueAudioAssetRethrowsNonRecoverableFailureWhenPlaceholderIsDisabled() {
		when(settingsStore.load()).thenReturn(settingsDocument(false));
		ProviderRegistry.ResolvedProvider primary = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"primary",
				"http://127.0.0.1:50021",
				"/health",
				1_000,
				List.of("TTS_GEN"),
				false);
		ProviderRegistry.ResolvedProvider fallback = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"fallback",
				"http://127.0.0.1:50022",
				"/health",
				1_000,
				List.of("TTS_GEN"),
				true);
		when(ttsRuntimeProfileResolver.resolve(any())).thenReturn(Optional.empty());
		when(providerRegistry.resolveChain(ProviderType.TTS, null)).thenReturn(List.of(primary, fallback));
		QueueItemEntity item = ttsQueueItem("queue-tts-placeholder-disabled");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(scriptSnapshot());
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("primary"),
				eq(item.getId()),
				eq(item.getCorrelationId()))).thenReturn(providerJob("provider-job-placeholder-disabled"));
		TtsSynthesisException expected = new TtsSynthesisException(ProviderErrorCode.PROVIDER_REJECTED, "rejected");
		when(ttsProvider.synthesize(eq(primary), eq(item), any(), nullable(TtsRuntimeProfile.class))).thenThrow(expected);

		TtsSynthesisException actual = assertThrows(
				TtsSynthesisException.class,
				() -> assetService.ensureQueueAudioAsset(item));

		assertEquals(expected, actual);
		verify(ttsProvider, never()).synthesize(eq(fallback), eq(item), any(), nullable(TtsRuntimeProfile.class));
	}

	@Test
	void ensureQueueAudioAssetReusesCachedSpeechAssetWithScriptTraceWithoutCallingProvider() {
		QueueItemEntity item = ttsQueueItem("queue-tts-cache-hit");
		when(scriptGenerationService.ensureScriptAsset(item)).thenReturn(scriptSnapshot());
		GeneratedAssetEntity scriptAsset = new GeneratedAssetEntity();
		scriptAsset.setId("script-asset-1");
		scriptAsset.setProviderJobId("script-job-1");
		when(generatedAssetService.findLatestScriptAssetForQueueItem(item.getId())).thenReturn(Optional.of(scriptAsset));
		when(ttsRuntimeProfileResolver.resolve(item)).thenReturn(Optional.empty());
		ProviderRegistry.ResolvedProvider voicevox = new ProviderRegistry.ResolvedProvider(
				ProviderType.TTS,
				"tts",
				"voicevox",
				"http://127.0.0.1:50021",
				"/version",
				1_000,
				List.of("TTS_GEN", "VOICEVOX"),
				"VOICEVOX",
				null,
				null,
				Map.of(),
				false);
		when(providerRegistry.resolveChain(ProviderType.TTS, null)).thenReturn(List.of(voicevox));
		GeneratedAssetEntity cached = new GeneratedAssetEntity();
		cached.setId("audio-cache-source");
		when(generatedAssetService.findReusableAsset(eq(GeneratedAssetType.AUDIO), anyString()))
				.thenReturn(Optional.of(cached));
		ProviderJobEntity cacheJob = providerJob("tts-cache-job");
		when(providerJobService.createQueuedJob(
				eq(ProviderJobType.TTS_GEN),
				eq(ProviderType.TTS),
				eq("voicevox"),
				eq(item.getId()),
				eq(item.getCorrelationId()))).thenReturn(cacheJob);
		GeneratedAssetEntity cloned = new GeneratedAssetEntity();
		cloned.setId("audio-cache-clone");
		when(generatedAssetService.cloneAssetForQueue(
				eq(cached),
				eq(item.getId()),
				eq(cacheJob.getId()),
				anyString(),
				argThat(metadata -> Boolean.TRUE.equals(metadata.get("cacheHit"))
						&& "script-asset-1".equals(metadata.get("scriptAssetId"))
						&& "script-job-1".equals(metadata.get("scriptProviderJobId"))
						&& metadata.containsKey("normalizedTextHash")
						&& metadata.containsKey("voiceHintHash")
						&& !metadata.containsKey("normalizedText")
						&& !metadata.containsKey("voiceHint"))))
				.thenReturn(cloned);

		assetService.ensureQueueAudioAsset(item);

		assertEquals("audio-cache-clone", item.getAssetId());
		assertEquals("/api/assets/audio/audio-cache-clone.wav", item.getAssetUrl());
		assertEquals("CACHE_REUSED", item.getContentOrigin());
		verify(providerJobService).markRunning("tts-cache-job", "voicevox", "cache-hit:audio-cache-source");
		verify(providerJobService).markSucceeded("tts-cache-job");
		verify(ttsProvider, never()).synthesize(any(), eq(item), any(), nullable(TtsRuntimeProfile.class));
	}

	private QueueItemEntity queueItem(String id, int durationMs) {
		QueueItemEntity item = org.mockito.Mockito.mock(QueueItemEntity.class);
		when(item.getId()).thenReturn(id);
		org.mockito.Mockito.lenient().when(item.getDurationMs()).thenReturn(durationMs);
		return item;
	}

	private QueueItemEntity newQueueItemEntity() {
		try {
			Constructor<QueueItemEntity> constructor = QueueItemEntity.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("QueueItemEntity を生成できません", exception);
		}
	}

	private QueueItemEntity ttsQueueItem(String id) {
		QueueItemEntity item = newQueueItemEntity();
		item.setId(id);
		item.setSessionId("session-tts");
		item.setSegmentType(SegmentType.TALK);
		item.setSlotRole(SlotRole.TOPIC);
		item.setDurationMs(10_000);
		item.setCorrelationId("corr-" + id);
		return item;
	}

	private ScriptDirectiveSnapshot scriptSnapshot() {
		return new ScriptDirectiveSnapshot(
				"本文です。",
				"本文です。",
				List.of(),
				"calm",
				"medium",
				List.of(),
				"persona-night-main",
				"VOICEVOX:4:normal",
				List.of());
	}

	private ProviderJobEntity providerJob(String id) {
		ProviderJobEntity entity = new ProviderJobEntity();
		entity.setId(id);
		return entity;
	}

	private SettingsDocument settingsDocument() {
		return settingsDocument(true);
	}

	private SettingsDocument settingsDocument(boolean placeholderEnabled) {
		return new SettingsDocument(
				1,
				"2026-04",
				Instant.parse("2026-03-20T09:00:00Z"),
				SettingsDocument.ServerSettings.defaults(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				SettingsDocument.PlayoutSettings.defaults(),
				SettingsDocument.CacheSettings.defaults(),
				SettingsDocument.ProgrammingSettings.defaults(),
				SettingsDocument.ProviderCatalog.defaults(),
				SettingsDocument.SecuritySettings.defaults(),
				new SettingsDocument.FeatureSettings(new SettingsDocument.StreamingFeatureSettings(placeholderEnabled)))
				.normalize();
	}
}
