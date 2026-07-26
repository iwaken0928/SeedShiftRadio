package com.seedshiftradio.management;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreGenerationJobTests {

	@Mock
	ManagementService managementService;

	@Test
	void failureIsPersistedWithTheOriginalException() {
		RuntimeException failure = new IllegalStateException("internal detail must not be logged");
		doThrow(failure).when(managementService).runPreGeneration("pregen-001");

		new PreGenerationJob(managementService).run("pregen-001");

		verify(managementService).markPreGenerationFailed("pregen-001", failure);
	}
}
