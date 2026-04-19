package com.seedshiftradio.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.GeneratedAssetType;

@Service
public class GeneratedAssetService {

	private final GeneratedAssetRepository generatedAssetRepository;
	private final RadioSettingsStore settingsStore;

	public GeneratedAssetService(GeneratedAssetRepository generatedAssetRepository, RadioSettingsStore settingsStore) {
		this.generatedAssetRepository = generatedAssetRepository;
		this.settingsStore = settingsStore;
	}

	@Transactional
	public GeneratedAssetEntity createAudioAsset(byte[] bytes, String providerFingerprint, Map<String, Object> metadata) {
		return createAudioAsset(bytes, providerFingerprint, null, null, null, metadata);
	}

	@Transactional
	public GeneratedAssetEntity createScriptAsset(
			String normalizedText,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			Map<String, Object> metadata) {
		String assetId = nextId();
		byte[] bytes = (normalizedText == null ? "" : normalizedText).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Path assetPath = resolveScriptPath(assetId);
		write(assetPath, bytes);
		return persistAsset(
				assetId,
				GeneratedAssetType.SCRIPT,
				assetPath,
				bytes.length,
				providerFingerprint,
				queueItemId,
				providerJobId,
				metadata == null ? null : stringValue(metadata.get("cacheKey")),
				metadata);
	}

	@Transactional
	public GeneratedAssetEntity createAudioAsset(
			byte[] bytes,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			Map<String, Object> metadata) {
		return createAudioAsset(bytes, providerFingerprint, queueItemId, providerJobId, null, metadata);
	}

	@Transactional
	public GeneratedAssetEntity createAudioAsset(
			byte[] bytes,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			String cacheKey,
			Map<String, Object> metadata) {
		String assetId = nextId();
		Path assetPath = resolveAudioPath(assetId);
		write(assetPath, bytes);
		return persistAsset(
				assetId,
				GeneratedAssetType.AUDIO,
				assetPath,
				bytes.length,
				providerFingerprint,
				queueItemId,
				providerJobId,
				cacheKey,
				metadata);
	}

	@Transactional
	public GeneratedAssetEntity registerExistingAsset(
			GeneratedAssetType assetType,
			Path assetPath,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			Map<String, Object> metadata) {
		return registerExistingAsset(assetType, assetPath, providerFingerprint, queueItemId, providerJobId, null, metadata);
	}

	@Transactional
	public GeneratedAssetEntity registerExistingAsset(
			GeneratedAssetType assetType,
			Path assetPath,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			String cacheKey,
			Map<String, Object> metadata) {
		Path normalizedPath = assetPath.toAbsolutePath().normalize();
		byte[] bytes = read(normalizedPath);
		return persistAsset(
				nextId(),
				assetType,
				normalizedPath,
				bytes.length,
				providerFingerprint,
				queueItemId,
				providerJobId,
				cacheKey,
				metadata);
	}

	@Transactional
	public Optional<GeneratedAssetEntity> touchAsset(String assetId) {
		return generatedAssetRepository.findById(assetId)
				.map(entity -> generatedAssetRepository.save(touch(entity)));
	}

	@Transactional(readOnly = true)
	public CacheMetricsSnapshot cacheMetrics() {
		return cacheMetrics(Instant.now());
	}

	@Transactional
	public CacheEvictionResult evictCache() {
		SettingsDocument.CacheSettings cache = settingsStore.load().cache();
		Instant now = Instant.now();
		int remainingBatch = cache.cleanupBatchSize();
		EvictionAccumulator accumulator = new EvictionAccumulator(now);

		List<GeneratedAssetEntity> expiredCandidates = generatedAssetRepository.findExpiredEvictionCandidates(
				now,
				PageRequest.of(0, remainingBatch));
		for (GeneratedAssetEntity candidate : expiredCandidates) {
			if (remainingBatch <= 0) {
				break;
			}
			if (evictPayload(candidate, "expired", now, accumulator)) {
				accumulator.expiredAssetCount++;
				remainingBatch--;
			}
		}

		CacheMetricsSnapshot metrics = cacheMetrics(now);
		for (GeneratedAssetType assetType : GeneratedAssetType.values()) {
			if (remainingBatch <= 0) {
				break;
			}
			long maxBytes = maxBytesFor(cache, assetType);
			long currentBytes = metrics.byType().get(assetType).byteSize();
			if (currentBytes <= maxBytes) {
				continue;
			}
			List<GeneratedAssetEntity> capacityCandidates = generatedAssetRepository.findCapacityEvictionCandidates(
					assetType,
					PageRequest.of(0, remainingBatch));
			for (GeneratedAssetEntity candidate : capacityCandidates) {
				if (remainingBatch <= 0 || currentBytes <= maxBytes) {
					break;
				}
				long beforeBytes = normalizeByteSize(candidate.getByteSize());
				if (evictPayload(candidate, "capacity", now, accumulator)) {
					accumulator.capacityAssetCount++;
					currentBytes = Math.max(0L, currentBytes - beforeBytes);
					remainingBatch--;
				}
			}
		}

		return accumulator.toResult(cacheMetrics(Instant.now()));
	}

