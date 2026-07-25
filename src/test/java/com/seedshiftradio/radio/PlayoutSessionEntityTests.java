package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayoutSessionEntityTests {

	@Test
	void onCreateDefaultsBufferReadyCount() {
		PlayoutSessionEntity entity = new PlayoutSessionEntity();

		entity.onCreate();

		assertEquals(0, entity.getBufferReadyCount());
		assertNotNull(entity.getStartedAt());
		assertNotNull(entity.getUpdatedAt());
	}

	@Test
	void preGenerationFactoryKeepsOffAirSessionSeparateFromLivePlayback() {
		PlayoutSessionEntity entity = PlayoutSessionEntity.preGeneration(
				"playout-pregen-001",
				"station-night",
				"pregen-001");

		assertTrue(entity.isPreGeneration());
		assertFalse(entity.isResumePlayback());
		assertEquals("ADMIN_PRE_GENERATION", entity.getRequestedBy());
	}
}
