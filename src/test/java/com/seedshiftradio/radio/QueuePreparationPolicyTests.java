package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.programming.ProgrammingPolicyProfileSupport.PreGenerationProfile;
import com.seedshiftradio.settings.SettingsDocument;

class QueuePreparationPolicyTests {

	@Test
	void realTimeOnlyUsesMinimumReadyCountAndStationCaps() {
		QueuePreparationPolicy policy = QueuePreparationPolicy.resolve(
				playoutSettings(true),
				new PreGenerationProfile("REALTIME_ONLY", 1, 1, true),
				session(false, null),
				List.of());

		assertEquals(2, policy.targetReadyCount());
		assertEquals(60_000, policy.maxPreparedDurationMs());
		assertEquals(1, policy.maxPreparedBlocks());
		assertTrue(policy.shouldDeferNextBlockPlanning(1));
		assertFalse(policy.shouldDeferNextBlockPlanning(0));
	}

	@Test
	void aggressiveModeStartsPlanningWhenRemainingSlotsDropBelowThreshold() {
		QueuePreparationPolicy policy = QueuePreparationPolicy.resolve(
				playoutSettings(true),
				new PreGenerationProfile("AGGRESSIVE", 12, 4, true),
				session(false, null),
				List.of());

		assertTrue(policy.shouldDeferNextBlockPlanning(3));
		assertFalse(policy.shouldDeferNextBlockPlanning(2));
	}

	@Test
	void idlePrefetchDisabledLimitsAheadCountsAfterSafetyBufferIsReady() {
		QueuePreparationPolicy policy = QueuePreparationPolicy.resolve(
				playoutSettings(false),
				new PreGenerationProfile("ASSISTED", 12, 4, true),
				session(false, null),
				List.of(
						readyItem("queue-001", 45_000),
						readyItem("queue-002", 45_000)));

		assertEquals(1, policy.ttsAheadCount());
		assertEquals(1, policy.scriptAheadCount());
		assertEquals(1, policy.musicAheadCount());
		assertTrue(policy.hasReachedSafetyBuffer(2, 90_000));
		assertFalse(policy.hasReachedTargetBuffer(2, 90_000));
	}

	@Test
	void activePlaybackKeepsConfiguredAheadCountsEvenWhenIdlePrefetchIsDisabled() {
		QueuePreparationPolicy policy = QueuePreparationPolicy.resolve(
				playoutSettings(false),
				new PreGenerationProfile("ASSISTED", 12, 4, true),
				session(false, "queue-001"),
				List.of(
						readyItem("queue-001", 45_000),
						readyItem("queue-002", 45_000)));

		assertEquals(3, policy.ttsAheadCount());
		assertEquals(4, policy.scriptAheadCount());
		assertEquals(2, policy.musicAheadCount());
	}

	@Test
	void allowsLetterPrefetchOnlyForCurrentBlockAndFirstLetter() {
		QueuePreparationPolicy policy = QueuePreparationPolicy.resolve(
				playoutSettings(true),
				new PreGenerationProfile("ASSISTED", 12, 4, true),
				session(false, "queue-001", "block-current"),
				List.of());

		assertTrue(policy.allowsFutureSpokenPrefetch(
				session(false, "queue-001", "block-current"),
				readyItem("queue-letter-current", SegmentType.LETTER, "block-current", 45_000),
				0));
		assertFalse(policy.allowsFutureSpokenPrefetch(
				session(false, "queue-001", "block-current"),
				readyItem("queue-letter-next", SegmentType.LETTER, "block-next", 45_000),
				0));
		assertFalse(policy.allowsFutureSpokenPrefetch(
				session(false, "queue-001", "block-current"),
				readyItem("queue-letter-second", SegmentType.LETTER, "block-current", 45_000),
				1));
	}

	@Test
	void allowsMusicGenerationByPreGenerationModeAndBlockWindow() {
		QueueItemEntity currentBlockMusic = readyItem("queue-music-current", SegmentType.MUSIC_AI, "block-current", 45_000);
		QueueItemEntity nextBlockMusic = readyItem("queue-music-next", SegmentType.MUSIC_AI, "block-next", 45_000);

		QueuePreparationPolicy realTimeOnly = QueuePreparationPolicy.resolve(
				playoutSettings(true),
				new PreGenerationProfile("REALTIME_ONLY", 12, 4, true),
				session(false, "queue-001", "block-current"),
				List.of());
		assertTrue(realTimeOnly.allowsMusicGeneration(session(false, "queue-001", "block-current"), currentBlockMusic, 0));
		assertFalse(realTimeOnly.allowsMusicGeneration(session(false, "queue-001", "block-current"), nextBlockMusic, 0));

		QueuePreparationPolicy assisted = QueuePreparationPolicy.resolve(
				playoutSettings(true),
				new PreGenerationProfile("ASSISTED", 12, 4, true),
				session(false, "queue-001", "block-current"),
				List.of());
		assertTrue(assisted.allowsMusicGeneration(session(false, "queue-001", "block-current"), nextBlockMusic, 0));
		assertFalse(assisted.allowsMusicGeneration(session(false, "queue-001", "block-current"), nextBlockMusic, 1));

		QueuePreparationPolicy aggressive = QueuePreparationPolicy.resolve(
				playoutSettings(true),
				new PreGenerationProfile("AGGRESSIVE", 12, 4, true),
				session(false, "queue-001", "block-current"),
				List.of());
		assertTrue(aggressive.allowsMusicGeneration(session(false, "queue-001", "block-current"), nextBlockMusic, 0));
		assertTrue(aggressive.allowsMusicGeneration(session(false, "queue-001", "block-current"), nextBlockMusic, 2));
	}

	private static SettingsDocument.PlayoutSettings playoutSettings(boolean idlePrefetchEnabled) {
		return new SettingsDocument.PlayoutSettings(4, 2, 90_000, 480_000, 2, 4, 3, 2, idlePrefetchEnabled);
	}

	private static PlayoutSessionEntity session(boolean resumePlayback, String currentQueueItemId) {
		return session(resumePlayback, currentQueueItemId, null);
	}

	private static PlayoutSessionEntity session(boolean resumePlayback, String currentQueueItemId, String currentProgramBlockId) {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setResumePlayback(resumePlayback);
		session.setCurrentQueueItemId(currentQueueItemId);
		session.setCurrentProgramBlockId(currentProgramBlockId);
		return session;
	}

	private static QueueItemEntity readyItem(String id, int durationMs) {
		return readyItem(id, SegmentType.TALK, null, durationMs);
	}

	private static QueueItemEntity readyItem(String id, SegmentType segmentType, String programBlockId, int durationMs) {
		QueueItemEntity item = new QueueItemEntity();
		item.setId(id);
		item.setStatus(QueueItemStatus.READY);
		item.setSegmentType(segmentType);
		item.setProgramBlockId(programBlockId);
		item.setDurationMs(durationMs);
		return item;
	}
}
