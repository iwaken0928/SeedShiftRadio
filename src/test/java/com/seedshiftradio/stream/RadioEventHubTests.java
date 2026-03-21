package com.seedshiftradio.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.seedshiftradio.radio.RadioEventRecord;

class RadioEventHubTests {

	@Test
	void replayAfterReturnsEventsAfterSpecifiedId() {
		RadioEventHub hub = new RadioEventHub();
		RadioEventRecord first = hub.publish("radio.status.changed", "first");
		hub.publish("queue.updated", "second");

		List<RadioEventRecord> replay = hub.replayAfter(first.id());

		assertEquals(1, replay.size());
		assertEquals("queue.updated", replay.getFirst().eventType());
		assertFalse(hub.latestEventId().isBlank());
	}
}
