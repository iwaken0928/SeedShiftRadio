package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.GeneratedAssetType;

@ExtendWith(MockitoExtension.class)
class GeneratedAssetServiceTests {

	@TempDir
	Path tempDir;

	@Mock
	GeneratedAssetRepository generatedAssetRepository;

	@Mock
	RadioSettingsStore settingsStore;

	GeneratedAssetService generatedAssetService;

	@BeforeEach
	void setUp() {
		generatedAssetService = new GeneratedAssetService(generatedAssetRepository, settingsStore);
		when(generatedAssetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void createAudioAssetStoresLifecycleMetadata() {
		when(settingsStore.load()).thenReturn(settingsDocument(
				new SettingsDocument.CacheSettings(
						134_217_728L,
						536_870_912L,
						2_147_483_648L,
						7,
						3,
						30,
						"STATION",
						"SESSION",
						"GLOBAL",
						200)));

		byte[] bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		GeneratedAssetEntity asset = generatedAssetService.createAudioAsset(
				bytes,
				"voicevox:1",
				"queue-1",
				"provider-job-1",
				Map.of("archiveEligible", true));

		assertEquals(GeneratedAssetType.AUDIO, asset.getAssetType());
		assertEquals(bytes.length, asset.getByteSize());
		assertEquals("SESSION", asset.getReuseScope());
		assertEquals(0, asset.getReuseCount());
		assertNotNull(asset.getLastAccessedAt());
		assertNotNull(asset.getExpiresAt());
		assertTrue(asset.getExpiresAt().isAfter(Instant.now().plus(Duration.ofDays(2))));
		assertTrue(asset.getExpiresAt().isBefore(Instant.now().plus(Duration.ofDays(4))));
		assertTrue(asset.isArchiveEligible());
		verify(generatedAssetRepository).save(any(GeneratedAssetEntity.class));
	}

	@Test
	void registerExistingAssetUsesMusicLifecycleSettings() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument(
				new SettingsDocument.CacheSettings(
						134_217_728L,
						536_870_912L,
						2_147_483_648L,
						7,
						3,
						9,
						"STATION",
						"SESSION",
						"GLOBAL",
						200)));

		Path source = tempDir.resolve("source.wav");
		Files.write(source, "music-data".getBytes(java.nio.charset.StandardCharsets.UTF_8));

		GeneratedAssetEntity asset = generatedAssetService.registerExistingAsset(
				GeneratedAssetType.MUSIC,
				source,
				"ace-step:1.0",
				"queue-1",
				"provider-job-1",
				Map.of());

		assertEquals(GeneratedAssetType.MUSIC, asset.getAssetType());
		assertEquals(Files.size(source), asset.getByteSize());
		assertEquals("GLOBAL", asset.getReuseScope());
		assertEquals(0, asset.getReuseCount());
		assertFalse(asset.isArchiveEligible());
		assertNotNull(asset.getExpiresAt());
	}

	@Test
	void touchAssetIncrementsReuseMetadata() {
		GeneratedAssetEntity existing = new GeneratedAssetEntity();
		existing.setId("asset-1");
		existing.setAssetType(GeneratedAssetType.MUSIC);
		existing.setStoragePath(tempDir.resolve("asset.wav").toString());
		existing.setContentHash("hash");
		existing.setProviderFingerprint("ace-step:1.0");
		existing.setByteSize(123L);
		existing.setReuseScope("GLOBAL");
		existing.setReuseCount(2);
		existing.setLastAccessedAt(Instant.parse("2026-03-20T09:00:00Z"));

		when(generatedAssetRepository.findById("asset-1")).thenReturn(Optional.of(existing));

		GeneratedAssetEntity touched = generatedAssetService.touchAsset("asset-1").orElseThrow();

		assertEquals(3, touched.getReuseCount());
		assertTrue(touched.getLastAccessedAt().isAfter(Instant.parse("2026-03-20T09:00:00Z")));
		verify(generatedAssetRepository).save(existing);
	}

	@Test
	void cloneAssetForQueueTouchesSourceAndPreservesAssetSize() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument(
				new SettingsDocument.CacheSettings(
						134_217_728L,
						536_870_912L,
						2_147_483_648L,
						7,
						3,
						9,
						"STATION",
						"SESSION",
						"GLOBAL",
						200)));

		Path sourcePath = tempDir.resolve("source.wav");
		byte[] bytes = "clone-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(sourcePath, bytes);

		GeneratedAssetEntity source = new GeneratedAssetEntity();
		source.setId("asset-source");
		source.setAssetType(GeneratedAssetType.MUSIC);
		source.setStoragePath(sourcePath.toString());
		source.setContentHash("hash-source");
		source.setProviderFingerprint("ace-step:1.0");
		source.setByteSize((long) bytes.length);
		source.setReuseScope("GLOBAL");
		source.setReuseCount(4);
		source.setLastAccessedAt(Instant.parse("2026-03-20T09:00:00Z"));
		source.setMetadata(Map.of("origin", "source"));

		when(generatedAssetRepository.findById("asset-source")).thenReturn(Optional.of(source));
		ArgumentCaptor<GeneratedAssetEntity> captor = ArgumentCaptor.forClass(GeneratedAssetEntity.class);
		when(generatedAssetRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

		GeneratedAssetEntity cloned = generatedAssetService.cloneAssetForQueue(
				source,
				"queue-1",
				"provider-job-1",
				"cache-key-1",
				Map.of("archiveEligible", true));

		assertEquals(bytes.length, cloned.getByteSize());
		assertEquals("GLOBAL", cloned.getReuseScope());
		assertEquals(0, cloned.getReuseCount());
		assertTrue(cloned.isArchiveEligible());
		assertEquals(2, captor.getAllValues().size());
		assertEquals(5, captor.getAllValues().get(0).getReuseCount());
		assertEquals(0, captor.getAllValues().get(1).getReuseCount());
		assertEquals(Files.size(sourcePath), captor.getAllValues().get(1).getByteSize());
	}

	private SettingsDocument settingsDocument(SettingsDocument.CacheSettings cacheSettings) {
		return new SettingsDocument(
				1,
				"2026-04",
				Instant.parse("2026-03-20T09:00:00Z"),
				SettingsDocument.ServerSettings.defaults(),
				new SettingsDocument.PathSettings(tempDir.resolve("data").toString(), tempDir.resolve("data").resolve("library").resolve("music").toString()),
				SettingsDocument.PlayoutSettings.defaults(),
				cacheSettings,
				SettingsDocument.ProgrammingSettings.defaults(),
				SettingsDocument.ProviderCatalog.defaults(),
				SettingsDocument.SecuritySettings.defaults(),
				SettingsDocument.FeatureSettings.defaults())
				.normalize();
	}
}
