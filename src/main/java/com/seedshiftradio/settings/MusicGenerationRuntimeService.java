package com.seedshiftradio.settings;

import java.nio.file.Path;
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
		MusicGenWorkerGateway.ResolvedMusicProvider provider = musicGenWorkerGateway.resolveProvider();
		ProviderJobEntity providerJob = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN,
				ProviderType.MUSIC,
				provider.providerKey(),
				item.getId(),
				item.getCorrelationId());
		try {
			MusicGenWorkerGateway.SubmittedMusicJob submittedJob = musicGenWorkerGateway.submit(provider, buildRequest(stationId, item));
			providerJobService.markRunning(providerJob.getId(), submittedJob.jobId());
			MusicGenWorkerGateway.MusicJobStatus completedJob = musicGenWorkerGateway.awaitCompletion(provider, submittedJob.jobId());
			GeneratedAssetEntity asset = generatedAssetService.registerExistingAsset(
					GeneratedAssetType.MUSIC,
					Path.of(completedJob.assetPath()),
					completedJob.providerFingerprint() == null || completedJob.providerFingerprint().isBlank()
							? provider.providerKey()
							: completedJob.providerFingerprint(),
					item.getId(),
					providerJob.getId(),
					buildMetadata(stationId, item, provider, providerJob, completedJob));
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

	private Map<String, Object> buildMetadata(
			String stationId,
			QueueItemEntity item,
			MusicGenWorkerGateway.ResolvedMusicProvider provider,
			ProviderJobEntity providerJob,
			MusicGenWorkerGateway.MusicJobStatus completedJob) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("stationId", stationId);
		metadata.put("queueItemId", item.getId());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("providerJobId", providerJob.getId());
		metadata.put("workerJobId", completedJob.jobId());
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("durationSec", completedJob.durationSec());
		if (completedJob.promptHash() != null && !completedJob.promptHash().isBlank()) {
			metadata.put("promptHash", completedJob.promptHash());
		}
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

	public record GeneratedMusicAsset(String assetId, String assetUrl, String providerJobId, String workerJobId) {
	}
}
