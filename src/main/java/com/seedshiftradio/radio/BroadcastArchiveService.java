package com.seedshiftradio.radio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import com.seedshiftradio.domain.GeneratedAssetType;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.ReplayProfile;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;
import com.seedshiftradio.settings.GeneratedAssetEntity;
import com.seedshiftradio.settings.GeneratedAssetRepository;
import com.seedshiftradio.settings.GeneratedAssetService;

@Service
public class BroadcastArchiveService {

	private static final List<SegmentType> DEFAULT_ARCHIVE_TYPES = List.of(
			SegmentType.TALK,
			SegmentType.MUSIC_AI,
			SegmentType.MUSIC_LOCAL,
			SegmentType.JINGLE);

	private final BroadcastArchiveRepository archiveRepository;
	private final GeneratedAssetRepository generatedAssetRepository;
	private final GeneratedAssetService generatedAssetService;
	private final StationProgrammingPolicyRepository policyRepository;

	public BroadcastArchiveService(
			BroadcastArchiveRepository archiveRepository,
			GeneratedAssetRepository generatedAssetRepository,
			GeneratedAssetService generatedAssetService,
			StationProgrammingPolicyRepository policyRepository) {
		this.archiveRepository = archiveRepository;
		this.generatedAssetRepository = generatedAssetRepository;
		this.generatedAssetService = generatedAssetService;
		this.policyRepository = policyRepository;
	}

	@Transactional
	public Optional<BroadcastArchiveEntity> promoteIfEligible(PlayHistoryEntity history, QueueItemEntity item) {
		if (!canPromote(history, item)) {
			return Optional.empty();
		}
		if (archiveRepository.existsBySourcePlayHistoryId(history.getId())) {
			return Optional.empty();
		}
		ReplayProfile replayProfile = replayProfile(history.getStationId());
		if (!isReplayEnabledFor(replayProfile, history.getSegmentType())) {
			return Optional.empty();
		}
		GeneratedAssetEntity primaryAsset = generatedAssetRepository.findById(item.getAssetId()).orElse(null);
		if (primaryAsset == null || !primaryAsset.isArchiveEligible()) {
			return Optional.empty();
		}

		BroadcastArchiveEntity archive = new BroadcastArchiveEntity();
		archive.setId(nextId());
		archive.setStationId(history.getStationId());
		archive.setSourcePlayHistoryId(history.getId());
		archive.setSegmentType(history.getSegmentType());
		archive.setTitle(history.getTitle());
		archive.setPrimaryAssetId(primaryAsset.getId());
		archive.setScriptAssetId(resolveScriptAssetId(item));
		archive.setArchiveStatus("ELIGIBLE");
		archive.setReplayWeight(replayWeight(history.getSegmentType(), replayProfile));
		archive.setReplayCount(0);
		archive.setEligibleFrom(Instant.now().plus(Math.max(0, replayProfile.minimumAssetAgeHours()), ChronoUnit.HOURS));
		archive.setExpiresAt(primaryAsset.getExpiresAt());
		archive.setMetadata(metadata(history, item, primaryAsset));
		try {
			return Optional.of(archiveRepository.save(archive));
		} catch (DataIntegrityViolationException exception) {
			return Optional.empty();
		}
	}

	@Transactional(readOnly = true)
	public Optional<BroadcastArchiveEntity> findReplayCandidate(String stationId, SegmentType segmentType) {
		ReplayProfile replayProfile = replayProfile(stationId);
		if (!isReplayEnabledFor(replayProfile, segmentType)) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		Instant cooldownCutoff = now.minus(Math.max(0, replayProfile.cooldownHours()), ChronoUnit.HOURS);
		return archiveRepository.findReplayCandidates(stationId, segmentType, now, cooldownCutoff).stream().findFirst();
	}

	@Transactional
	public void markReplayed(String sourcePlayHistoryId) {
		if (sourcePlayHistoryId == null || sourcePlayHistoryId.isBlank()) {
			return;
		}
		archiveRepository.findBySourcePlayHistoryId(sourcePlayHistoryId).ifPresent(archive -> {
			archive.setReplayCount(normalizeCount(archive.getReplayCount()) + 1);
			archive.setLastReplayedAt(Instant.now());
			archiveRepository.save(archive);
		});
	}

	private boolean canPromote(PlayHistoryEntity history, QueueItemEntity item) {
		if (history == null || item == null) {
			return false;
		}
		if (history.getResultStatus() != PlayHistoryResultStatus.DONE) {
			return false;
		}
		if (history.getSegmentType() == SegmentType.LETTER || item.getLetterId() != null) {
			return false;
		}
		if (item.getAssetId() == null || item.getAssetId().isBlank()) {
			return false;
		}
		return DEFAULT_ARCHIVE_TYPES.contains(history.getSegmentType());
	}

	private ReplayProfile replayProfile(String stationId) {
		return policyRepository.findByStationId(stationId)
				.map(policy -> ProgrammingPolicyProfileSupport.toReplayProfile(policy.getReplayPolicy()))
				.orElseGet(ProgrammingPolicyProfileSupport::defaultReplayProfile);
	}

	private boolean isReplayEnabledFor(ReplayProfile replayProfile, SegmentType segmentType) {
		if (replayProfile == null || segmentType == null || "OFF".equalsIgnoreCase(replayProfile.intensity())) {
			return false;
		}
		if (Boolean.TRUE.equals(replayProfile.excludeLetterSegments()) && segmentType == SegmentType.LETTER) {
			return false;
		}
		return replayProfile.eligibleSegmentTypes().contains(segmentType.name());
	}

	private String resolveScriptAssetId(QueueItemEntity item) {
		return generatedAssetService.findLatestScriptAssetForQueueItem(item.getId())
				.filter(asset -> asset.getAssetType() == GeneratedAssetType.SCRIPT)
				.map(GeneratedAssetEntity::getId)
				.orElse(null);
	}

	private int replayWeight(SegmentType segmentType, ReplayProfile replayProfile) {
		int intensityWeight = switch (replayProfile.intensity()) {
			case "HEAVY" -> 30;
			case "MEDIUM" -> 20;
			default -> 10;
		};
		int segmentWeight = switch (segmentType) {
			case MUSIC_AI, MUSIC_LOCAL -> 30;
			case JINGLE -> 15;
			case TALK -> 10;
			case LETTER -> 0;
		};
		return Math.max(1, intensityWeight + segmentWeight);
	}

	private Map<String, Object> metadata(PlayHistoryEntity history, QueueItemEntity item, GeneratedAssetEntity primaryAsset) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("queueItemId", item.getId());
		metadata.put("programBlockId", history.getProgramBlockId());
		metadata.put("programSlotId", history.getProgramSlotId());
		metadata.put("contentHash", primaryAsset.getContentHash());
		metadata.put("providerFingerprint", primaryAsset.getProviderFingerprint());
		metadata.put("durationMs", item.getDurationMs());
		metadata.put("contentOrigin", normalizeContentOrigin(history.getContentOrigin()));
		return metadata;
	}

	private String normalizeContentOrigin(String contentOrigin) {
		return contentOrigin == null || contentOrigin.isBlank() ? "LIVE_GEN" : contentOrigin;
	}

	private int normalizeCount(Integer replayCount) {
		return replayCount == null || replayCount < 0 ? 0 : replayCount;
	}

	private String nextId() {
		return "archive-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
