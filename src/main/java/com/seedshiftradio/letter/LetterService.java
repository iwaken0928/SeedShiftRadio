package com.seedshiftradio.letter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.letter.LetterDtos.LetterCreateRequest;
import com.seedshiftradio.letter.LetterDtos.LetterCreateResponse;
import com.seedshiftradio.letter.LetterDtos.LetterReplyRequest;
import com.seedshiftradio.letter.LetterDtos.LetterReplyResponse;
import com.seedshiftradio.letter.LetterDtos.LetterReplySummary;
import com.seedshiftradio.letter.LetterDtos.LetterStatusUpdateRequest;
import com.seedshiftradio.letter.LetterDtos.LetterSummaryResponse;
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.station.StationRepository;

@Service
public class LetterService {

	private static final EnumSet<LetterStatus> PENDING_POOL = EnumSet.of(LetterStatus.UNREAD, LetterStatus.PENDING, LetterStatus.ADOPTED);

	private final LetterRepository letterRepository;
	private final LetterReplyRepository letterReplyRepository;
	private final PlayoutSessionRepository playoutSessionRepository;
	private final StationRepository stationRepository;
	private final ApplicationEventPublisher eventPublisher;

	public LetterService(
			LetterRepository letterRepository,
			LetterReplyRepository letterReplyRepository,
			PlayoutSessionRepository playoutSessionRepository,
			StationRepository stationRepository,
			ApplicationEventPublisher eventPublisher) {
		this.letterRepository = letterRepository;
		this.letterReplyRepository = letterReplyRepository;
		this.playoutSessionRepository = playoutSessionRepository;
		this.stationRepository = stationRepository;
		this.eventPublisher = eventPublisher;
	}

	@Transactional(readOnly = true)
	public List<LetterSummaryResponse> list(String stationId, LetterStatus status) {
		List<LetterSummaryResponse> responses = new ArrayList<>();
		List<LetterEntity> letters = stationId == null
				? letterRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
				: letterRepository.findByStationIdOrderByCreatedAtDesc(stationId);
		Map<String, List<LetterReplySummary>> repliesByLetterId = toReplyMap(letters);
		for (LetterEntity letter : letters) {
			if (stationId != null && !stationId.equals(letter.getStationId())) {
				continue;
			}
			if (status != null && status != letter.getStatus()) {
				continue;
			}
			responses.add(toSummary(letter, repliesByLetterId.getOrDefault(letter.getId(), List.of())));
		}
		return responses;
	}

