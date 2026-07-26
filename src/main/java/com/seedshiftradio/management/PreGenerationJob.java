package com.seedshiftradio.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PreGenerationJob {

	private static final Logger LOGGER = LoggerFactory.getLogger(PreGenerationJob.class);

	private final ManagementService managementService;

	public PreGenerationJob(ManagementService managementService) {
		this.managementService = managementService;
	}

	public void run(String requestId) {
		try {
			managementService.runPreGeneration(requestId);
		} catch (RuntimeException exception) {
			LOGGER.error(
					"pre_generation_failed requestId={} exceptionType={} failureLocation={}",
					requestId,
					exception.getClass().getName(),
					failureLocation(exception));
			managementService.markPreGenerationFailed(requestId, exception);
		}
	}

	private static String failureLocation(Throwable exception) {
		Throwable current = exception;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		StackTraceElement[] stackTrace = current.getStackTrace();
		if (stackTrace.length == 0) {
			return current.getClass().getName();
		}
		StackTraceElement first = stackTrace[0];
		return first.getClassName() + "#" + first.getMethodName() + ":" + first.getLineNumber();
	}
}
