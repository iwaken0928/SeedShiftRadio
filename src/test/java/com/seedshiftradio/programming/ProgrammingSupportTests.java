package com.seedshiftradio.programming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProgrammingSupportTests {

	@Test
	void matchesOvernightWindow() {
		assertTrue(ProgrammingSupport.matchesTime("22:00", "02:00", "23:30"));
		assertTrue(ProgrammingSupport.matchesTime("22:00", "02:00", "01:30"));
		assertFalse(ProgrammingSupport.matchesTime("22:00", "02:00", "14:00"));
	}

	@Test
	void matchesRuleRequiresPendingLettersAndProviderState() {
		ProgramRuleEntity rule = new ProgramRuleEntity();
		rule.setMinimumPendingLetters(3);
		rule.setDaysOfWeek("SAT,SUN");
		rule.setStartTime("22:00");
		rule.setEndTime("02:00");
		rule.setRequiredProviderStates(List.of("MUSICGEN_UP"));

		boolean matched = ProgrammingSupport.matchesRule(
				rule,
				OffsetDateTime.of(2026, 3, 21, 23, 30, 0, 0, ZoneOffset.ofHours(9)),
				4,
				Map.of("MUSICGEN_UP", "UP"));

		assertTrue(matched);
	}

	@Test
	void legacyFallbackSlotsHaveStableShape() {
		List<ProgrammingDtos.PreviewSlot> slots = ProgrammingSupport.buildLegacyFallbackSlots();

		assertEquals(4, slots.size());
		assertEquals("legacy-talk", slots.getFirst().slotId());
		assertTrue(slots.stream().mapToInt(ProgrammingDtos.PreviewSlot::targetDurationMs).sum() > 0);
	}
}
