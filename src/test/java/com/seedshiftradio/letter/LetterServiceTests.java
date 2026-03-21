package com.seedshiftradio.letter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.letter.LetterDtos.LetterCreateRequest;
import com.seedshiftradio.letter.LetterDtos.LetterStatusUpdateRequest;
import com.seedshiftradio.station.StationRepository;

@ExtendWith(MockitoExtension.class)
class LetterServiceTests {

	@Mock
	LetterRepository letterRepository;

	@Mock
	LetterReplyRepository letterReplyRepository;

	@Mock
	StationRepository stationRepository;

	@Mock
	ApplicationEventPublisher applicationEventPublisher;

	LetterService letterService;

	@BeforeEach
	void setUp() {
		letterService = new LetterService(letterRepository, letterReplyRepository, stationRepository, applicationEventPublisher);
	}

	@Test
	void createReturnsExistingLetterWhenIdempotencyKeyMatches() {
		LetterEntity existing = new LetterEntity(
				"letter-existing",
				null,
				"夜更かしペンギン",
				"最近の作業BGM",
				"深夜作業でおすすめの音を教えてください。",
				LetterStatus.UNREAD,
				"idem-1");
		existing.setCreatedAt(Instant.parse("2026-03-20T09:00:00Z"));
		when(letterRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

		var response = letterService.create(
				new LetterCreateRequest(null, "夜更かしペンギン", "最近の作業BGM", "深夜作業でおすすめの音を教えてください。"),
				"idem-1");

		assertEquals("letter-existing", response.id());
		verify(letterRepository, never()).save(any());
	}

	@Test
	void updateStatusRejectsInvalidTransition() {
		LetterEntity existing = new LetterEntity(
				"letter-existing",
				null,
				"夜更かしペンギン",
				"最近の作業BGM",
				"深夜作業でおすすめの音を教えてください。",
				LetterStatus.UNREAD,
				null);
		when(letterRepository.findById("letter-existing")).thenReturn(Optional.of(existing));

		ApiException exception = assertThrows(
				ApiException.class,
				() -> letterService.updateStatus("letter-existing", new LetterStatusUpdateRequest(LetterStatus.ADOPTED)));

		assertEquals("CONFLICT", exception.getCode());
	}
}