	@Transactional
	public LetterCreateResponse create(LetterCreateRequest request, String idempotencyKey) {
		if (request.stationId() != null && !stationRepository.existsById(request.stationId())) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定された局が見つかりません。", Map.of("stationId", request.stationId()));
		}
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			return letterRepository.findByIdempotencyKey(idempotencyKey)
					.map(existing -> new LetterCreateResponse(existing.getId(), existing.getStatus(), existing.getCreatedAt()))
					.orElseGet(() -> saveNewLetter(request, idempotencyKey));
		}
		return saveNewLetter(request, null);
	}

	@Transactional
	public LetterSummaryResponse updateStatus(String letterId, LetterStatusUpdateRequest request) {
		LetterEntity letter = getLetter(letterId);
		validateTransition(letter.getStatus(), request.status());
		letter.setStatus(request.status());
		if (request.status() == LetterStatus.ADOPTED) {
			if (request.sessionId() == null || request.sessionId().isBlank()) {
				throw new ApiException(
						HttpStatus.BAD_REQUEST,
						"VALIDATION_ERROR",
						"ADOPTED へ更新する場合は sessionId が必要です。",
						Map.of("letterId", letterId));
			}
			if (!playoutSessionRepository.existsById(request.sessionId())) {
				throw new ApiException(
						HttpStatus.NOT_FOUND,
						"NOT_FOUND",
						"採用先の再生セッションが見つかりません。",
						Map.of("sessionId", request.sessionId()));
			}
			letter.setAdoptedInSessionId(request.sessionId());
		} else if (request.sessionId() != null && !request.sessionId().isBlank()) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"VALIDATION_ERROR",
					"sessionId は ADOPTED へ更新する場合のみ指定できます。",
					Map.of("letterId", letterId));
		}
		LetterEntity saved = letterRepository.save(letter);
		LetterSummaryResponse summary = toSummary(saved, loadReplies(saved.getId()));
		eventPublisher.publishEvent(new LetterChangedEvent(summary));
		return summary;
	}

	@Transactional
	public LetterReplyResponse addReply(String letterId, LetterReplyRequest request) {
		LetterEntity letter = getLetter(letterId);
		if (letter.getStatus() == LetterStatus.UNREAD) {
			letter.setStatus(LetterStatus.REPLIED);
			letterRepository.save(letter);
		} else if (letter.getStatus() == LetterStatus.PENDING || letter.getStatus() == LetterStatus.ADOPTED) {
			letter.setStatus(LetterStatus.REPLIED);
			letterRepository.save(letter);
		}
		LetterReplyEntity reply = letterReplyRepository.save(new LetterReplyEntity(nextId("reply"), letter.getId(), request.replyText()));
		eventPublisher.publishEvent(new LetterChangedEvent(toSummary(letter, loadReplies(letter.getId()))));
		return new LetterReplyResponse(reply.getId(), reply.getCreatedAt());
	}

	@Transactional(readOnly = true)
	public long countPendingLetters(String stationId) {
		if (stationId == null) {
			return 0;
		}
		return letterRepository.countByStationIdAndStatusIn(stationId, PENDING_POOL);
	}

	private LetterCreateResponse saveNewLetter(LetterCreateRequest request, String idempotencyKey) {
		LetterEntity entity = new LetterEntity(
				nextId("letter"),
				request.stationId(),
				request.radioName(),
				request.subject(),
				request.body(),
				LetterStatus.UNREAD,
				idempotencyKey);
		LetterEntity saved = letterRepository.save(entity);
		eventPublisher.publishEvent(new LetterChangedEvent(toSummary(saved, List.of())));
		return new LetterCreateResponse(saved.getId(), saved.getStatus(), saved.getCreatedAt());
	}

	private LetterEntity getLetter(String letterId) {
		return letterRepository.findById(letterId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "指定されたレターが見つかりません。", Map.of("letterId", letterId)));
	}

	private void validateTransition(LetterStatus current, LetterStatus next) {
		boolean valid = switch (current) {
			case UNREAD -> next == LetterStatus.PENDING;
			case PENDING -> next == LetterStatus.ADOPTED || next == LetterStatus.REPLIED;
			case ADOPTED -> next == LetterStatus.REPLIED;
			case REPLIED -> false;
		};
		if (!valid) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CONFLICT",
					"指定されたレター状態遷移は許可されていません。",
					Map.of("from", current.name(), "to", next.name()));
		}
	}

	private Map<String, List<LetterReplySummary>> toReplyMap(List<LetterEntity> letters) {
		if (letters.isEmpty()) {
			return Map.of();
		}
		Map<String, List<LetterReplySummary>> repliesByLetterId = new LinkedHashMap<>();
		for (LetterReplyEntity reply : letterReplyRepository.findByLetterIdInOrderByCreatedAtAsc(letters.stream().map(LetterEntity::getId).toList())) {
			repliesByLetterId.computeIfAbsent(reply.getLetterId(), ignored -> new ArrayList<>())
					.add(new LetterReplySummary(reply.getId(), reply.getReplyText(), reply.getCreatedAt()));
		}
		return Collections.unmodifiableMap(repliesByLetterId);
	}

	private List<LetterReplySummary> loadReplies(String letterId) {
		return letterReplyRepository.findByLetterIdOrderByCreatedAtAsc(letterId).stream()
				.map(reply -> new LetterReplySummary(reply.getId(), reply.getReplyText(), reply.getCreatedAt()))
				.toList();
	}

	private LetterSummaryResponse toSummary(LetterEntity letter, List<LetterReplySummary> replies) {
		return new LetterSummaryResponse(
				letter.getId(),
				letter.getStationId(),
				letter.getRadioName(),
				letter.getSubject(),
				letter.getStatus(),
				letter.getAdoptedInSessionId(),
				letter.getCreatedAt(),
				replies);
	}

	private String nextId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
