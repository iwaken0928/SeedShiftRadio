package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
