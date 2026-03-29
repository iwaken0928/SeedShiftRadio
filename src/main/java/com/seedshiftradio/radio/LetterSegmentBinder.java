package com.seedshiftradio.radio;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.letter.LetterEntity;
import com.seedshiftradio.letter.LetterRepository;

@Service
public class LetterSegmentBinder {

	private final LetterRepository letterRepository;
	private final ProgramBlockRepository programBlockRepository;
	private final ProgramBlockSlotRepository programBlockSlotRepository;
	private final QueueItemRepository queueItemRepository;

	public LetterSegmentBinder(
			LetterRepository letterRepository,
			ProgramBlockRepository programBlockRepository,
			ProgramBlockSlotRepository programBlockSlotRepository,
			QueueItemRepository queueItemRepository) {
		this.letterRepository = letterRepository;
		this.programBlockRepository = programBlockRepository;
		this.programBlockSlotRepository = programBlockSlotRepository;
		this.queueItemRepository = queueItemRepository;
	}

	public void bind(PlayoutSessionEntity session, ProgramBlockSlotEntity blockSlot, QueueItemEntity item) {
		if (item.getSegmentType() != SegmentType.LETTER) {
			return;
		}

		Map<String, Object> slotContext = blockSlot.getSlotContext() == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(blockSlot.getSlotContext());
		String existingLetterId = stringValue(slotContext.get("letterId"));
		if (existingLetterId != null) {
			item.setLetterId(existingLetterId);
			item.setTitle(buildLetterTitle(stringValue(slotContext.get("letterSubject"))));
			blockSlot.setSlotContext(slotContext);
			return;
		}

		selectNextLetter(session.getId(), reservedLetterIds(session.getId())).ifPresent(selection -> applySelection(blockSlot, item, slotContext, selection));
	}

	public void bindPendingSegments(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return;
		}
		List<QueueItemEntity> queueItems = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(sessionId);
		if (queueItems.isEmpty()) {
			return;
		}
		Map<String, ProgramBlockSlotEntity> slotsById = loadSlotsBySessionId(sessionId);
		Set<String> reservedLetterIds = new HashSet<>(reservedLetterIds(slotsById.values()));
		Iterator<LetterSelection> availableLetters = selectAvailableLetters(sessionId, reservedLetterIds).iterator();
		List<QueueItemEntity> changedItems = new ArrayList<>();
		List<ProgramBlockSlotEntity> changedSlots = new ArrayList<>();
		for (QueueItemEntity queueItem : queueItems) {
			if (!needsBinding(queueItem)) {
				continue;
			}
			ProgramBlockSlotEntity slot = slotsById.get(queueItem.getProgramSlotId());
			if (slot == null) {
				continue;
			}
			Map<String, Object> slotContext = slot.getSlotContext() == null
					? new LinkedHashMap<>()
					: new LinkedHashMap<>(slot.getSlotContext());
			String existingLetterId = stringValue(slotContext.get("letterId"));
			if (existingLetterId != null) {
				queueItem.setLetterId(existingLetterId);
				queueItem.setTitle(buildLetterTitle(stringValue(slotContext.get("letterSubject"))));
				changedItems.add(queueItem);
				reservedLetterIds.add(existingLetterId);
				continue;
			}
			if (!availableLetters.hasNext()) {
				continue;
			}
			LetterSelection selection = availableLetters.next();
			applySelection(slot, queueItem, slotContext, selection);
			changedItems.add(queueItem);
			changedSlots.add(slot);
			reservedLetterIds.add(selection.id());
		}
		if (!changedSlots.isEmpty()) {
			programBlockSlotRepository.saveAll(changedSlots);
		}
		if (!changedItems.isEmpty()) {
			queueItemRepository.saveAll(changedItems);
		}
	}

	private Optional<LetterSelection> selectNextLetter(String sessionId, Set<String> reservedLetterIds) {
		return selectAvailableLetters(sessionId, reservedLetterIds).stream()
				.findFirst();
	}

	private List<LetterSelection> selectAvailableLetters(String sessionId, Set<String> reservedLetterIds) {
		return letterRepository.findByAdoptedInSessionIdAndStatusOrderByCreatedAtAsc(sessionId, LetterStatus.ADOPTED).stream()
				.filter(letter -> !reservedLetterIds.contains(letter.getId()))
				.map(letter -> new LetterSelection(letter.getId(), letter.getRadioName(), letter.getSubject()))
				.toList();
	}

	private Set<String> reservedLetterIds(String sessionId) {
		return reservedLetterIds(loadSlotsBySessionId(sessionId).values());
	}

	private Set<String> reservedLetterIds(Collection<ProgramBlockSlotEntity> slots) {
		return slots.stream()
				.map(ProgramBlockSlotEntity::getSlotContext)
				.filter(context -> context != null)
				.map(context -> stringValue(context.get("letterId")))
				.filter(letterId -> letterId != null && !letterId.isBlank())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private Map<String, ProgramBlockSlotEntity> loadSlotsBySessionId(String sessionId) {
		List<String> blockIds = programBlockRepository.findBySessionIdOrderByStartedAtAsc(sessionId).stream()
				.map(ProgramBlockEntity::getId)
				.toList();
		if (blockIds.isEmpty()) {
			return Map.of();
		}
		Map<String, ProgramBlockSlotEntity> slotsById = new LinkedHashMap<>();
		for (ProgramBlockSlotEntity slot : programBlockSlotRepository.findByProgramBlockIdIn(blockIds)) {
			slotsById.put(slot.getId(), slot);
		}
		return Map.copyOf(slotsById);
	}

	private boolean needsBinding(QueueItemEntity queueItem) {
		if (queueItem.getSegmentType() != SegmentType.LETTER) {
			return false;
		}
		if (queueItem.getLetterId() != null && !queueItem.getLetterId().isBlank()) {
			return false;
		}
		return switch (queueItem.getStatus()) {
			case DONE, FAILED, SKIPPED -> false;
			default -> true;
		};
	}

	private void applySelection(
			ProgramBlockSlotEntity blockSlot,
			QueueItemEntity item,
			Map<String, Object> slotContext,
			LetterSelection selection) {
		slotContext.put("letterId", selection.id());
		slotContext.put("letterRadioName", selection.radioName());
		slotContext.put("letterSubject", selection.subject());
		blockSlot.setSlotContext(slotContext);
		item.setLetterId(selection.id());
		item.setTitle(buildLetterTitle(selection.subject()));
	}

	private String buildLetterTitle(String subject) {
		if (subject == null || subject.isBlank()) {
			return "レター";
		}
		return "レター: " + subject;
	}

	private String stringValue(Object value) {
		return value instanceof String string && !string.isBlank() ? string : null;
	}

	record LetterSelection(String id, String radioName, String subject) {
	}
}
