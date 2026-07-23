package com.seedshiftradio.settings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(
		prefix = "seedshift.radio.provider-job.recovery",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class ProviderJobRecoveryJob {

	private static final int MAX_BATCH_SIZE = 1_000;
	static final String RECOVERED_COUNTER_NAME = "seedshift.provider.jobs.recovered";
	static final String RECOVERY_FAILURE_COUNTER_NAME = "seedshift.provider.jobs.recovery.failures";

	private final ProviderJobService providerJobService;
	private final Duration staleAfter;
	private final int batchSize;
	private final Counter recoveredCounter;
	private final Counter recoveryFailureCounter;

	public ProviderJobRecoveryJob(
			ProviderJobService providerJobService,
			MeterRegistry meterRegistry,
			@Value("${seedshift.radio.provider-job.recovery.stale-after:15m}") Duration staleAfter,
			@Value("${seedshift.radio.provider-job.recovery.batch-size:100}") int batchSize) {
		this.providerJobService = providerJobService;
		if (staleAfter.isZero() || staleAfter.isNegative()) {
			throw new IllegalArgumentException("provider job の stale 判定時間は正の値である必要があります。");
		}
		this.staleAfter = staleAfter;
		this.batchSize = Math.max(1, Math.min(batchSize, MAX_BATCH_SIZE));
		this.recoveredCounter = Counter.builder(RECOVERED_COUNTER_NAME)
				.description("stale RUNNING から FAILED へ回収した provider job 件数")
				.register(meterRegistry);
		this.recoveryFailureCounter = Counter.builder(RECOVERY_FAILURE_COUNTER_NAME)
				.description("provider job recovery 処理が例外終了した回数")
				.register(meterRegistry);
	}

	@Scheduled(fixedDelayString = "${seedshift.radio.provider-job.recovery.fixed-delay:60s}")
	public void runScheduled() {
		runOnce();
	}

	public RecoveryResult runOnce() {
		return runOnce(Instant.now());
	}

	public RecoveryResult runOnce(Instant now) {
		try {
			Instant cutoff = now.minus(staleAfter);
			List<ProviderJobEntity> candidates = providerJobService.findStaleRunning(cutoff, batchSize);
			int recoveredCount = 0;
			for (ProviderJobEntity candidate : candidates) {
				if (providerJobService.failIfStaleRunning(candidate.getId(), cutoff, now)) {
					recoveredCount++;
					recoveredCounter.increment();
				}
			}
			return new RecoveryResult(now, cutoff, candidates.size(), recoveredCount);
		} catch (RuntimeException exception) {
			recoveryFailureCounter.increment();
			throw exception;
		}
	}

	public record RecoveryResult(
			Instant checkedAt,
			Instant cutoff,
			int scannedCount,
			int recoveredCount) {
	}
}
