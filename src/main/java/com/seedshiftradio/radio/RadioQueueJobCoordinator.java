package com.seedshiftradio.radio;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RadioQueueJobCoordinator {

	private final ObjectProvider<JobScheduler> jobSchedulerProvider;
	private final RadioService radioService;
	private final boolean backgroundJobServerEnabled;

	public RadioQueueJobCoordinator(
			ObjectProvider<JobScheduler> jobSchedulerProvider,
			RadioService radioService,
			@Value("${jobrunr.background-job-server.enabled:false}") boolean backgroundJobServerEnabled) {
		this.jobSchedulerProvider = jobSchedulerProvider;
		this.radioService = radioService;
		this.backgroundJobServerEnabled = backgroundJobServerEnabled;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onWarmupRequested(QueueWarmupRequested event) {
		JobScheduler jobScheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && jobScheduler != null) {
			jobScheduler.enqueue(() -> radioService.warmupQueue(event.sessionId()));
			return;
		}
		radioService.warmupQueue(event.sessionId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRefillRequested(QueueRefillRequested event) {
		JobScheduler jobScheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && jobScheduler != null) {
			jobScheduler.enqueue(() -> radioService.refillQueue(event.sessionId()));
			return;
		}
		radioService.refillQueue(event.sessionId());
	}
}
