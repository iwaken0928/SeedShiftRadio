package com.seedshiftradio.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.letter.LetterChangedEvent;
import com.seedshiftradio.letter.LetterDtos.LetterReplySummary;
import com.seedshiftradio.letter.LetterDtos.LetterSummaryResponse;
import com.seedshiftradio.radio.RadioEventRecord;

class StreamEventServiceTests {

	@Test
	void replayAfterReturnsEventsAfterSpecifiedId() {
		StreamEventService service = new StreamEventService();
		service.publish("radio.status.changed", "first");
		service.publish("queue.updated", "second");

		List<RadioEventRecord> replay = service.replayAfter("1");

		assertEquals(1, replay.size());
		assertEquals("queue.updated", replay.getFirst().eventType());
	}

	@Test
	void letterChangedPublishesLetterSummaryPayload() {
		StreamEventService service = new StreamEventService();
		LetterSummaryResponse summary = new LetterSummaryResponse(
				"letter-001",
				"station-night",
				"夜更かしペンギン",
				"最近の作業BGM",
				LetterStatus.UNREAD,
				null,
				Instant.parse("2026-03-20T09:00:00Z"),
				List.of(new LetterReplySummary("reply-001", "ありがとうございます。", Instant.parse("2026-03-20T10:00:00Z"))));

		service.onLetterChanged(new LetterChangedEvent(summary));

		List<RadioEventRecord> replay = service.replayAfter("0");
		assertEquals(1, replay.size());
		assertEquals("letter.updated", replay.getFirst().eventType());
		assertEquals(summary, replay.getFirst().payload());
	}
}
