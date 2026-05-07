package com.seedshiftradio.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.domain.QueueItemStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.letter.LetterEntity;
import com.seedshiftradio.letter.LetterRepository;

@ExtendWith(MockitoExtension.class)
class LetterSegmentBinderTests {

	@Mock
	LetterRepository letterRepository;

	@Mock
	ProgramBlockRepository programBlockRepository;

	@Mock
	ProgramBlockSlotRepository programBlockSlotRepository;

	@Mock
	QueueItemRepository queueItemRepository;

	LetterSegmentBinder binder;

	@BeforeEach
	void setUp() {
		binder = new LetterSegmentBinder(letterRepository, programBlockRepository, programBlockSlotRepository, queueItemRepository);
	}

	@Test
	void bindAssignsFirstUnreservedAdoptedLetterToLetterSegment() {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("playout-001");

		ProgramBlockEntity existingBlock = new ProgramBlockEntity();
		existingBlock.setId("block-existing");
		when(programBlockRepository.findBySessionIdOrderByStartedAtAsc("playout-001")).thenReturn(List.of(existingBlock));

		ProgramBlockSlotEntity reservedSlot = new ProgramBlockSlotEntity();
		reservedSlot.setId("slot-existing");
		reservedSlot.setSlotContext(new LinkedHashMap<>(java.util.Map.of(
				"letterId", "letter-001",
				"letterSubject", "最初のレター")));
		when(programBlockSlotRepository.findByProgramBlockIdIn(List.of("block-existing"))).thenReturn(List.of(reservedSlot));

		LetterEntity first = new LetterEntity("letter-001", "station-night", "夜更かしペンギン", "最初のレター", "本文", LetterStatus.ADOPTED, null);
		first.setAdoptedInSessionId("playout-001");
		first.setCreatedAt(Instant.parse("2026-03-29T10:00:00Z"));
		LetterEntity second = new LetterEntity("letter-002", "station-night", "真夜中ラビット", "次のレター", "本文", LetterStatus.ADOPTED, null);
		second.setAdoptedInSessionId("playout-001");
		second.setCreatedAt(Instant.parse("2026-03-29T10:10:00Z"));
		when(letterRepository.findByAdoptedInSessionIdAndStatusOrderByCreatedAtAsc("playout-001", LetterStatus.ADOPTED)).thenReturn(List.of(first, second));

		ProgramBlockSlotEntity targetSlot = new ProgramBlockSlotEntity();
		targetSlot.setId("slot-letter");
		targetSlot.setSlotContext(new LinkedHashMap<>());

		QueueItemEntity item = new QueueItemEntity();
		item.setSegmentType(SegmentType.LETTER);
		item.setStatus(QueueItemStatus.READY);
		item.setSlotRole(SlotRole.LETTER);
		item.setPlaybackMode(PlaybackMode.SERVER_AUDIO);

		binder.bind(session, targetSlot, item);

		assertEquals("letter-002", targetSlot.getSlotContext().get("letterId"));
		assertEquals("letter-002", item.getLetterId());
		assertEquals("レター: 次のレター", item.getTitle());
	}

	@Test
	void bindLeavesNonLetterSegmentUntouched() {
		PlayoutSessionEntity session = new PlayoutSessionEntity();
		session.setId("playout-001");

		ProgramBlockSlotEntity targetSlot = new ProgramBlockSlotEntity();
		targetSlot.setSlotContext(new LinkedHashMap<>());

		QueueItemEntity item = new QueueItemEntity();
		item.setSegmentType(SegmentType.TALK);
		item.setTitle("オープニング");

		binder.bind(session, targetSlot, item);

		assertTrue(targetSlot.getSlotContext().isEmpty());
		assertEquals("オープニング", item.getTitle());
	}
}
