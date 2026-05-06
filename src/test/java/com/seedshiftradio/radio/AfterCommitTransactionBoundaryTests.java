package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AfterCommitTransactionBoundaryTests {

	@Test
	void radioAfterCommitEntryPointsUseRequiresNewTransactions() throws NoSuchMethodException {
		assertRequiresNew(RadioService.class.getMethod("warmupQueue", String.class));
		assertRequiresNew(RadioService.class.getMethod("refillQueue", String.class));
		assertRequiresNew(RadioService.class.getMethod("synchronizeSessionAfterAsyncUpdate", String.class));
		assertRequiresNew(RadioService.class.getMethod("handleAsyncGenerationFailure", String.class, String.class));
		assertRequiresNew(RadioService.class.getMethod("handleAsyncGenerationFailure", String.class, String.class, String.class));
	}

	@Test
	void archivePromotionJobUsesRequiresNewTransaction() throws NoSuchMethodException {
		assertRequiresNew(PromoteArchiveCandidateJob.class.getMethod("run", String.class, String.class, String.class));
	}

	private void assertRequiresNew(Method method) {
		Transactional transactional = method.getAnnotation(Transactional.class);
		assertNotNull(transactional, () -> method.getName() + " must be transactional");
		assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(), () -> method.getName() + " must use REQUIRES_NEW");
	}
}
