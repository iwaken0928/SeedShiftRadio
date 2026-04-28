package com.seedshiftradio.programming;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.common.api.ApiException;

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
		if (!matchesTime(rule.getStartTime(), rule.getEndTime(), at.toLocalTime())) {
			return false;
		}
		return requiredStatesSatisfied(rule.getRequiredProviderStates(), providerStates);
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
		return matchesTime(startTime, endTime, LocalTime.parse(currentTime));
	}

	static boolean matchesTime(String startTime, String endTime, LocalTime currentTime) {
		LocalTime start = LocalTime.parse(startTime);
		LocalTime end = LocalTime.parse(endTime);
		if (start.equals(end) || end.isAfter(start)) {
			return !currentTime.isBefore(start) && currentTime.isBefore(end);
		}
		return !currentTime.isBefore(start) || currentTime.isBefore(end);
	}

	static Map<String, String> normalizeProviderStates(Map<String, String> providerStates) {
		Map<String, String> normalized = new LinkedHashMap<>();
		String musicGen = normalizeProviderStatus(providerStates.get("musicGen"));
		String tts = normalizeProviderStatus(providerStates.get("tts"));
		String llm = normalizeProviderStatus(providerStates.get("llm"));
		normalized.put("musicGen", musicGen);
		normalized.put("tts", tts);
		normalized.put("llm", llm);
		normalized.put("MUSICGEN_" + musicGen, musicGen);
		normalized.put("TTS_" + tts, tts);
		normalized.put("LLM_" + llm, llm);
		for (Map.Entry<String, String> entry : providerStates.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			normalized.putIfAbsent(key, entry.getValue());
			normalized.putIfAbsent(key.toUpperCase(Locale.ROOT), entry.getValue());
		}
		return normalized;
	}

	static boolean isSegmentTypeAvailable(SegmentType type, Map<String, String> providerStates, int pendingLetterCount) {
		return switch (type) {
			case LETTER -> pendingLetterCount > 0;
			case MUSIC_AI -> "UP".equalsIgnoreCase(providerStates.getOrDefault("musicGen", "UNKNOWN"));
			case TALK -> "UP".equalsIgnoreCase(providerStates.getOrDefault("tts", "UP"))
					|| "UP".equalsIgnoreCase(providerStates.getOrDefault("llm", "UP"));
			default -> true;
		};
	}

	private static String normalizeProviderStatus(String status) {
		return status == null || status.isBlank() ? "UNKNOWN" : status.toUpperCase(Locale.ROOT);
	}

	private static boolean requiredStatesSatisfied(List<String> requiredStates, Map<String, String> providerStates) {
		for (String requiredState : requiredStates) {
			String normalized = requiredState.toUpperCase(Locale.ROOT);
			switch (normalized) {
				case "MUSICGEN_UP" -> {
					if (!"UP".equalsIgnoreCase(providerStates.getOrDefault("musicGen", "UNKNOWN"))) {
						return false;
					}
				}
				case "TTS_UP" -> {
					if (!"UP".equalsIgnoreCase(providerStates.getOrDefault("tts", "UNKNOWN"))) {
						return false;
					}
				}
				case "LLM_UP" -> {
					if (!"UP".equalsIgnoreCase(providerStates.getOrDefault("llm", "UNKNOWN"))) {
						return false;
					}
				}
				default -> {
					if (!providerStates.containsKey(requiredState) && !providerStates.containsKey(normalized)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	static List<ProgrammingDtos.PreviewSlot> buildLegacyFallbackSlots() {
		List<ProgrammingDtos.PreviewSlot> slots = new ArrayList<>();
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-talk", com.seedshiftradio.domain.SlotRole.TOPIC, com.seedshiftradio.domain.ConstraintMode.SOFT, 120000));
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-music", com.seedshiftradio.domain.SlotRole.MUSIC_BREAK, com.seedshiftradio.domain.ConstraintMode.SOFT, 72000));
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-letter", com.seedshiftradio.domain.SlotRole.LETTER, com.seedshiftradio.domain.ConstraintMode.SOFT, 36000));
		slots.add(new ProgrammingDtos.PreviewSlot("legacy-jingle", com.seedshiftradio.domain.SlotRole.OPENING, com.seedshiftradio.domain.ConstraintMode.HARD, 12000));
		return slots;
	}

	static void validateSegmentTypes(List<String> segmentTypes, String fieldName, String slotId) {
		if (segmentTypes == null) {
			return;
		}
		for (String segmentType : segmentTypes) {
			parseSegmentTypeOrThrow(segmentType, fieldName, slotId, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
		}
	}

	static SegmentType parseSegmentTypeOrThrow(
			String segmentType,
			String fieldName,
			String slotId,
			HttpStatus status,
			String code) {
		try {
			return SegmentType.valueOf(segmentType);
		} catch (IllegalArgumentException exception) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("field", fieldName);
			details.put("segmentType", segmentType);
			if (slotId != null) {
				details.put("slotId", slotId);
			}
			throw new ApiException(
					status,
					code,
					"segmentType に未対応の値が含まれています。",
					details);
		}
	}
}
