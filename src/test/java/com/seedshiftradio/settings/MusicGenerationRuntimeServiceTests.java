package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.programming.StationProgrammingPolicyEntity;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;
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

	@Mock
	StationProgrammingPolicyRepository programmingPolicyRepository;

	MusicGenerationRuntimeService musicGenerationRuntimeService;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		musicGenerationRuntimeService = new MusicGenerationRuntimeService(
				musicGenWorkerGateway,
				providerJobService,
				generatedAssetService,
				settingsStore,
				stationRepository,
				programmingPolicyRepository);
		lenient().when(settingsStore.load()).thenReturn(SettingsDocument.defaults().normalize());
		lenient().when(programmingPolicyRepository.findByStationId(anyString())).thenReturn(Optional.empty());
	}

	@Test
	void generateReusesCachedMusicAssetBeforeCallingWorker() {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		ProviderJobEntity providerJob = providerJob("provider-job-1");
		GeneratedAssetEntity cachedAsset = asset("asset-existing", "/tmp/music-existing.wav", "cache-key-1");
		GeneratedAssetEntity clonedAsset = asset("asset-cloned", "/tmp/music-existing.wav", "cache-key-1");
		cachedAsset.setMetadata(Map.of("promptHash", "prompt-hash-1", "duration", 28));
		clonedAsset.setMetadata(Map.of("promptHash", "prompt-hash-1", "duration", 28));
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
		assertEquals("CACHE_REUSED", response.contentOrigin());
		assertEquals(28, response.durationSec());
		verify(providerJobService).markRunning("provider-job-1", "ace-step", "cache-hit:asset-existing");
		verify(providerJobService).markSucceeded("provider-job-1");
		verify(musicGenWorkerGateway, never()).submitWithFallback(any(), any(MusicGenerationRequest.class));
		verify(musicGenWorkerGateway, never()).awaitCompletion(any(), anyString());
	}

	@Test
	void generateFallsBackToWorkerWhenNoReusableAssetExists() throws IOException {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		when(item.getDurationMs()).thenReturn(120_000);
		Path actualWav = writeSilentWav(tempDir.resolve("music-created.wav"), 2);
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
						actualWav.toString(),
						117,
						"ace-step:1.0",
						"prompt-hash-1",
						"lyrics-hash-1",
						null,
						"generated",
						"acestep-v15-turbo",
						"acestep-5Hz-lm-0.6B",
						"12345"));
		when(generatedAssetService.registerExistingAsset(
				eq(GeneratedAssetType.MUSIC),
				eq(actualWav),
				eq("ace-step:1.0"),
				eq("queue-1"),
				eq("provider-job-1"),
				anyString(),
				argThat(metadata -> Integer.valueOf(2).equals(metadata.get("duration"))
						&& Integer.valueOf(117).equals(metadata.get("workerDuration"))
						&& !metadata.containsKey("durationSec"))))
				.thenReturn(createdAsset);

		MusicGenerationRuntimeService.GeneratedMusicAsset response = musicGenerationRuntimeService.generate("station-night", item);

		assertEquals("asset-created", response.assetId());
		assertEquals("/api/assets/audio/asset-created.wav", response.assetUrl());
		assertEquals("provider-job-1", response.providerJobId());
		assertEquals("worker-job-1", response.workerJobId());
		assertEquals("LIVE_GEN", response.contentOrigin());
		assertEquals(2, response.durationSec());
		ArgumentCaptor<MusicGenerationRequest> requestCaptor = ArgumentCaptor.forClass(MusicGenerationRequest.class);
		verify(musicGenWorkerGateway).submitWithFallback(any(), requestCaptor.capture());
		assertTrue(requestCaptor.getValue().prompt().contains("complete the full song form within exactly 120 seconds"));
		assertTrue(requestCaptor.getValue().prompt().contains("begin the outro no later than 100 seconds"));
		assertTrue(requestCaptor.getValue().prompt().contains("resolve to a clear final tonic cadence"));
		assertTrue(requestCaptor.getValue().prompt().contains("final 6 seconds for a natural fade"));
		assertTrue(requestCaptor.getValue().lyrics().contains("[End: hold final chord and fade out completely]"));
		assertTrue(requestCaptor.getValue().lyrics().contains("[Verse 2]"));
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
		assertEquals("CACHE_REUSED", response.contentOrigin());
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
						"lyrics-hash-1",
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
		assertEquals("LIVE_GEN", response.contentOrigin());
		verify(generatedAssetService, never()).findReusableAsset(any(), anyString());
	}

	@Test
	void generateSkipsCacheLookupWhenPreferCacheReuseIsFalse() {
		QueueItemEntity item = queueItem("queue-1", "corr-1");
		ProviderJobEntity providerJob = providerJob("provider-job-1");
		GeneratedAssetEntity createdAsset = asset("asset-created", "/tmp/music-created.wav", "cache-key-1");
		MusicGenWorkerGateway.ResolvedMusicProvider provider = new MusicGenWorkerGateway.ResolvedMusicProvider(
				"ace-step",
				"http://127.0.0.1:8000",
				5_000,
				List.of("MUSIC_GEN"));
		StationProgrammingPolicyEntity policy = programmingPolicy(false);

		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station("station-night")));
		when(programmingPolicyRepository.findByStationId("station-night"))
				.thenReturn(Optional.of(policy));
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
						"lyrics-hash-1",
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
		assertEquals("LIVE_GEN", response.contentOrigin());
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

	private Path writeSilentWav(Path path, int durationSec) throws IOException {
		AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);
		long frameLength = (long) format.getFrameRate() * durationSec;
		byte[] samples = new byte[Math.toIntExact(frameLength * format.getFrameSize())];
		try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(samples), format, frameLength)) {
			AudioSystem.write(audio, AudioFileFormat.Type.WAVE, path.toFile());
		}
		return path;
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

	private StationProgrammingPolicyEntity programmingPolicy(boolean preferCacheReuse) {
		StationProgrammingPolicyEntity entity = org.mockito.Mockito.mock(StationProgrammingPolicyEntity.class);
		org.mockito.Mockito.doReturn(Map.of(
				"mode", "ASSISTED",
				"maxPreparedMinutes", 12,
				"maxPreparedBlocks", 2,
				"preferCacheReuse", preferCacheReuse)).when(entity).getPreGenerationPolicy();
		return entity;
	}
}
