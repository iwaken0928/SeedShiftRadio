package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
		lenient().when(generatedAssetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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

	@Test
	void evictCacheRemovesExpiredPayloadWithoutDeletingMetadata() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument(
				new SettingsDocument.CacheSettings(
						1_000L,
						1_000L,
						1_000L,
						7,
						3,
						9,
						"STATION",
						"SESSION",
						"GLOBAL",
						10)));
		when(generatedAssetRepository.summarizeByAssetType()).thenReturn(List.of());
		when(generatedAssetRepository.countExpiredEvictionCandidates(any())).thenReturn(0L);

		Path expiredPath = tempDir.resolve("expired.wav");
		Files.write(expiredPath, "expired-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		GeneratedAssetEntity expired = asset(
				"asset-expired",
				GeneratedAssetType.AUDIO,
				expiredPath,
				Files.size(expiredPath),
				"cache-key-expired",
				Instant.parse("2026-03-19T09:00:00Z"),
				false);
		when(generatedAssetRepository.findExpiredEvictionCandidates(any(), any())).thenReturn(List.of(expired));

		GeneratedAssetService.CacheEvictionResult result = generatedAssetService.evictCache();

		assertFalse(Files.exists(expiredPath));
		assertEquals(1, result.evictedAssetCount());
		assertEquals(1, result.expiredAssetCount());
		assertEquals("DISABLED", expired.getReuseScope());
		assertEquals(0L, expired.getByteSize());
		assertNull(expired.getCacheKey());
		assertEquals("expired", expired.getMetadata().get("evictionReason"));
		verify(generatedAssetRepository).save(expired);
	}

	@Test
	void evictCacheKeepsPayloadFileWhenAnotherAssetRecordSharesStoragePath() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument(
				new SettingsDocument.CacheSettings(
						1_000L,
						1_000L,
						1_000L,
						7,
						3,
						9,
						"STATION",
						"SESSION",
						"GLOBAL",
						10)));
		when(generatedAssetRepository.summarizeByAssetType()).thenReturn(List.of());
		when(generatedAssetRepository.countExpiredEvictionCandidates(any())).thenReturn(0L);
		when(generatedAssetRepository.countActivePayloadReferences(any())).thenReturn(2L);

		Path sharedPath = tempDir.resolve("shared.wav");
		Files.write(sharedPath, "shared-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		GeneratedAssetEntity expired = asset(
				"asset-shared",
				GeneratedAssetType.AUDIO,
				sharedPath,
				Files.size(sharedPath),
				"cache-key-shared",
				Instant.parse("2026-03-19T09:00:00Z"),
				false);
		when(generatedAssetRepository.findExpiredEvictionCandidates(any(), any())).thenReturn(List.of(expired));

		GeneratedAssetService.CacheEvictionResult result = generatedAssetService.evictCache();

		assertTrue(Files.exists(sharedPath));
		assertEquals(1, result.evictedAssetCount());
		assertFalse((Boolean) expired.getMetadata().get("payloadFileDeleted"));
		assertNull(expired.getCacheKey());
		verify(generatedAssetRepository).save(expired);
	}

	@Test
	void evictCacheEnforcesTypeSizeCapUsingLeastRecentlyUsedCandidates() throws Exception {
		when(settingsStore.load()).thenReturn(settingsDocument(
				new SettingsDocument.CacheSettings(
						1_000L,
						1_000L,
						10L,
						7,
						3,
						9,
						"STATION",
						"SESSION",
						"GLOBAL",
						10)));
		when(generatedAssetRepository.findExpiredEvictionCandidates(any(), any())).thenReturn(List.of());
		when(generatedAssetRepository.countExpiredEvictionCandidates(any())).thenReturn(0L);
		when(generatedAssetRepository.summarizeByAssetType())
				.thenReturn(List.of(stats(GeneratedAssetType.MUSIC, 1L, 100L, 0L)))
				.thenReturn(List.of(stats(GeneratedAssetType.MUSIC, 1L, 0L, 0L)));

		Path musicPath = tempDir.resolve("music.wav");
		Files.write(musicPath, "music-data-over-cap".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		GeneratedAssetEntity candidate = asset(
				"asset-music",
				GeneratedAssetType.MUSIC,
				musicPath,
				100L,
				"cache-key-music",
				Instant.parse("2026-03-30T09:00:00Z"),
				false);
		when(generatedAssetRepository.findCapacityEvictionCandidates(any(), any())).thenReturn(List.of(candidate));

		GeneratedAssetService.CacheEvictionResult result = generatedAssetService.evictCache();

		assertFalse(Files.exists(musicPath));
		assertEquals(1, result.evictedAssetCount());
		assertEquals(1, result.capacityAssetCount());
		assertEquals(100L, result.reclaimedBytes());
		assertEquals("capacity", candidate.getMetadata().get("evictionReason"));
		verify(generatedAssetRepository).save(candidate);
	}

	@Test
	void assetConsistencyReportsMetadataAndPayloadIssuesWithoutFlaggingEvictedPayloads() throws Exception {
		SettingsDocument settings = settingsDocument(SettingsDocument.CacheSettings.defaults());
		when(settingsStore.load()).thenReturn(settings);

		Path dataRoot = Path.of(settings.paths().dataRoot());
		Path audioRoot = dataRoot.resolve("assets").resolve("audio");
		Path scriptRoot = dataRoot.resolve("assets").resolve("scripts");
		Files.createDirectories(audioRoot);
		Files.createDirectories(scriptRoot);

		Path validPath = audioRoot.resolve("valid.wav");
		Files.write(validPath, "valid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Path mismatchPath = audioRoot.resolve("mismatch.wav");
		Files.write(mismatchPath, "actual".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Path hashMismatchPath = audioRoot.resolve("hash-mismatch.wav");
		Files.write(hashMismatchPath, "hash".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Path orphanPath = scriptRoot.resolve("orphan.txt");
		Files.write(orphanPath, "orphan".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Path evictedLeftoverPath = audioRoot.resolve("evicted-leftover.wav");
		Files.write(evictedLeftoverPath, "leftover".getBytes(java.nio.charset.StandardCharsets.UTF_8));

		GeneratedAssetEntity valid = asset(
				"asset-valid",
				GeneratedAssetType.AUDIO,
				validPath,
				Files.size(validPath),
				"cache-valid",
				Instant.parse("2026-03-30T09:00:00Z"),
				false);
		valid.setContentHash(sha256("valid"));
		GeneratedAssetEntity mismatch = asset(
				"asset-mismatch",
				GeneratedAssetType.AUDIO,
				mismatchPath,
				99L,
				"cache-mismatch",
				Instant.parse("2026-03-30T09:00:00Z"),
				false);
		mismatch.setContentHash(sha256("actual"));
		GeneratedAssetEntity hashMismatch = asset(
				"asset-hash-mismatch",
				GeneratedAssetType.AUDIO,
				hashMismatchPath,
				Files.size(hashMismatchPath),
				"cache-hash-mismatch",
				Instant.parse("2026-03-30T09:00:00Z"),
				false);
		hashMismatch.setContentHash(sha256("different"));
		GeneratedAssetEntity missing = asset(
				"asset-missing",
				GeneratedAssetType.MUSIC,
				dataRoot.resolve("assets").resolve("music").resolve("missing.wav"),
				123L,
				"cache-missing",
				Instant.parse("2026-03-30T09:00:00Z"),
				false);
		GeneratedAssetEntity noPath = asset(
				"asset-no-path",
				GeneratedAssetType.SCRIPT,
				(String) null,
				12L,
				"cache-no-path",
				Instant.parse("2026-03-30T09:00:00Z"),
				false);
		GeneratedAssetEntity evicted = asset(
				"asset-evicted",
				GeneratedAssetType.AUDIO,
				evictedLeftoverPath,
				0L,
				null,
				Instant.parse("2026-03-19T09:00:00Z"),
				false);
		when(generatedAssetRepository.findAll()).thenReturn(List.of(valid, mismatch, hashMismatch, missing, noPath, evicted));

		GeneratedAssetService.AssetConsistencyReport report = generatedAssetService.assetConsistency();
		long actualMismatchSize = Files.size(mismatchPath);

		assertEquals(6L, report.assetCount());
		assertEquals(5L, report.checkedAssetCount());
		assertEquals(2L, report.missingFileCount());
		assertEquals(1L, report.byteSizeMismatchCount());
		assertEquals(1L, report.contentHashMismatchCount());
		assertEquals(2L, report.orphanFileCount());
		assertEquals(0L, report.unreadableFileCount());
		assertEquals(6L, report.issueCount());
		assertFalse(report.issuesTruncated());
		assertTrue(report.issues().stream().anyMatch(issue ->
				issue.issueType() == GeneratedAssetService.AssetConsistencyIssueType.BYTE_SIZE_MISMATCH
						&& "asset-mismatch".equals(issue.assetId())
						&& issue.expectedByteSize() == 99L
						&& issue.actualByteSize() == actualMismatchSize));
		assertTrue(report.issues().stream().anyMatch(issue ->
				issue.issueType() == GeneratedAssetService.AssetConsistencyIssueType.CONTENT_HASH_MISMATCH
						&& "asset-hash-mismatch".equals(issue.assetId())));
		assertTrue(report.issues().stream().anyMatch(issue ->
				issue.issueType() == GeneratedAssetService.AssetConsistencyIssueType.MISSING_FILE
						&& "asset-missing".equals(issue.assetId())));
		assertTrue(report.issues().stream().anyMatch(issue ->
				issue.issueType() == GeneratedAssetService.AssetConsistencyIssueType.MISSING_FILE
						&& "asset-no-path".equals(issue.assetId())));
		assertTrue(report.issues().stream().anyMatch(issue ->
				issue.issueType() == GeneratedAssetService.AssetConsistencyIssueType.ORPHAN_FILE
						&& "assets/scripts/orphan.txt".equals(issue.storagePath())));
		assertTrue(report.issues().stream().anyMatch(issue ->
				issue.issueType() == GeneratedAssetService.AssetConsistencyIssueType.ORPHAN_FILE
						&& "assets/audio/evicted-leftover.wav".equals(issue.storagePath())));
		assertFalse(report.issues().stream().anyMatch(issue -> "asset-evicted".equals(issue.assetId())));
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

	private GeneratedAssetEntity asset(
			String id,
			GeneratedAssetType assetType,
			Path storagePath,
			long byteSize,
			String cacheKey,
			Instant expiresAt,
			boolean archiveEligible) {
		return asset(id, assetType, storagePath == null ? null : storagePath.toString(), byteSize, cacheKey, expiresAt, archiveEligible);
	}

	private GeneratedAssetEntity asset(
			String id,
			GeneratedAssetType assetType,
			String storagePath,
			long byteSize,
			String cacheKey,
			Instant expiresAt,
			boolean archiveEligible) {
		GeneratedAssetEntity entity = new GeneratedAssetEntity();
		entity.setId(id);
		entity.setAssetType(assetType);
		entity.setStoragePath(storagePath);
		entity.setContentHash("hash-" + id);
		entity.setProviderFingerprint("provider:test");
		entity.setCacheKey(cacheKey);
		entity.setByteSize(byteSize);
		entity.setReuseScope("GLOBAL");
		entity.setReuseCount(0);
		entity.setLastAccessedAt(Instant.parse("2026-03-20T09:00:00Z"));
		entity.setExpiresAt(expiresAt);
		entity.setArchiveEligible(archiveEligible);
		entity.setMetadata(Map.of("source", "test"));
		entity.setCreatedAt(Instant.parse("2026-03-20T09:00:00Z"));
		entity.setUpdatedAt(Instant.parse("2026-03-20T09:00:00Z"));
		return entity;
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(hash.length * 2);
			for (byte item : hash) {
				builder.append(String.format("%02x", item));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	private GeneratedAssetRepository.AssetTypeStats stats(
			GeneratedAssetType assetType,
			long assetCount,
			long byteSize,
			long cacheHitCount) {
		return new GeneratedAssetRepository.AssetTypeStats() {
			@Override
			public GeneratedAssetType getAssetType() {
				return assetType;
			}

			@Override
			public long getAssetCount() {
				return assetCount;
			}

			@Override
			public long getByteSize() {
				return byteSize;
			}

			@Override
			public long getCacheHitCount() {
				return cacheHitCount;
			}
		};
	}
}
