package com.seedshiftradio.radio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class BroadcastArchivePromotionJobCoordinatorTests {

	@Mock
	ObjectProvider<JobScheduler> jobSchedulerProvider;

	@Mock
	JobScheduler jobScheduler;

	@Mock
	PromoteArchiveCandidateJob promoteArchiveCandidateJob;

	BroadcastArchivePromotionRequested event;

	@BeforeEach
	void setUp() {
		event = new BroadcastArchivePromotionRequested(
				"play-history-001",
				"queue-001",
				"play-history-source");
	}

	@Test
	void onPromotionRequestedRunsInlineWhenBackgroundJobServerIsDisabled() {
		BroadcastArchivePromotionJobCoordinator coordinator = new BroadcastArchivePromotionJobCoordinator(
				jobSchedulerProvider,
				promoteArchiveCandidateJob,
				false);

		coordinator.onPromotionRequested(event);

		verify(promoteArchiveCandidateJob).run("play-history-001", "queue-001", "play-history-source");
		verify(jobSchedulerProvider).getIfAvailable();
	}

	@Test
	void onPromotionRequestedEnqueuesJobWhenBackgroundJobServerIsEnabled() {
		when(jobSchedulerProvider.getIfAvailable()).thenReturn(jobScheduler);
		BroadcastArchivePromotionJobCoordinator coordinator = new BroadcastArchivePromotionJobCoordinator(
				jobSchedulerProvider,
				promoteArchiveCandidateJob,
				true);

		coordinator.onPromotionRequested(event);

		verify(jobScheduler).enqueue(any(JobLambda.class));
		verify(promoteArchiveCandidateJob, never()).run(any(), any(), any());
	}
}
