package com.seedshiftradio.radio;

import java.time.Instant;
import java.util.List;

import com.seedshiftradio.domain.ProgramBlockStatus;

public record ProgramBlockResponse(
		String id,
		String stationId,
		String templateId,
		Integer templateVersion,
		String title,
		ProgramBlockStatus status,
		Integer plannedDurationMs,
		Integer remainingSlotCount,
		Instant startedAt,
		List<ProgramBlockSlotResponse> slots,
		String correlationId) {
}
