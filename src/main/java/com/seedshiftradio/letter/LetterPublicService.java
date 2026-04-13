package com.seedshiftradio.letter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicLookupRequest;
import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicLookupResponse;
import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicPlayHistorySummary;
import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicSummary;
import com.seedshiftradio.radio.PlayHistoryEntity;
import com.seedshiftradio.radio.PlayHistoryRepository;

@Service
public class LetterPublicService {

	private static final int MAX_LOOKUP_IDS = 50;

	private final LetterRepository letterRepository;
	private final PlayHistoryRepository playHistoryRepository;

	public LetterPublicService(LetterRepository letterRepository, PlayHistoryRepository playHistoryRepository) {
		this.letterRepository = letterRepository;
		this.playHistoryRepository = playHistoryRepository;
	}

	@Transactional(readOnly = true)
	public LetterPublicLookupResponse lookup(LetterPublicLookupRequest request) {
		List<String> requestedIds = normalizeIds(request == null ? null : request.letterIds());
		if (requestedIds.size() > MAX_LOOKUP_IDS) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"letterIds は 50 件以下で指定してください。",
					Map.of("letterIds", requestedIds.size()));
		}
		if (requestedIds.isEmpty()) {
			return new LetterPublicLookupResponse(List.of());
		}

		Map<String, LetterEntity> lettersById = new LinkedHashMap<>();
		for (LetterEntity letter : letterRepository.findAllById(requestedIds)) {
			lettersById.put(letter.getId(), letter);
		}
		if (lettersById.isEmpty()) {
			return new LetterPublicLookupResponse(List.of());
		}

		Map<String, List<LetterPublicPlayHistorySummary>> historiesByLetterId = loadPlayHistorySummaries(requestedIds);
		List<LetterPublicSummary> summaries = new ArrayList<>();
		for (String letterId : requestedIds) {
			LetterEntity letter = lettersById.get(letterId);
			if (letter == null) {
				continue;
			}
			summaries.add(new LetterPublicSummary(
					letter.getId(),
					letter.getStationId(),
					letter.getSubject(),
					letter.getStatus(),
					letter.getAdoptedInSessionId(),
					letter.getCreatedAt(),
					historiesByLetterId.getOrDefault(letter.getId(), List.of())));
		}
		return new LetterPublicLookupResponse(List.copyOf(summaries));
	}

	private Map<String, List<LetterPublicPlayHistorySummary>> loadPlayHistorySummaries(Collection<String> letterIds) {
		List<PlayHistoryEntity> histories = playHistoryRepository.findByLetterIdInOrderByPlayedAtDesc(letterIds);
		Map<String, List<LetterPublicPlayHistorySummary>> historiesByLetterId = new LinkedHashMap<>();
		for (PlayHistoryEntity history : histories) {
			historiesByLetterId
					.computeIfAbsent(history.getLetterId(), ignored -> new ArrayList<>())
					.add(new LetterPublicPlayHistorySummary(
							history.getId(),
							history.getSessionId(),
							history.getStationId(),
							history.getSegmentType(),
							history.getTitle(),
							history.getResultStatus(),
							history.getPlayedAt()));
		}
		return historiesByLetterId;
	}

	private List<String> normalizeIds(List<String> letterIds) {
		if (letterIds == null || letterIds.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String letterId : letterIds) {
			if (letterId == null) {
				continue;
			}
			String trimmed = letterId.trim();
			if (!trimmed.isEmpty()) {
				normalized.add(trimmed);
			}
		}
		return List.copyOf(normalized);
	}
}
