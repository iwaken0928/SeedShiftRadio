package com.seedshiftradio.radio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.settings.GpuExecutionCoordinator.ExecutionOrigin;
import com.seedshiftradio.settings.MusicGenWorkerException;
import com.seedshiftradio.settings.MusicGenerationRuntimeService;

@ExtendWith(MockitoExtension.class)
class GenerateMusicJobTests {

	@Mock
	QueueItemRepository queueItemRepository;

	@Mock
	PlayoutSessionRepository playoutSessionRepository;

	@Mock
	MusicGenerationRuntimeService musicGenerationRuntimeService;

	@Mock
	RadioService radioService;

	GenerateMusicJob generateMusicJob;

	@BeforeEach
	void setUp() {
		generateMusicJob = new GenerateMusicJob(
				queueItemRepository,
				playoutSessionRepository,
				musicGenerationRuntimeService,
				radioService);
	}

	@Test
	void runMarksQueueItemReadyWhenWorkerSucceeds() {
		QueueItemEntity item = new QueueItemEntity();
		item.setId("queue-1");
		item.setSessionId("playout-1");
		item.setStatus(QueueItemStatus.GENERATING);
		item.setDurationMs(120_000);

		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("playout-1");
		session.setStationId("station-night");

		when(queueItemRepository.findById("queue-1")).thenReturn(Optional.of(item), Optional.of(item));
		when(playoutSessionRepository.findById("playout-1")).thenReturn(Optional.of(session));
		when(musicGenerationRuntimeService.generate(
				eq("station-night"),
				any(QueueItemEntity.class),
				eq(ExecutionOrigin.AUTOMATIC)))
				.thenReturn(new MusicGenerationRuntimeService.GeneratedMusicAsset(
						"asset-1",
						"/api/assets/audio/asset-1.wav",
						"provider-job-1",
						"worker-job-1",
						"CACHE_REUSED",
						117));

		generateMusicJob.run("queue-1", "corr-1");

		verify(queueItemRepository).save(item);
		org.junit.jupiter.api.Assertions.assertEquals("CACHE_REUSED", item.getContentOrigin());
		org.junit.jupiter.api.Assertions.assertEquals(117_000, item.getDurationMs());
		verify(radioService).synchronizeSessionAfterAsyncUpdate("playout-1");
		verify(radioService, never()).handleAsyncGenerationFailure(any(), any());
	}

	@Test
	void runMarksQueueItemFailedWhenWorkerFails() {
		QueueItemEntity item = new QueueItemEntity();
		item.setId("queue-1");
		item.setSessionId("playout-1");
		item.setStatus(QueueItemStatus.GENERATING);

		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("playout-1");
		session.setStationId("station-night");

		when(queueItemRepository.findById("queue-1")).thenReturn(Optional.of(item), Optional.of(item));
		when(playoutSessionRepository.findById("playout-1")).thenReturn(Optional.of(session));
		when(musicGenerationRuntimeService.generate(
				eq("station-night"),
				any(QueueItemEntity.class),
				eq(ExecutionOrigin.AUTOMATIC)))
				.thenThrow(new MusicGenWorkerException("PROVIDER_TIMEOUT", "worker timeout"));

		generateMusicJob.run("queue-1", "corr-1");

		verify(queueItemRepository, never()).save(any(QueueItemEntity.class));
		verify(radioService).handleAsyncGenerationFailure("playout-1", "queue-1", "PROVIDER_TIMEOUT");
		verify(radioService, never()).synchronizeSessionAfterAsyncUpdate(any());
	}

	@Test
	void preGenerationSuccessDoesNotSynchronizeLiveRadioSession() {
		QueueItemEntity item = new QueueItemEntity();
		item.setId("queue-pregen");
		item.setSessionId("playout-pregen");
		item.setStatus(QueueItemStatus.GENERATING);
		PlayoutSessionEntity session = PlayoutSessionEntity.preGeneration(
				"playout-pregen",
				"station-night",
				"pregen-1");

		when(queueItemRepository.findById("queue-pregen")).thenReturn(Optional.of(item), Optional.of(item));
		when(playoutSessionRepository.findById("playout-pregen")).thenReturn(Optional.of(session));
		when(musicGenerationRuntimeService.generate(
				eq("station-night"),
				any(QueueItemEntity.class),
				eq(ExecutionOrigin.MANUAL)))
				.thenReturn(new MusicGenerationRuntimeService.GeneratedMusicAsset(
						"asset-pregen",
						"/api/assets/audio/asset-pregen.wav",
						"provider-job-pregen",
						"worker-job-pregen",
						"LIVE_GEN",
						120));

		generateMusicJob.run("queue-pregen", "pregen-1");

		org.junit.jupiter.api.Assertions.assertEquals(QueueItemStatus.READY, item.getStatus());
		verify(queueItemRepository).save(item);
		verify(radioService, never()).synchronizeSessionAfterAsyncUpdate(any());
		verify(radioService, never()).handleAsyncGenerationFailure(any(), any(), any());
	}

	@Test
	void completedAssetIsDiscardedWhenContentWasDeletedDuringGeneration() {
		QueueItemEntity generatingItem = new QueueItemEntity();
		generatingItem.setId("queue-pregen");
		generatingItem.setSessionId("playout-pregen");
		generatingItem.setStatus(QueueItemStatus.GENERATING);
		QueueItemEntity deletedItem = new QueueItemEntity();
		deletedItem.setId("queue-pregen");
		deletedItem.setSessionId("playout-pregen");
		deletedItem.setStatus(QueueItemStatus.PLANNED);
		PlayoutSessionEntity session = PlayoutSessionEntity.preGeneration(
				"playout-pregen",
				"station-night",
				"pregen-1");

		when(queueItemRepository.findById("queue-pregen"))
				.thenReturn(Optional.of(generatingItem), Optional.of(deletedItem));
		when(playoutSessionRepository.findById("playout-pregen")).thenReturn(Optional.of(session));
		when(musicGenerationRuntimeService.generate(
				eq("station-night"),
				any(QueueItemEntity.class),
				eq(ExecutionOrigin.MANUAL)))
				.thenReturn(new MusicGenerationRuntimeService.GeneratedMusicAsset(
						"asset-late",
						"/api/assets/audio/asset-late.wav",
						"provider-job-late",
						"worker-job-late",
						"LIVE_GEN",
						120));

		generateMusicJob.run("queue-pregen", "pregen-1");

		verify(musicGenerationRuntimeService).discardGeneratedAsset("asset-late");
		verify(queueItemRepository, never()).save(any(QueueItemEntity.class));
		verify(radioService, never()).synchronizeSessionAfterAsyncUpdate(any());
	}
}
