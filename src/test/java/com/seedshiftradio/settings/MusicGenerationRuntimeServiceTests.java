package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;

@ExtendWith(MockitoExtension.class)
class MusicGenerationRuntimeServiceTests {

	@Mock
	MusicGenWorkerGateway musicGenWorkerGateway;

	@Mock
	ProviderJobService providerJobService;

	@Mock
	GeneratedAssetService generatedAssetService;

	@Mock
	RadioSettingsStore settingsStore;

	@Mock
	StationRepository stationRepository;

	MusicGenerationRuntimeService musicGenerationRuntimeService;

	@BeforeEach
	void setUp() {
		musicGenerationRuntimeService = new MusicGenerationRuntimeService(
				musicGenWorkerGateway,
				providerJobService,
				generatedAssetService,
				settingsStore,
				stationRepository);
		lenient().when(settingsStore.load()).thenReturn(SettingsDocument.defaults().normalize());
	}

	@Test
	void generateReusesCachedMusicAssetBeforeCallingWorker() {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		ProviderJobEntity providerJob = providerJob("provider-job-1");
		GeneratedAssetEntity cachedAsset = asset("asset-existing", "/tmp/music-existing.wav", "cache-key-1");
		GeneratedAssetEntity clonedAsset = asset("asset-cloned", "/tmp/music-existing.wav", "cache-key-1");
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8000",
				5_000,
				List.of("MUSIC_GEN"));

		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station("station-night")));
		when(musicGenWorkerGateway.resolveProviders()).thenReturn(List.of(provider));
		when(providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				"ace-step",
				"queue-1",
				"corr-1")).thenReturn(providerJob);
		when(generatedAssetService.findReusableAsset(eq(GeneratedAssetType.MUSIC), anyString())).thenReturn(Optional.of(cachedAsset));
		when(generatedAssetService.cloneAssetForQueue(eq(cachedAsset), eq("queue-1"), eq("provider-job-1"), anyString(), any(Map.class)))
				.thenReturn(clonedAsset);

		MusicGenerationRuntimeService.GeneratedMusicAsset response = musicGenerationRuntimeService.generate("station-night", item);

		assertEquals("asset-cloned", response.assetId());
		assertEquals("/api/assets/audio/asset-cloned.wav", response.assetUrl());
		assertEquals("provider-job-1", response.providerJobId());
		assertNull(response.workerJobId());
		verify(providerJobService).markRunning("provider-job-1", "ace-step", "cache-hit:asset-existing");
		verify(providerJobService).markSucceeded("provider-job-1");
		verify(musicGenWorkerGateway, never()).submitWithFallback(any(), any(MusicGenerationRequest.class));
		verify(musicGenWorkerGateway, never()).awaitCompletion(any(), anyString());
	}

	@Test
	void generateFallsBackToWorkerWhenNoReusableAssetExists() {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		ProviderJobEntity providerJob = providerJob("provider-job-1");
		GeneratedAssetEntity createdAsset = asset("asset-created", "/tmp/music-created.wav", "cache-key-1");

		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station("station-night")));
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8000",
				5_000,
				List.of("MUSIC_GEN"));
		when(musicGenWorkerGateway.resolveProviders()).thenReturn(List.of(provider));
		when(providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				"ace-step",
				"queue-1",
				"corr-1")).thenReturn(providerJob);
		when(generatedAssetService.findReusableAsset(eq(GeneratedAssetType.MUSIC), anyString())).thenReturn(Optional.empty());
		when(musicGenWorkerGateway.submitWithFallback(any(), any(MusicGenerationRequest.class)))
				.thenReturn(new MusicGenWorkerGateway.SubmittedMusicJob("worker-job-1", "QUEUED", provider));
		when(musicGenWorkerGateway.awaitCompletion(eq(provider), eq("worker-job-1")))
				.thenReturn(new MusicGenWorkerGateway.MusicJobStatus(
						"worker-job-1",
						"SUCCEEDED",
						Path.of("/tmp/music-created.wav").toString(),
						30,
						"ace-step:1.0",
						"prompt-hash-1",
						null,
						"generated",
						"acestep-v15-turbo",
						"acestep-5Hz-lm-0.6B",
						"12345"));
		when(generatedAssetService.registerExistingAsset(
				eq(GeneratedAssetType.MUSIC),
				eq(Path.of("/tmp/music-created.wav")),
				eq("ace-step:1.0"),
				eq("queue-1"),
				eq("provider-job-1"),
				anyString(),
				any(Map.class)))
				.thenReturn(createdAsset);

		MusicGenerationRuntimeService.GeneratedMusicAsset response = musicGenerationRuntimeService.generate("station-night", item);

		assertEquals("asset-created", response.assetId());
		assertEquals("/api/assets/audio/asset-created.wav", response.assetUrl());
		assertEquals("provider-job-1", response.providerJobId());
		assertEquals("worker-job-1", response.workerJobId());
		verify(providerJobService).markRunning("provider-job-1", "ace-step", "worker-job-1");
		verify(providerJobService).markSucceeded("provider-job-1");
		verify(generatedAssetService, never()).cloneAssetForQueue(any(), anyString(), anyString(), anyString(), any(Map.class));
	}

	@Test
	void generateReusesCachedMusicAssetFromFallbackProviderBeforeCallingWorker() {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		ProviderJobEntity providerJob = providerJob("provider-job-1");
		GeneratedAssetEntity cachedAsset = asset("asset-fallback-cache", "/tmp/music-existing.wav", "cache-key-fallback");
		GeneratedAssetEntity clonedAsset = asset("asset-cloned", "/tmp/music-existing.wav", "cache-key-fallback");

		MusicGenWorkerGateway.ResolvedMusicProvider primaryProvider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step-primary",
				"http://127.0.0.1:8000",
				5_000,
				List.of("MUSIC_GEN"));
		MusicGenWorkerGateway.ResolvedMusicProvider fallbackProvider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step-fallback",
				"http://127.0.0.1:8001",
				5_000,
				List.of("MUSIC_GEN"));

		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station("station-night")));
		when(musicGenWorkerGateway.resolveProviders()).thenReturn(List.of(primaryProvider, fallbackProvider));
		when(providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				"ace-step-primary",
				"queue-1",
				"corr-1")).thenReturn(providerJob);
		when(generatedAssetService.findReusableAsset(eq(GeneratedAssetType.MUSIC), anyString()))
				.thenReturn(Optional.empty(), Optional.of(cachedAsset));
		when(generatedAssetService.cloneAssetForQueue(eq(cachedAsset), eq("queue-1"), eq("provider-job-1"), anyString(), any(Map.class)))
				.thenReturn(clonedAsset);

		MusicGenerationRuntimeService.GeneratedMusicAsset response = musicGenerationRuntimeService.generate("station-night", item);

		assertEquals("asset-cloned", response.assetId());
		assertEquals("provider-job-1", response.providerJobId());
		assertNull(response.workerJobId());
		assertTrue(response.assetUrl().endsWith("asset-cloned.wav"));
		verify(providerJobService).markRunning("provider-job-1", "ace-step-fallback", "cache-hit:asset-fallback-cache");
		verify(providerJobService).markSucceeded("provider-job-1");
		verify(musicGenWorkerGateway, never()).submitWithFallback(any(), any(MusicGenerationRequest.class));
		verify(musicGenWorkerGateway, never()).awaitCompletion(any(), anyString());
	}

	@Test
	void generateSkipsCacheLookupWhenMusicReuseScopeIsDisabled() {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		ProviderJobEntity providerJob = providerJob("provider-job-1");
		GeneratedAssetEntity createdAsset = asset("asset-created", "/tmp/music-created.wav", null);
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8000",
				5_000,
				List.of("MUSIC_GEN"));

		when(settingsStore.load()).thenReturn(settingsWithMusicReuseScope("DISABLED"));
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station("station-night")));
		when(musicGenWorkerGateway.resolveProviders()).thenReturn(List.of(provider));
		when(providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				"ace-step",
				"queue-1",
				"corr-1")).thenReturn(providerJob);
		when(musicGenWorkerGateway.submitWithFallback(any(), any(MusicGenerationRequest.class)))
				.thenReturn(new MusicGenWorkerGateway.SubmittedMusicJob("worker-job-1", "QUEUED", provider));
		when(musicGenWorkerGateway.awaitCompletion(eq(provider), eq("worker-job-1")))
				.thenReturn(new MusicGenWorkerGateway.MusicJobStatus(
						"worker-job-1",
						"SUCCEEDED",
						Path.of("/tmp/music-created.wav").toString(),
						30,
						"ace-step:1.0",
						"prompt-hash-1",
						null,
						"generated",
						"acestep-v15-turbo",
						"acestep-5Hz-lm-0.6B",
						"12345"));
		when(generatedAssetService.registerExistingAsset(
				eq(GeneratedAssetType.MUSIC),
				eq(Path.of("/tmp/music-created.wav")),
				eq("ace-step:1.0"),
				eq("queue-1"),
				eq("provider-job-1"),
				eq(null),
				any(Map.class)))
				.thenReturn(createdAsset);

		MusicGenerationRuntimeService.GeneratedMusicAsset response = musicGenerationRuntimeService.generate("station-night", item);

		assertEquals("asset-created", response.assetId());
		verify(generatedAssetService, never()).findReusableAsset(any(), anyString());
	}

	private QueueItemEntity queueItem(String id, String correlationId) {
		QueueItemEntity entity = org.mockito.Mockito.mock(QueueItemEntity.class);
		when(entity.getId()).thenReturn(id);
		when(entity.getCorrelationId()).thenReturn(correlationId);
		when(entity.getSegmentType()).thenReturn(SegmentType.MUSIC_AI);
		when(entity.getSlotRole()).thenReturn(SlotRole.MUSIC_BREAK);
		when(entity.getDurationMs()).thenReturn(30_000);
		return entity;
	}

	private ProviderJobEntity providerJob(String id) {
		ProviderJobEntity entity = new ProviderJobEntity();
		entity.setId(id);
		return entity;
	}

	private GeneratedAssetEntity asset(String id, String storagePath, String cacheKey) {
		GeneratedAssetEntity entity = new GeneratedAssetEntity();
		entity.setId(id);
		entity.setAssetType(GeneratedAssetType.MUSIC);
		entity.setStoragePath(storagePath);
		entity.setContentHash("hash-" + id);
		entity.setProviderFingerprint("ace-step:1.0");
		entity.setCacheKey(cacheKey);
		entity.setMetadata(Map.of("promptHash", "prompt-hash-1"));
		return entity;
	}

	private StationEntity station(String id) {
		return new StationEntity(
				id,
				"Midnight Echo",
				new BigDecimal("81.3"),
				"ambient",
				"persona-night-main",
				"voice-night-main",
				true,
				"tmpl-night-regular",
				true);
	}

	private SettingsDocument settingsWithMusicReuseScope(String musicReuseScope) {
		SettingsDocument defaults = SettingsDocument.defaults().normalize();
		return new SettingsDocument(
				defaults.version(),
				defaults.schemaVersion(),
				defaults.updatedAt(),
				defaults.server(),
				defaults.paths(),
				defaults.playout(),
				new SettingsDocument.CacheSettings(
						defaults.cache().scriptMaxBytes(),
						defaults.cache().ttsMaxBytes(),
						defaults.cache().musicMaxBytes(),
						defaults.cache().scriptRetentionDays(),
						defaults.cache().ttsRetentionDays(),
						defaults.cache().musicRetentionDays(),
						defaults.cache().scriptReuseScope(),
						defaults.cache().ttsReuseScope(),
						musicReuseScope,
						defaults.cache().cleanupBatchSize()),
				defaults.programming(),
				defaults.providers(),
				defaults.security(),
				defaults.features()).normalize();
	}
}
