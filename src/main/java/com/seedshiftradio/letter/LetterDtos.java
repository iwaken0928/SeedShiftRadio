package com.seedshiftradio.letter;

import java.time.Instant;
import java.util.List;

import com.seedshiftradio.domain.LetterStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class LetterDtos {

	private LetterDtos() {
	}

	public record LetterCreateRequest(
			String stationId,
			@NotBlank @Size(max = 255) String radioName,
			@NotBlank @Size(max = 255) String subject,
			@NotBlank @Size(max = 5000) String body) {
	}

	public record LetterCreateResponse(String id, LetterStatus status, Instant createdAt) {
	}

	public record LetterStatusUpdateRequest(
			LetterStatus status,
			@Size(max = 100) String sessionId) {
	}

	public record LetterReplyRequest(@NotBlank @Size(max = 5000) String replyText) {
	}

	public record LetterReplyResponse(String id, Instant createdAt) {
	}

	public record LetterSummaryResponse(
			String id,
			String stationId,
			String radioName,
			String subject,
			LetterStatus status,
			String adoptedInSessionId,
			Instant createdAt,
			List<LetterReplySummary> replies) {
	}

	public record LetterReplySummary(String id, String replyText, Instant createdAt) {
	}
}
