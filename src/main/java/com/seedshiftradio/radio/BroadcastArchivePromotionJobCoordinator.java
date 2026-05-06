package com.seedshiftradio.radio;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BroadcastArchivePromotionJobCoordinator {

	private final ObjectProvider<JobScheduler> jobSchedulerProvider;
	private final PromoteArchiveCandidateJob promoteArchiveCandidateJob;
	private final boolean backgroundJobServerEnabled;

	public BroadcastArchivePromotionJobCoordinator(
			ObjectProvider<JobScheduler> jobSchedulerProvider,
			PromoteArchiveCandidateJob promoteArchiveCandidateJob,
			@Value("${jobrunr.background-job-server.enabled:false}") boolean backgroundJobServerEnabled) {
		this.jobSchedulerProvider = jobSchedulerProvider;
		this.promoteArchiveCandidateJob = promoteArchiveCandidateJob;
		this.backgroundJobServerEnabled = backgroundJobServerEnabled;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPromotionRequested(BroadcastArchivePromotionRequested event) {
		JobScheduler jobScheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && jobScheduler != null) {
			jobScheduler.enqueue(() -> promoteArchiveCandidateJob.run(
					event.playHistoryId(),
					event.queueItemId(),
					event.replayOfPlayHistoryId()));
			return;
		}
		promoteArchiveCandidateJob.run(
				event.playHistoryId(),
				event.queueItemId(),
				event.replayOfPlayHistoryId());
	}
}
