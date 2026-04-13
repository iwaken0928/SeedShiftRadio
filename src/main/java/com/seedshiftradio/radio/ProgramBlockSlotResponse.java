package com.seedshiftradio.radio;

import java.util.Map;

import com.seedshiftradio.domain.ConstraintMode;
import com.seedshiftradio.domain.SlotRole;

public record ProgramBlockSlotResponse(
		String id,
		String slotId,
		SlotRole role,
		ConstraintMode constraintMode,
		String resolvedSegmentType,
		Integer targetDurationMs,
		String status,
		Map<String, Object> slotContext,
		String title) {
}
