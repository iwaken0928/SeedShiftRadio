package com.seedshiftradio.letter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
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
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.station.StationRepository;

@ExtendWith(MockitoExtension.class)
class LetterServiceTests {

	@Mock
	LetterRepository letterRepository;

	@Mock
	LetterReplyRepository letterReplyRepository;

	@Mock
	PlayoutSessionRepository playoutSessionRepository;

	@Mock
	StationRepository stationRepository;

	@Mock
	ApplicationEventPublisher applicationEventPublisher;

	LetterService letterService;

	@BeforeEach
	void setUp() {
		letterService = new LetterService(letterRepository, letterReplyRepository, playoutSessionRepository, stationRepository, applicationEventPublisher);
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
				() -> letterService.updateStatus("letter-existing", new LetterStatusUpdateRequest(LetterStatus.ADOPTED, null)));

		assertEquals("CONFLICT", exception.getCode());
	}

	@Test
	void updateStatusRequiresSessionIdWhenAdopting() {
		LetterEntity existing = new LetterEntity(
				"letter-existing",
				null,
				"夜更かしペンギン",
				"最近の作業BGM",
				"深夜作業でおすすめの音を教えてください。",
				LetterStatus.PENDING,
				null);
		when(letterRepository.findById("letter-existing")).thenReturn(Optional.of(existing));

		ApiException exception = assertThrows(
				ApiException.class,
				() -> letterService.updateStatus("letter-existing", new LetterStatusUpdateRequest(LetterStatus.ADOPTED, null)));

		assertEquals("VALIDATION_ERROR", exception.getCode());
	}

	@Test
	void updateStatusStoresAdoptedSessionId() {
		LetterEntity existing = new LetterEntity(
				"letter-existing",
				null,
				"夜更かしペンギン",
				"最近の作業BGM",
				"深夜作業でおすすめの音を教えてください。",
				LetterStatus.PENDING,
				null);
		when(letterRepository.findById("letter-existing")).thenReturn(Optional.of(existing));
		when(playoutSessionRepository.existsById("playout-001")).thenReturn(true);
		when(letterRepository.save(any(LetterEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(letterReplyRepository.findByLetterIdOrderByCreatedAtAsc("letter-existing")).thenReturn(List.of());

		var response = letterService.updateStatus("letter-existing", new LetterStatusUpdateRequest(LetterStatus.ADOPTED, "playout-001"));

		assertEquals(LetterStatus.ADOPTED, response.status());
		assertEquals("playout-001", response.adoptedInSessionId());
	}
}
