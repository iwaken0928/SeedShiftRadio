package com.seedshiftradio.radio;

import java.util.List;

import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.PreGenerationProfile;
import com.seedshiftradio.settings.SettingsDocument;

final class QueuePreparationPolicy {

	private static final int DEFAULT_REMAINING_SLOT_THRESHOLD = 2;
	private static final int AGGRESSIVE_REMAINING_SLOT_THRESHOLD = 3;
	private static final int CURRENT_BLOCK_LETTER_AHEAD_LIMIT = 1;
	private static final int ASSISTED_NEXT_BLOCK_MUSIC_AHEAD_LIMIT = 1;

	private final int minimumReadyCount;
	private final int minReadyDurationMs;
	private final int targetReadyCount;
	private final int maxPreparedDurationMs;
	private final int maxPreparedBlocks;
	private final int ttsAheadCount;
	private final int scriptAheadCount;
	private final int musicAheadCount;
	private final String preGenerationMode;

	private QueuePreparationPolicy(
			int minimumReadyCount,
			int minReadyDurationMs,
			int targetReadyCount,
			int maxPreparedDurationMs,
			int maxPreparedBlocks,
			int ttsAheadCount,
			int scriptAheadCount,
			int musicAheadCount,
			String preGenerationMode) {
		this.minimumReadyCount = minimumReadyCount;
		this.minReadyDurationMs = minReadyDurationMs;
		this.targetReadyCount = targetReadyCount;
		this.maxPreparedDurationMs = maxPreparedDurationMs;
		this.maxPreparedBlocks = maxPreparedBlocks;
		this.ttsAheadCount = ttsAheadCount;
		this.scriptAheadCount = scriptAheadCount;
		this.musicAheadCount = musicAheadCount;
		this.preGenerationMode = preGenerationMode;
	}

	static QueuePreparationPolicy resolve(
			SettingsDocument.PlayoutSettings playout,
			PreGenerationProfile preGeneration,
			PlayoutSessionEntity session,
			List<QueueItemEntity> queueItems) {
		boolean limitIdlePrefetch = shouldLimitIdlePrefetch(playout, session, queueItems);
		int minimumReadyCount = Math.max(0, playout.minimumReadyCount());
		int targetReadyCount = "REALTIME_ONLY".equals(preGeneration.mode())
				? Math.max(1, minimumReadyCount)
				: Math.max(1, playout.targetReadyCount());
		long stationCapMs = preGeneration.maxPreparedMinutes() == null || preGeneration.maxPreparedMinutes() <= 0
				? Long.MAX_VALUE
				: preGeneration.maxPreparedMinutes() * 60_000L;
		int maxPreparedDurationMs = (int) Math.min(playout.maxPreparedDurationMs(), Math.min(Integer.MAX_VALUE, stationCapMs));
		int globalBlockCap = Math.max(1, playout.maxPreparedBlocks());
		int stationBlockCap = preGeneration.maxPreparedBlocks() == null
				? globalBlockCap
				: Math.max(1, preGeneration.maxPreparedBlocks());
		int ttsAheadCount = limitIdlePrefetch ? 1 : Math.max(1, playout.ttsAheadCount());
		int scriptAheadCount = limitIdlePrefetch ? ttsAheadCount : Math.max(ttsAheadCount, playout.scriptAheadCount());
		int musicAheadCount = limitIdlePrefetch ? 1 : Math.max(1, playout.musicAheadCount());
		return new QueuePreparationPolicy(
				minimumReadyCount,
				playout.minReadyDurationMs(),
				targetReadyCount,
				maxPreparedDurationMs,
				Math.min(globalBlockCap, stationBlockCap),
				ttsAheadCount,
				scriptAheadCount,
				musicAheadCount,
				preGeneration.mode());
	}

	int targetReadyCount() {
		return targetReadyCount;
	}

	int maxPreparedDurationMs() {
		return maxPreparedDurationMs;
	}

	int maxPreparedBlocks() {
		return maxPreparedBlocks;
	}

	int ttsAheadCount() {
		return ttsAheadCount;
	}

	int scriptAheadCount() {
		return scriptAheadCount;
	}

	int musicAheadCount() {
		return musicAheadCount;
	}

	boolean allowsFutureSpokenPrefetch(
			PlayoutSessionEntity session,
			QueueItemEntity item,
			int preparedCurrentBlockLetterCount) {
		if (item.getSegmentType() != SegmentType.LETTER) {
			return true;
		}
		if (preparedCurrentBlockLetterCount >= CURRENT_BLOCK_LETTER_AHEAD_LIMIT) {
			return false;
		}
		return session.getCurrentProgramBlockId() != null
				&& session.getCurrentProgramBlockId().equals(item.getProgramBlockId());
	}

	boolean allowsMusicGeneration(
			PlayoutSessionEntity session,
			QueueItemEntity item,
			int preparedFutureBlockMusicCount) {
		String currentProgramBlockId = session.getCurrentProgramBlockId();
		if (currentProgramBlockId == null
				|| currentProgramBlockId.equals(item.getProgramBlockId())) {
			return true;
		}
		return switch (preGenerationMode) {
			case "REALTIME_ONLY" -> false;
			case "ASSISTED" -> preparedFutureBlockMusicCount < ASSISTED_NEXT_BLOCK_MUSIC_AHEAD_LIMIT;
			default -> true;
		};
	}

	boolean hasReachedSafetyBuffer(long readyCount, int readyDurationMs) {
		return readyCount >= minimumReadyCount && readyDurationMs >= minReadyDurationMs;
	}

	boolean hasReachedTargetBuffer(long readyCount, int readyDurationMs) {
		return readyCount >= targetReadyCount && readyDurationMs >= minReadyDurationMs;
	}

	boolean hasReachedPreparedDurationLimit(int readyDurationMs) {
		return readyDurationMs >= maxPreparedDurationMs;
	}

	boolean shouldDeferNextBlockPlanning(long remainingSlotCount) {
		if ("REALTIME_ONLY".equals(preGenerationMode)) {
			return remainingSlotCount > 0;
		}
		return remainingSlotCount >= remainingSlotThreshold();
	}

	private int remainingSlotThreshold() {
		return switch (preGenerationMode) {
			case "AGGRESSIVE" -> AGGRESSIVE_REMAINING_SLOT_THRESHOLD;
			default -> DEFAULT_REMAINING_SLOT_THRESHOLD;
		};
	}

	private static boolean shouldLimitIdlePrefetch(
			SettingsDocument.PlayoutSettings playout,
			PlayoutSessionEntity session,
			List<QueueItemEntity> queueItems) {
		if (playout.idlePrefetchEnabled()
				|| session.getCurrentQueueItemId() != null
				|| session.isResumePlayback()) {
			return false;
		}
		long readyCount = queueItems.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.count();
		int readyDuration = queueItems.stream()
				.filter(item -> item.getStatus() == QueueItemStatus.READY)
				.mapToInt(QueueItemEntity::getDurationMs)
				.sum();
		return readyCount >= Math.max(0, playout.minimumReadyCount())
				&& readyDuration >= playout.minReadyDurationMs();
	}
}