	@Transactional(readOnly = true)
	public Optional<GeneratedAssetEntity> findReusableAsset(GeneratedAssetType assetType, String cacheKey) {
		if (cacheKey == null || cacheKey.isBlank()) {
			return Optional.empty();
		}
		return generatedAssetRepository.findFirstByAssetTypeAndCacheKeyOrderByCreatedAtDesc(assetType, cacheKey)
				.filter(asset -> Files.isRegularFile(Path.of(asset.getStoragePath()).toAbsolutePath().normalize()));
	}

	@Transactional(readOnly = true)
	public Optional<GeneratedAssetEntity> findLatestScriptAssetForQueueItem(String queueItemId) {
		if (queueItemId == null || queueItemId.isBlank()) {
			return Optional.empty();
		}
		return generatedAssetRepository.findFirstByAssetTypeAndQueueItemIdOrderByCreatedAtDesc(GeneratedAssetType.SCRIPT, queueItemId);
	}

	@Transactional
	public GeneratedAssetEntity cloneAssetForQueue(
			GeneratedAssetEntity source,
			String queueItemId,
			String providerJobId,
			String cacheKey,
			Map<String, Object> metadataOverrides) {
		Path assetPath = Path.of(source.getStoragePath()).toAbsolutePath().normalize();
		if (!Files.isRegularFile(assetPath)) {
			throw new ApiException(
					HttpStatus.NOT_FOUND,
					"NOT_FOUND",
					"再利用対象の generated asset が見つかりません。",
					Map.of("assetId", source.getId()));
		}
		touchAsset(source.getId());
		Map<String, Object> mergedMetadata = new LinkedHashMap<>(source.getMetadata());
		if (metadataOverrides != null) {
			mergedMetadata.putAll(metadataOverrides);
		}
		return persistAssetRecord(
				nextId(),
				source.getAssetType(),
				assetPath,
				resolveByteSize(assetPath, source.getByteSize()),
				source.getContentHash(),
				source.getProviderFingerprint(),
				queueItemId,
				providerJobId,
				cacheKey,
				mergedMetadata);
	}

	public Optional<Path> resolveAudioAssetPath(String assetId) {
		return generatedAssetRepository.findById(assetId)
				.map(entity -> Path.of(entity.getStoragePath()).toAbsolutePath().normalize());
	}

	public Path resolveLegacyAudioPath(String assetId) {
		return resolveAudioPath(assetId);
	}

