package com.seedshiftradio.letter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicLookupRequest;
import com.seedshiftradio.radio.PlayHistoryEntity;
import com.seedshiftradio.radio.PlayHistoryRepository;

@ExtendWith(MockitoExtension.class)
class LetterPublicServiceTests {

	@Mock
	LetterRepository letterRepository;

	@Mock
	PlayHistoryRepository playHistoryRepository;

	LetterPublicService letterPublicService;

	@BeforeEach
	void setUp() {
		letterPublicService = new LetterPublicService(letterRepository, playHistoryRepository);
	}

	@Test
	void lookupIgnoresUnknownIdsAndPreservesRequestOrder() {
		LetterEntity first = new LetterEntity("letter-001", "station-night", "夜更かしペンギン", "最近の作業BGM", "body-1", LetterStatus.ADOPTED, null);
		first.setAdoptedInSessionId("playout-001");
		first.setCreatedAt(Instant.parse("2026-03-20T09:00:00Z"));
		LetterEntity second = new LetterEntity("letter-002", null, "深夜ラジオ", "おたより", "body-2", LetterStatus.REPLIED, null);
		second.setCreatedAt(Instant.parse("2026-03-20T10:00:00Z"));
		when(letterRepository.findAllById(List.of("letter-002", "missing", "letter-001"))).thenReturn(List.of(first, second));
		when(playHistoryRepository.findByLetterIdInOrderByPlayedAtDesc(List.of("letter-002", "missing", "letter-001")))
				.thenReturn(List.of(buildHistory("play-2", "letter-002", "session-2", "station-night", SegmentType.LETTER, PlayHistoryResultStatus.DONE, "2026-03-20T10:30:00Z")));

		var response = letterPublicService.lookup(new LetterPublicLookupRequest(List.of("letter-002", "missing", "letter-001", "letter-002", "  ")));

		assertEquals(2, response.letters().size());
		assertEquals("letter-002", response.letters().get(0).id());
		assertEquals("深夜ラジオ", response.letters().get(0).radioName());
		assertEquals("letter-001", response.letters().get(1).id());
		assertEquals("夜更かしペンギン", response.letters().get(1).radioName());
		assertEquals(0, response.letters().get(1).playHistory().size());
		assertEquals("station-night", response.letters().get(0).playHistory().getFirst().stationId());
	}

	@Test
	void lookupRejectsTooManyIds() {
		List<String> ids = java.util.stream.IntStream.range(0, 51)
				.mapToObj(index -> "letter-" + index)
				.toList();

		ApiException exception = assertThrows(ApiException.class, () -> letterPublicService.lookup(new LetterPublicLookupRequest(ids)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	private PlayHistoryEntity buildHistory(
			String id,
			String letterId,
			String sessionId,
			String stationId,
			SegmentType segmentType,
			PlayHistoryResultStatus resultStatus,
			String playedAt) {
		PlayHistoryEntity entity = new TestPlayHistoryEntity();
		entity.setId(id);
		entity.setLetterId(letterId);
		entity.setSessionId(sessionId);
		entity.setStationId(stationId);
		entity.setQueueItemId("queue-" + id);
		entity.setSegmentType(segmentType);
		entity.setTitle("レター");
		entity.setPlaybackMode(com.seedshiftradio.domain.PlaybackMode.SERVER_AUDIO);
		entity.setResultStatus(resultStatus);
		entity.setCorrelationId("corr-" + id);
		entity.setPlayedAt(Instant.parse(playedAt));
		return entity;
	}

	private static final class TestPlayHistoryEntity extends PlayHistoryEntity {
	}
}
