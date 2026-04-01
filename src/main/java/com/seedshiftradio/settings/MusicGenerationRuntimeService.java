package com.seedshiftradio.settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.station.StationRepository;

@Service
public class MusicGenerationRuntimeService {

	private final MusicGenWorkerGateway musicGenWorkerGateway;
	private final ProviderJobService providerJobService;
	private final GeneratedAssetService generatedAssetService;
	private final StationRepository stationRepository;

	public MusicGenerationRuntimeService(
			MusicGenWorkerGateway musicGenWorkerGateway,
			ProviderJobService providerJobService,
			GeneratedAssetService generatedAssetService,
			StationRepository stationRepository) {
		this.musicGenWorkerGateway = musicGenWorkerGateway;
		this.providerJobService = providerJobService;
		this.generatedAssetService = generatedAssetService;
		this.stationRepository = stationRepository;
	}

	public GeneratedMusicAsset generate(String stationId, QueueItemEntity item) {
		List<MusicGenWorkerGateway.ResolvedMusicProvider> providers = musicGenWorkerGateway.resolveProviders();
		MusicGenWorkerGateway.MusicJobRequest request = buildRequest(stationId, item);
		ProviderJobEntity providerJob = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				providers.getFirst().providerKey(),
				item.getId(),
				item.getCorrelationId());
		try {
			CachedAssetHit cachedAssetHit = findReusableAsset(providers, request);
			GeneratedAssetEntity reusableAsset = cachedAssetHit == null ? null : cachedAssetHit.asset();
			if (reusableAsset != null) {
				providerJobService.markRunning(providerJob.getId(), cachedAssetHit.provider().providerKey(), "cache-hit:" + reusableAsset.getId());
				GeneratedAssetEntity asset = generatedAssetService.cloneAssetForQueue(
						reusableAsset,
						item.getId(),
						providerJob.getId(),
						cachedAssetHit.cacheKey(),
						buildCacheHitMetadata(stationId, item, cachedAssetHit.provider(), providerJob, reusableAsset));
				providerJobService.markSucceeded(providerJob.getId());
				return new GeneratedMusicAsset(
						asset.getId(),
						"/api/assets/audio/" + asset.getId() + ".wav",
						providerJob.getId(),
						null);
			}
			MusicGenWorkerGateway.SubmittedMusicJob submittedJob = musicGenWorkerGateway.submitWithFallback(providers, request);
			providerJobService.markRunning(providerJob.getId(), submittedJob.provider().providerKey(), submittedJob.jobId());
			MusicGenWorkerGateway.MusicJobStatus completedJob = musicGenWorkerGateway.awaitCompletion(submittedJob.provider(), submittedJob.jobId());
			String cacheKey = buildCacheKey(submittedJob.provider(), request);
			GeneratedAssetEntity asset = generatedAssetService.registerExistingAsset(
					GeneratedAssetType.MUSIC,
					Path.of(completedJob.assetPath()),
					completedJob.providerFingerprint() == null || completedJob.providerFingerprint().isBlank()
							? submittedJob.provider().providerKey()
							: completedJob.providerFingerprint(),
					item.getId(),
					providerJob.getId(),
					cacheKey,
					buildGeneratedMetadata(stationId, item, submittedJob.provider(), providerJob, completedJob, cacheKey));
			providerJobService.markSucceeded(providerJob.getId());
			return new GeneratedMusicAsset(
					asset.getId(),
					"/api/assets/audio/" + asset.getId() + ".wav",
					providerJob.getId(),
					submittedJob.jobId());
		} catch (MusicGenWorkerException exception) {
			providerJobService.markFailed(providerJob.getId(), exception.errorCode());
			throw exception;
		} catch (RuntimeException exception) {
			providerJobService.markFailed(providerJob.getId(), "PROVIDER_BAD_RESPONSE");
			throw exception;
		}
	}

	private CachedAssetHit findReusableAsset(
			List<MusicGenWorkerGateway.ResolvedMusicProvider> providers,
			MusicGenWorkerGateway.MusicJobRequest request) {
		for (MusicGenWorkerGateway.ResolvedMusicProvider provider : providers) {
			String cacheKey = buildCacheKey(provider, request);
			GeneratedAssetEntity reusableAsset = generatedAssetService.findReusableAsset(GeneratedAssetType.MUSIC, cacheKey).orElse(null);
			if (reusableAsset != null) {
				return new CachedAssetHit(provider, cacheKey, reusableAsset);
			}
		}
		return null;
	}

	private MusicGenWorkerGateway.MusicJobRequest buildRequest(String stationId, QueueItemEntity item) {
		return new MusicGenWorkerGateway.MusicJobRequest(
				item.getCorrelationId() + ":" + item.getId(),
				stationId,
				"BGM",
				resolveGenre(stationId),
				resolveMood(item.getSlotRole()),
				normalizeDurationSec(item.getDurationMs()),
				resolveSeed(item));
	}

	private Map<String, Object> buildGeneratedMetadata(
			String stationId,
			QueueItemEntity item,
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			ProviderJobEntity providerJob,
			MusicGenWorkerGateway.MusicJobStatus completedJob,
			String cacheKey) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("stationId", stationId);
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("providerJobId", providerJob.getId());
		metadata.put("workerJobId", completedJob.jobId());
		metadata.put("cacheKey", cacheKey);
		metadata.put("cacheHit", false);
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("durationSec", completedJob.durationSec());
		if (completedJob.promptHash() != null && !completedJob.promptHash().isBlank()) {
			metadata.put("promptHash", completedJob.promptHash());
		}
		return metadata;
	}

	private Map<String, Object> buildCacheHitMetadata(
			String stationId,
			QueueItemEntity item,
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			ProviderJobEntity providerJob,
			GeneratedAssetEntity reusableAsset) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("stationId", stationId);
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("providerJobId", providerJob.getId());
		metadata.put("cacheKey", reusableAsset.getCacheKey());
		metadata.put("cacheHit", true);
		metadata.put("cacheSourceAssetId", reusableAsset.getId());
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		return metadata;
	}

	private String resolveGenre(String stationId) {
		return stationRepository.findById(stationId)
				.map(station -> station.getGenre().toLowerCase(Locale.ROOT))
				.orElse("ambient");
	}

	private List<String> resolveMood(SlotRole slotRole) {
		return switch (slotRole) {
			case OPENING -> List.of("bright", "intro");
			case TOPIC -> List.of("warm", "flow");
			case LETTER -> List.of("gentle", "supportive");
			case MUSIC_BREAK -> List.of("ambient", "calm");
			case ENDING -> List.of("calm", "closing");
		};
	}

	private Integer normalizeDurationSec(Integer durationMs) {
		int safeDurationMs = durationMs == null ? 30_000 : durationMs;
		return Math.max(5, Math.min(120, safeDurationMs / 1_000));
	}

	private Integer resolveSeed(QueueItemEntity item) {
		return Math.floorMod((item.getCorrelationId() + ":" + item.getId()).hashCode(), Integer.MAX_VALUE);
	}

	private String buildCacheKey(
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			MusicGenWorkerGateway.MusicJobRequest request) {
		List<String> normalizedMood = request.mood() == null
				? List.of()
				: request.mood().stream().map(this::normalize).toList();
		String raw = String.join(
				"|",
				normalize(provider.providerKey()),
				normalize(provider.baseUrl()),
				normalize(request.stationId()),
				normalize(request.mode()),
				normalize(request.genre()),
				String.join(",", normalizedMood),
				String.valueOf(request.durationSec()),
				String.valueOf(request.seed() == null ? 0 : request.seed()));
		return sha256(raw);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte current : bytes) {
				builder.append(String.format("%02x", current));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	public record GeneratedMusicAsset(String assetId, String assetUrl, String providerJobId, String workerJobId) {
	}

	private record CachedAssetHit(
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			String cacheKey,
			GeneratedAssetEntity asset) {
	}
}
