package com.seedshiftradio.programming;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ProgrammingSupport {

	private ProgrammingSupport() {
	}

	static List<String> normalizeDays(List<String> days) {
		return days.stream().map(value -> value.trim().toUpperCase(Locale.ROOT)).toList();
	}

	static boolean matchesRule(ProgramRuleEntity rule, OffsetDateTime at, int pendingLetterCount, Map<String, String> providerStates) {
		if (pendingLetterCount < rule.getMinimumPendingLetters()) {
			return false;
		}
		if (!matchesDay(rule.getDaysOfWeek(), at.getDayOfWeek())) {
			return false;
		}
		if (!matchesTime(rule.getStartTime(), rule.getEndTime(), at.toLocalTime().toString().substring(0, 5))) {
			return false;
		}
		for (String requiredState : rule.getRequiredProviderStates()) {
			if (!providerStates.containsKey(requiredState)) {
				return false;
			}
		}
		return true;
	}

	static boolean matchesDay(String csv, DayOfWeek dayOfWeek) {
		String target = dayOfWeek.name();
		String shortTarget = target.substring(0, 3);
		for (String token : csv.split(",")) {
			String normalized = token.trim().toUpperCase(Locale.ROOT);
			if (normalized.equals(target) || normalized.equals(shortTarget)) {
				return true;
			}
		}
		return false;
	}

	static boolean matchesTime(String startTime, String endTime, String currentTime) {
		if (startTime.compareTo(endTime) <= 0) {
			return currentTime.compareTo(startTime) >= 0 && currentTime.compareTo(endTime) <= 0;
		}
		return currentTime.compareTo(startTime) >= 0 || currentTime.compareTo(endTime) <= 0;
	}

	static List<ProgrammingDtos.PreviewSlot> buildLegacyFallbackSlots() {
		List<ProgrammingDtos.PreviewSlot> slots = new ArrayList<>();
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-talk", com.seedshiftradio.domain.SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 120000));
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-music", com.seedshiftradio.domain.SlotRole.MUSIC_BREAK, com.seedshiftradio.domain.ConstraintMode.SOFT, 72000));
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-letter", com.seedshiftradio.domain.SlotRole.LETTER, com.seedshiftradio.domain.ConstraintMode.SOFT, 36000));
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-jingle", com.seedshiftradio.domain.SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 12000));
		return slots;
	}
}
