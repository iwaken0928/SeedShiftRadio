package com.seedshiftradio.radio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.letter.LetterEntity;
import com.seedshiftradio.letter.LetterRepository;
import com.seedshiftradio.radio.PlayHistoryDtos.PlayHistoryLetterResponse;
import com.seedshiftradio.radio.PlayHistoryDtos.PlayHistoryResponse;

import jakarta.persistence.criteria.Predicate;

@Service
public class PlayHistoryQueryService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;

	private final PlayHistoryRepository playHistoryRepository;
	private final ProgramBlockSlotRepository programBlockSlotRepository;
	private final LetterRepository letterRepository;

	public PlayHistoryQueryService(
			PlayHistoryRepository playHistoryRepository,
			ProgramBlockSlotRepository programBlockSlotRepository,
			LetterRepository letterRepository) {
		this.playHistoryRepository = playHistoryRepository;
		this.programBlockSlotRepository = programBlockSlotRepository;
		this.letterRepository = letterRepository;
	}

	@Transactional(readOnly = true)
	public List<PlayHistoryResponse> list(
			String sessionId,
			String stationId,
			String letterId,
			PlayHistoryResultStatus resultStatus,
			Integer limit) {
		List<PlayHistoryResponse> responses = mapResponses(playHistoryRepository.findAll(
				specification(sessionId, stationId, letterId, resultStatus),
				Sort.by(Sort.Direction.DESC, "playedAt")));
		return responses.stream().limit(normalizeLimit(limit)).toList();
	}

	@Transactional(readOnly = true)
	public PlayHistoryResponse get(String playHistoryId) {
		PlayHistoryEntity entity = playHistoryRepository.findById(playHistoryId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された play history が見つかりません。", Map.of("playHistoryId", playHistoryId)));
		return mapResponses(List.of(entity)).getFirst();
	}

	private List<PlayHistoryResponse> mapResponses(List<PlayHistoryEntity> entities) {
		Map<String, ProgramBlockSlotEntity> slotsById = loadSlots(entities);
		Map<String, LetterEntity> lettersById = loadLetters(entities, slotsById.values());
		return entities.stream()
				.map(entity -> toResponse(entity, slotsById.get(entity.getProgramSlotId()), lettersById))
				.toList();
	}

	private Map<String, ProgramBlockSlotEntity> loadSlots(List<PlayHistoryEntity> entities) {
		List<String> slotIds = entities.stream()
				.map(PlayHistoryEntity::getProgramSlotId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		if (slotIds.isEmpty()) {
			return Map.of();
		}
		Map<String, ProgramBlockSlotEntity> slotsById = new LinkedHashMap<>();
		for (ProgramBlockSlotEntity slot : programBlockSlotRepository.findAllById(slotIds)) {
			slotsById.put(slot.getId(), slot);
		}
		return Map.copyOf(slotsById);
	}

	private Map<String, LetterEntity> loadLetters(
			List<PlayHistoryEntity> entities,
			Iterable<ProgramBlockSlotEntity> slots) {
		List<String> letterIds = java.util.stream.Stream.concat(
				entities.stream().map(PlayHistoryEntity::getLetterId),
				java.util.stream.StreamSupport.stream(slots.spliterator(), false)
				.map(this::extractLetterId)
		)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		if (letterIds.isEmpty()) {
			return Map.of();
		}
		Map<String, LetterEntity> lettersById = new LinkedHashMap<>();
		for (LetterEntity letter : letterRepository.findAllById(letterIds)) {
			lettersById.put(letter.getId(), letter);
		}
		return Map.copyOf(lettersById);
	}

	private PlayHistoryResponse toResponse(
			PlayHistoryEntity entity,
			ProgramBlockSlotEntity slot,
			Map<String, LetterEntity> lettersById) {
		PlayHistoryLetterResponse letter = toLetterResponse(entity, slot, lettersById);
		return new PlayHistoryResponse(
				entity.getId(),
				entity.getSessionId(),
				entity.getStationId(),
				entity.getQueueItemId(),
				entity.getProgramBlockId(),
				entity.getProgramSlotId(),
				entity.getSegmentType(),
				entity.getTitle(),
				entity.getPlaybackMode(),
				entity.getResultStatus(),
				entity.getCorrelationId(),
				entity.getContentOrigin(),
				entity.getReplayOfPlayHistoryId(),
				entity.getPlayedAt(),
				letter);
	}

	private PlayHistoryLetterResponse toLetterResponse(
			PlayHistoryEntity entity,
			ProgramBlockSlotEntity slot,
			Map<String, LetterEntity> lettersById) {
		String letterId = extractLetterId(entity, slot);
		if (letterId == null) {
			return null;
		}
		LetterEntity letter = lettersById.get(letterId);
		String radioName = slot == null || slot.getSlotContext() == null ? null : stringValue(slot.getSlotContext().get("letterRadioName"));
		String subject = slot == null || slot.getSlotContext() == null ? null : stringValue(slot.getSlotContext().get("letterSubject"));
		if (letter == null) {
			return new PlayHistoryLetterResponse(letterId, radioName, subject, null);
		}
		return new PlayHistoryLetterResponse(
				letter.getId(),
				letter.getRadioName(),
				letter.getSubject(),
				letter.getAdoptedInSessionId());
	}

	private Specification<PlayHistoryEntity> specification(
			String sessionId,
			String stationId,
			String letterId,
			PlayHistoryResultStatus resultStatus) {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new java.util.ArrayList<>();
			if (sessionId != null && !sessionId.isBlank()) {
				predicates.add(criteriaBuilder.equal(root.get("sessionId"), sessionId));
			}
			if (stationId != null && !stationId.isBlank()) {
				predicates.add(criteriaBuilder.equal(root.get("stationId"), stationId));
			}
			if (letterId != null && !letterId.isBlank()) {
				predicates.add(criteriaBuilder.equal(root.get("letterId"), letterId));
			}
			if (resultStatus != null) {
				predicates.add(criteriaBuilder.equal(root.get("resultStatus"), resultStatus));
			}
			return predicates.isEmpty()
					? criteriaBuilder.conjunction()
					: criteriaBuilder.and(predicates.toArray(Predicate[]::new));
		};
	}

	private int normalizeLimit(Integer limit) {
		if (limit == null) {
			return DEFAULT_LIMIT;
		}
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"limit は 1 以上 200 以下で指定してください。",
					Map.of("limit", limit));
		}
		return limit;
	}

	private String extractLetterId(ProgramBlockSlotEntity slot) {
		if (slot == null || slot.getSlotContext() == null) {
			return null;
		}
		return stringValue(slot.getSlotContext().get("letterId"));
	}

	private String extractLetterId(PlayHistoryEntity entity, ProgramBlockSlotEntity slot) {
		if (entity.getLetterId() != null && !entity.getLetterId().isBlank()) {
			return entity.getLetterId();
		}
		return extractLetterId(slot);
	}

	private String stringValue(Object value) {
		return value instanceof String string && !string.isBlank() ? string : null;
	}
}
