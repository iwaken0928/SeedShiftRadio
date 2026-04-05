package com.seedshiftradio.letter;

import java.time.Instant;
import java.util.List;

import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.SegmentType;

public final class LetterPublicDtos {

	private LetterPublicDtos() {
	}

	public record LetterPublicLookupRequest(List<String> letterIds) {
	}

	public record LetterPublicLookupResponse(List<LetterPublicSummary> letters) {
	}

	public record LetterPublicSummary(
			String id,
			String stationId,
			String radioName,
			String subject,
			LetterStatus status,
			String adoptedInSessionId,
			Instant createdAt,
			List<LetterPublicPlayHistorySummary> playHistory) {
	}

	public record LetterPublicPlayHistorySummary(
			String id,
			String sessionId,
			String stationId,
			SegmentType segmentType,
			String title,
			PlayHistoryResultStatus resultStatus,
			Instant playedAt) {
	}
}
