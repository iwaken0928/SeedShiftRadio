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
	private final WarmupQueueJob warmupQueueJob;
	private final QueueRefillJob queueRefillJob;
	private final GenerateMusicJob generateMusicJob;
	private final boolean backgroundJobServerEnabled;

	public RadioQueueJobCoordinator(
			ObjectProvider<JobScheduler> jobSchedulerProvider,
			WarmupQueueJob warmupQueueJob,
			QueueRefillJob queueRefillJob,
			GenerateMusicJob generateMusicJob,
			@Value("${jobrunr.background-job-server.enabled:false}") boolean backgroundJobServerEnabled) {
		this.jobSchedulerProvider = jobSchedulerProvider;
		this.warmupQueueJob = warmupQueueJob;
		this.queueRefillJob = queueRefillJob;
		this.generateMusicJob = generateMusicJob;
		this.backgroundJobServerEnabled = backgroundJobServerEnabled;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onWarmupRequested(QueueWarmupRequested event) {
		JobScheduler jobScheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && jobScheduler != null) {
			jobScheduler.enqueue(() -> warmupQueueJob.run(event.sessionId()));
			return;
		}
		warmupQueueJob.run(event.sessionId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRefillRequested(QueueRefillRequested event) {
		JobScheduler jobScheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && jobScheduler != null) {
			jobScheduler.enqueue(() -> queueRefillJob.run(event.sessionId()));
			return;
		}
		queueRefillJob.run(event.sessionId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onGenerateMusicRequested(GenerateMusicRequested event) {
		JobScheduler jobScheduler = jobSchedulerProvider.getIfAvailable();
		if (backgroundJobServerEnabled && jobScheduler != null) {
			jobScheduler.enqueue(() -> generateMusicJob.run(event.queueItemId(), event.correlationId()));
			return;
		}
		generateMusicJob.run(event.queueItemId(), event.correlationId());
	}
}
