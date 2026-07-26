package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class QueueItemEntityTests {

	@Test
	void preUpdateRepairsMissingCreatedAtFromAssignedIdMerge() {
		QueueItemEntity entity = new QueueItemEntity();
		entity.setContentOrigin(null);

		entity.onUpdate();

		assertNotNull(entity.getCreatedAt());
		assertNotNull(entity.getUpdatedAt());
		assertEquals("LIVE_GEN", entity.getContentOrigin());
	}
}
