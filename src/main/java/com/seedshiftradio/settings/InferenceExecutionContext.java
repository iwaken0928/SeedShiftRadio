package com.seedshiftradio.settings;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 同一スレッド内の provider 呼び出しへ、手動/自動ジョブの実行ポリシーを引き渡す。
 */
public final class InferenceExecutionContext {

	private static final ThreadLocal<SettingsDocument.JobExecutionPolicy> ACTIVE_POLICY = new ThreadLocal<>();

	private InferenceExecutionContext() {
	}

	public static <T> T withPolicy(SettingsDocument.JobExecutionPolicy policy, Supplier<T> action) {
		SettingsDocument.JobExecutionPolicy previous = ACTIVE_POLICY.get();
		ACTIVE_POLICY.set(policy);
		try {
			return action.get();
		} finally {
			if (previous == null) {
				ACTIVE_POLICY.remove();
			} else {
				ACTIVE_POLICY.set(previous);
			}
		}
	}

	public static Duration modelLoadTimeout(Duration fallback) {
		SettingsDocument.JobExecutionPolicy policy = ACTIVE_POLICY.get();
		return policy == null ? fallback : Duration.ofSeconds(policy.modelLoadTimeoutSeconds());
	}

	public static Duration jobTimeout(Duration fallback) {
		SettingsDocument.JobExecutionPolicy policy = ACTIVE_POLICY.get();
		return policy == null ? fallback : Duration.ofSeconds(policy.jobTimeoutSeconds());
	}

	public static long pollIntervalMillis(long fallback) {
		SettingsDocument.JobExecutionPolicy policy = ACTIVE_POLICY.get();
		return policy == null ? fallback : policy.pollIntervalMillis();
	}
}
