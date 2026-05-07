package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.ScriptGenerationService;

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

	AssetService assetService;

	@BeforeEach
	void setUp() {
		assetService = new AssetService(
				settingsStore,
				providerRegistry,
				ttsProvider,
				scriptGenerationService,
				generatedAssetService,
				providerJobService,
				placeholderAudioFactory);
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
		when(placeholderAudioFactory.createSilentWav(30_000)).thenReturn(wav);
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
		when(placeholderAudioFactory.createSilentWav(45_000)).thenReturn(wav);
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

	private SettingsDocument settingsDocument() {
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
				SettingsDocument.FeatureSettings.defaults())
				.normalize();
	}
}
