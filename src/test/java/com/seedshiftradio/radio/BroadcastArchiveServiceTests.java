package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.programming.StationProgrammingPolicyRepository;
import com.seedshiftradio.settings.GeneratedAssetEntity;
import com.seedshiftradio.settings.GeneratedAssetRepository;
import com.seedshiftradio.settings.GeneratedAssetService;

@ExtendWith(MockitoExtension.class)
class BroadcastArchiveServiceTests {

	@Mock
	BroadcastArchiveRepository archiveRepository;

	@Mock
	GeneratedAssetRepository generatedAssetRepository;

	@Mock
	GeneratedAssetService generatedAssetService;

	@Mock
	StationProgrammingPolicyRepository policyRepository;

	BroadcastArchiveService service;

	@BeforeEach
	void setUp() {
		service = new BroadcastArchiveService(
				archiveRepository,
				generatedAssetRepository,
				generatedAssetService,
				policyRepository);
	}

	@Test
	void promoteIfEligibleCreatesArchiveForSafeCompletedMusic() {
		PlayHistoryEntity history = playHistory("play-history-001", SegmentType.MUSIC_AI, PlayHistoryResultStatus.DONE);
		QueueItemEntity item = queueItem("queue-001", SegmentType.MUSIC_AI);
		item.setAssetId("asset-001");
		GeneratedAssetEntity asset = generatedAsset("asset-001", true);
		when(policyRepository.findByStationId("station-night")).thenReturn(Optional.empty());
		when(archiveRepository.existsBySourcePlayHistoryId("play-history-001")).thenReturn(false);
		when(generatedAssetRepository.findById("asset-001")).thenReturn(Optional.of(asset));
		when(generatedAssetService.findLatestScriptAssetForQueueItem("queue-001")).thenReturn(Optional.empty());
		when(archiveRepository.save(any(BroadcastArchiveEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Optional<BroadcastArchiveEntity> promoted = service.promoteIfEligible(history, item);

		assertTrue(promoted.isPresent());
		ArgumentCaptor<BroadcastArchiveEntity> captor = ArgumentCaptor.forClass(BroadcastArchiveEntity.class);
		verify(archiveRepository).save(captor.capture());
		BroadcastArchiveEntity archive = captor.getValue();
		assertEquals("station-night", archive.getStationId());
		assertEquals("play-history-001", archive.getSourcePlayHistoryId());
		assertEquals(SegmentType.MUSIC_AI, archive.getSegmentType());
		assertEquals("asset-001", archive.getPrimaryAssetId());
		assertEquals("ELIGIBLE", archive.getArchiveStatus());
		assertEquals(0, archive.getReplayCount());
		assertEquals("queue-001", archive.getMetadata().get("queueItemId"));
	}

	@Test
	void promoteIfEligibleSkipsLettersEvenWhenAssetAllowsArchive() {
		PlayHistoryEntity history = playHistory("play-history-001", SegmentType.LETTER, PlayHistoryResultStatus.DONE);
		QueueItemEntity item = queueItem("queue-001", SegmentType.LETTER);
		item.setAssetId("asset-001");
		item.setLetterId("letter-001");

		Optional<BroadcastArchiveEntity> promoted = service.promoteIfEligible(history, item);

		assertFalse(promoted.isPresent());
		verifyNoInteractions(archiveRepository, generatedAssetRepository);
	}

	@Test
	void markReplayedIncrementsReplayMetadata() {
		BroadcastArchiveEntity archive = new BroadcastArchiveEntity();
		archive.setId("archive-001");
		archive.setSourcePlayHistoryId("play-history-001");
		archive.setReplayCount(2);
		when(archiveRepository.findBySourcePlayHistoryId("play-history-001")).thenReturn(Optional.of(archive));

		service.markReplayed("play-history-001");

		assertEquals(3, archive.getReplayCount());
		assertTrue(archive.getLastReplayedAt() != null);
		verify(archiveRepository).save(archive);
	}

	private PlayHistoryEntity playHistory(String id, SegmentType segmentType, PlayHistoryResultStatus status) {
		PlayHistoryEntity history = new PlayHistoryEntity();
		history.setId(id);
		history.setSessionId("playout-001");
		history.setStationId("station-night");
		history.setQueueItemId("queue-001");
		history.setProgramBlockId("program-001");
		history.setProgramSlotId("slot-001");
		history.setSegmentType(segmentType);
		history.setTitle("放送済みセグメント");
		history.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		history.setResultStatus(status);
		history.setCorrelationId("corr-001");
		history.setContentOrigin("LIVE_GEN");
		history.setPlayedAt(Instant.parse("2026-03-20T09:00:00Z"));
		return history;
	}

	private QueueItemEntity queueItem(String id, SegmentType segmentType) {
		QueueItemEntity item = new QueueItemEntity();
		item.setId(id);
		item.setSessionId("playout-001");
		item.setSequenceNo(1);
		item.setSegmentType(segmentType);
		item.setStatus(QueueItemStatus.DONE);
		item.setProgramBlockId("program-001");
		item.setProgramSlotId("slot-001");
		item.setSlotRole(SlotRole.TOPIC);
		item.setTitle("放送済みセグメント");
		item.setPlaybackMode(PlaybackMode.SERVER_AUDIO);
		item.setDurationMs(30_000);
		item.setCorrelationId("corr-001");
		item.setContentOrigin("LIVE_GEN");
		return item;
	}

	private GeneratedAssetEntity generatedAsset(String id, boolean archiveEligible) {
		GeneratedAssetEntity asset = org.mockito.Mockito.mock(GeneratedAssetEntity.class);
		when(asset.getId()).thenReturn(id);
		when(asset.getContentHash()).thenReturn("content-hash");
		when(asset.getProviderFingerprint()).thenReturn("provider:fingerprint");
		when(asset.getExpiresAt()).thenReturn(Instant.parse("2026-03-27T09:00:00Z"));
		when(asset.isArchiveEligible()).thenReturn(archiveEligible);
		return asset;
	}
}
