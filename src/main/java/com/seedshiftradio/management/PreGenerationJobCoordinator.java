package com.seedshiftradio.management;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PreGenerationJobCoordinator {

	private final ObjectProvider<JobScheduler> jobSchedulerProvider;
	private final PreGenerationJob preGenerationJob;
	private final boolean backgroundJobServerEnabled;

	public PreGenerationJobCoordinator(
			ObjectProvider<JobScheduler> jobSchedulerProvider,
			PreGenerationJob preGenerationJob,
			@Value("${jobrunr.background-job-server.enabled:false}") boolean backgroundJobServerEnabled) {
		this.jobSchedulerProvider = jobSchedulerProvider;
		this.preGenerationJob = preGenerationJob;
		this.backgroundJobServerEnabled = backgroundJobServerEnabled;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPreGenerationRequested(PreGenerationRequested event) {
		JobScheduler scheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && scheduler != null) {
			scheduler.enqueue(() -> preGenerationJob.run(event.requestId()));
			return;
		}
		preGenerationJob.run(event.requestId());
	}
}
