package com.seedshiftradio;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class SeedShiftRadioApplicationMigrationModeTests {

	@Test
	void closesApplicationContextWhenMigrationOnlyModeIsEnabled() {
		ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
		MockEnvironment environment = new MockEnvironment()
				.withProperty("seedshift.radio.migration-only", "true");
		when(context.getEnvironment()).thenReturn(environment);

		SeedShiftRadioApplication.closeAfterMigrationIfRequested(context);

		verify(context).close();
	}

	@Test
	void leavesApplicationContextRunningByDefault() {
		ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
		when(context.getEnvironment()).thenReturn(new MockEnvironment());

		SeedShiftRadioApplication.closeAfterMigrationIfRequested(context);

		verify(context, never()).close();
	}
}