	private Path resolveAudioPath(String assetId) {
		Path dataRoot = Path.of(settingsStore.load().paths().dataRoot()).toAbsolutePath().normalize();
		Path audioRoot = dataRoot.resolve("assets").resolve("audio").normalize();
		Path assetPath = audioRoot.resolve(assetId + ".wav").normalize();
		if (!assetPath.startsWith(audioRoot)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "assetId が不正です。", Map.of("assetId", assetId));
		}
		return assetPath;
	}

	private Path resolveScriptPath(String assetId) {
		Path dataRoot = Path.of(settingsStore.load().paths().dataRoot()).toAbsolutePath().normalize();
		Path scriptRoot = dataRoot.resolve("assets").resolve("scripts").normalize();
		Path assetPath = scriptRoot.resolve(assetId + ".txt").normalize();
		if (!assetPath.startsWith(scriptRoot)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "assetId が不正です。", Map.of("assetId", assetId));
		}
		return assetPath;
	}

	private void write(Path assetPath, byte[] bytes) {
		try {
			Files.createDirectories(assetPath.getParent());
			Files.write(assetPath, bytes);
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"generated asset の保存に失敗しました。",
					Map.of("assetPath", assetPath.toString()));
		}
	}

	private byte[] read(Path assetPath) {
		try {
			return Files.readAllBytes(assetPath);
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"generated asset の読み込みに失敗しました。",
					Map.of("assetPath", assetPath.toString()));
		}
	}

	private GeneratedAssetEntity persistAsset(
			String assetId,
			GeneratedAssetType assetType,
			Path assetPath,
			long byteSize,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			String cacheKey,
			Map<String, Object> metadata) {
		return persistAssetRecord(
				assetId,
				assetType,
				assetPath,
				byteSize,
				sha256(read(assetPath)),
				providerFingerprint,
				queueItemId,
				providerJobId,
				cacheKey,
				metadata);
	}

	private GeneratedAssetEntity persistAssetRecord(
			String assetId,
			GeneratedAssetType assetType,
			Path assetPath,
			long byteSize,
			String contentHash,
			String providerFingerprint,
			String queueItemId,
			String providerJobId,
			String cacheKey,
			Map<String, Object> metadata) {
		LifecycleDefaults lifecycleDefaults = resolveLifecycleDefaults(assetType);
		GeneratedAssetEntity entity = new GeneratedAssetEntity();
		entity.setId(assetId);
		entity.setAssetType(assetType);
		entity.setStoragePath(assetPath.toString());
		entity.setContentHash(contentHash);
		entity.setProviderFingerprint(providerFingerprint == null || providerFingerprint.isBlank() ? "server:placeholder" : providerFingerprint);
		entity.setCacheKey(cacheKey == null || cacheKey.isBlank() ? null : cacheKey);
		entity.setByteSize(Math.max(0L, byteSize));
		entity.setReuseScope(lifecycleDefaults.reuseScope());
		entity.setReuseCount(0);
		entity.setLastAccessedAt(Instant.now());
		entity.setExpiresAt(resolveExpiresAt(lifecycleDefaults));
		entity.setArchiveEligible(resolveArchiveEligible(metadata));
		entity.setQueueItemId(queueItemId);
		entity.setProviderJobId(providerJobId);
		entity.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
		entity.setUpdatedAt(Instant.now());
		return generatedAssetRepository.save(entity);
	}

	private GeneratedAssetEntity touch(GeneratedAssetEntity entity) {
		entity.setReuseCount(normalizeReuseCount(entity.getReuseCount()) + 1);
		entity.setLastAccessedAt(Instant.now());
		return entity;
	}

	private CacheMetricsSnapshot cacheMetrics(Instant checkedAt) {
		Map<GeneratedAssetType, CacheTypeMetrics> byType = new EnumMap<>(GeneratedAssetType.class);
		for (GeneratedAssetType assetType : GeneratedAssetType.values()) {
			byType.put(assetType, new CacheTypeMetrics(assetType, 0L, 0L, 0L, 0.0D));
		}
		for (GeneratedAssetRepository.AssetTypeStats stats : generatedAssetRepository.summarizeByAssetType()) {
			long assetCount = stats.getAssetCount();
			long byteSize = stats.getByteSize();
			long cacheHitCount = stats.getCacheHitCount();
			byType.put(
					stats.getAssetType(),
					new CacheTypeMetrics(
							stats.getAssetType(),
							assetCount,
							byteSize,
							cacheHitCount,
							cacheHitRate(assetCount, cacheHitCount)));
		}
		long assetCount = byType.values().stream().mapToLong(CacheTypeMetrics::assetCount).sum();
		long byteSize = byType.values().stream().mapToLong(CacheTypeMetrics::byteSize).sum();
		long cacheHitCount = byType.values().stream().mapToLong(CacheTypeMetrics::cacheHitCount).sum();
		return new CacheMetricsSnapshot(
				checkedAt,
				assetCount,
				byteSize,
				cacheHitCount,
				cacheHitRate(assetCount, cacheHitCount),
				generatedAssetRepository.countExpiredEvictionCandidates(checkedAt),
				Map.copyOf(byType));
	}

	private boolean evictPayload(
			GeneratedAssetEntity entity,
			String reason,
			Instant now,
			EvictionAccumulator accumulator) {
		long byteSize = normalizeByteSize(entity.getByteSize());
		try {
			boolean fileDeleted = shouldDeletePayloadFile(entity) && deletePayloadFile(entity.getStoragePath());
			Map<String, Object> metadata = new LinkedHashMap<>(entity.getMetadata() == null ? Map.of() : entity.getMetadata());
			metadata.put("evictedAt", now.toString());
			metadata.put("evictionReason", reason);
			metadata.put("evictedByteSize", byteSize);
			metadata.put("payloadFileDeleted", fileDeleted);
			entity.setMetadata(metadata);
			entity.setByteSize(0L);
			entity.setCacheKey(null);
			entity.setReuseScope("DISABLED");
			entity.setExpiresAt(now);
			generatedAssetRepository.save(entity);
			accumulator.evictedAssetCount++;
			accumulator.reclaimedBytes += byteSize;
			return true;
		} catch (IOException | InvalidPathException exception) {
			accumulator.failedAssetCount++;
			return false;
		}
	}

	private boolean shouldDeletePayloadFile(GeneratedAssetEntity entity) {
		String storagePath = entity.getStoragePath();
		if (storagePath == null || storagePath.isBlank()) {
			return false;
		}
		return generatedAssetRepository.countActivePayloadReferences(storagePath) <= 1L;
	}

	private boolean deletePayloadFile(String storagePath) throws IOException {
		if (storagePath == null || storagePath.isBlank()) {
			return false;
		}
		Path path = Path.of(storagePath).toAbsolutePath().normalize();
		if (!Files.exists(path)) {
			return false;
		}
		Files.delete(path);
		return true;
	}

	private long maxBytesFor(SettingsDocument.CacheSettings cache, GeneratedAssetType assetType) {
		return switch (assetType) {
			case SCRIPT -> cache.scriptMaxBytes();
			case AUDIO -> cache.ttsMaxBytes();
			case MUSIC -> cache.musicMaxBytes();
		};
	}

	private double cacheHitRate(long assetCount, long cacheHitCount) {
		long denominator = assetCount + cacheHitCount;
		if (denominator <= 0) {
			return 0.0D;
		}
		return (double) cacheHitCount / (double) denominator;
	}

	private long normalizeByteSize(Long byteSize) {
		return byteSize == null || byteSize < 0 ? 0L : byteSize;
	}

	private long resolveByteSize(Path assetPath, Long fallback) {
		if (fallback != null && fallback >= 0) {
			return fallback;
		}
		try {
			return Files.size(assetPath);
		} catch (IOException exception) {
			throw new ApiException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"INTERNAL_ERROR",
					"generated asset のサイズ取得に失敗しました。",
					Map.of("assetPath", assetPath.toString()));
		}
	}

	private LifecycleDefaults resolveLifecycleDefaults(GeneratedAssetType assetType) {
		SettingsDocument.CacheSettings cache = settingsStore.load().cache();
		return switch (assetType) {
			case SCRIPT -> new LifecycleDefaults(cache.scriptReuseScope(), cache.scriptRetentionDays());
			case AUDIO -> new LifecycleDefaults(cache.ttsReuseScope(), cache.ttsRetentionDays());
			case MUSIC -> new LifecycleDefaults(cache.musicReuseScope(), cache.musicRetentionDays());
		};
	}

	private Instant resolveExpiresAt(LifecycleDefaults defaults) {
		return Instant.now().plus(Duration.ofDays(defaults.retentionDays()));
	}

	private boolean resolveArchiveEligible(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return false;
		}
		Object value = metadata.get("archiveEligible");
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		if (value instanceof String stringValue) {
			return Boolean.parseBoolean(stringValue);
		}
		return false;
	}

	private int normalizeReuseCount(Integer reuseCount) {
		return reuseCount == null || reuseCount < 0 ? 0 : reuseCount;
	}

	private String stringValue(Object value) {
		return value instanceof String string && !string.isBlank() ? string : null;
	}

	private String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(bytes);
			StringBuilder builder = new StringBuilder(hash.length * 2);
			for (byte value : hash) {
				builder.append(String.format("%02x", value));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	private String nextId() {
		return "asset-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	public record CacheMetricsSnapshot(
			Instant checkedAt,
			long assetCount,
			long byteSize,
			long cacheHitCount,
			double cacheHitRate,
			long expiredAssetCount,
			Map<GeneratedAssetType, CacheTypeMetrics> byType) {
	}

	public record CacheTypeMetrics(
			GeneratedAssetType assetType,
			long assetCount,
			long byteSize,
			long cacheHitCount,
			double cacheHitRate) {
	}

	public record CacheEvictionResult(
			Instant executedAt,
			int evictedAssetCount,
			int expiredAssetCount,
			int capacityAssetCount,
			int failedAssetCount,
			long reclaimedBytes,
			CacheMetricsSnapshot after) {
	}

	private static final class EvictionAccumulator {

		private final Instant executedAt;
		private int evictedAssetCount;
		private int expiredAssetCount;
		private int capacityAssetCount;
		private int failedAssetCount;
		private long reclaimedBytes;

		private EvictionAccumulator(Instant executedAt) {
			this.executedAt = executedAt;
		}

		private CacheEvictionResult toResult(CacheMetricsSnapshot after) {
			return new CacheEvictionResult(
					executedAt,
					evictedAssetCount,
					expiredAssetCount,
					capacityAssetCount,
					failedAssetCount,
					reclaimedBytes,
					after);
		}
	}

	private record LifecycleDefaults(String reuseScope, int retentionDays) {
	}
}
