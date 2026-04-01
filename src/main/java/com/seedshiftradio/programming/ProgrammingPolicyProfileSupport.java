package com.seedshiftradio.programming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.SegmentType;

public final class ProgrammingPolicyProfileSupport {

	private static final Set<String> PRE_GENERATION_MODES = Set.of("REALTIME_ONLY", "ASSISTED", "AGGRESSIVE");
	private static final Set<String> REPLAY_INTENSITIES = Set.of("OFF", "LIGHT", "MEDIUM", "HEAVY");
	private static final List<String> TARGET_SHARE_KEYS = List.of("talk", "letter", "music", "jingle");

	private ProgrammingPolicyProfileSupport() {
	}

	public static PreGenerationProfile defaultPreGenerationProfile() {
		return new PreGenerationProfile("ASSISTED", 12, 2, true);
	}

	public static ReplayProfile defaultReplayProfile() {
		return new ReplayProfile("LIGHT", List.of("MUSIC_AI", "MUSIC_LOCAL", "JINGLE"), 6, 72, 20, true);
	}

	public static CompositionProfile defaultCompositionProfile() {
		return new CompositionProfile(new LinkedHashMap<>(Map.of(
				"talk", 40,
				"letter", 20,
				"music", 35,
				"jingle", 5)), 2, 8, 4, true);
	}

	public static PreGenerationProfile materializePreGenerationProfile(PreGenerationProfile profile) {
		PreGenerationProfile defaults = defaultPreGenerationProfile();
		if (profile == null) {
			return defaults;
		}
		return new PreGenerationProfile(
				normalizeUpper(profile.mode(), defaults.mode()),
				defaultIfNull(profile.maxPreparedMinutes(), defaults.maxPreparedMinutes()),
				defaultIfNull(profile.maxPreparedBlocks(), defaults.maxPreparedBlocks()),
				defaultIfNull(profile.preferCacheReuse(), defaults.preferCacheReuse()));
	}

	public static ReplayProfile materializeReplayProfile(ReplayProfile profile) {
		ReplayProfile defaults = defaultReplayProfile();
		if (profile == null) {
			return defaults;
		}
		List<String> eligibleSegmentTypes = profile.eligibleSegmentTypes() == null || profile.eligibleSegmentTypes().isEmpty()
				? defaults.eligibleSegmentTypes()
				: profile.eligibleSegmentTypes().stream()
						.map(type -> normalizeUpper(type, null))
						.toList();
		return new ReplayProfile(
				normalizeUpper(profile.intensity(), defaults.intensity()),
				List.copyOf(eligibleSegmentTypes),
				defaultIfNull(profile.minimumAssetAgeHours(), defaults.minimumAssetAgeHours()),
				defaultIfNull(profile.cooldownHours(), defaults.cooldownHours()),
				defaultIfNull(profile.maxReplaySharePercent(), defaults.maxReplaySharePercent()),
				defaultIfNull(profile.excludeLetterSegments(), defaults.excludeLetterSegments()));
	}

	public static CompositionProfile materializeCompositionProfile(CompositionProfile profile) {
		CompositionProfile defaults = defaultCompositionProfile();
		if (profile == null) {
			return defaults;
		}

		LinkedHashMap<String, Integer> shares = new LinkedHashMap<>();
		Map<String, Integer> requested = profile.targetSegmentShares() == null ? Map.of() : profile.targetSegmentShares();
		for (Map.Entry<String, Integer> entry : requested.entrySet()) {
			shares.put(normalizeShareKey(entry.getKey()), entry.getValue());
		}
		for (String key : TARGET_SHARE_KEYS) {
			shares.putIfAbsent(key, defaults.targetSegmentShares().get(key));
		}

		return new CompositionProfile(
				Map.copyOf(shares),
				defaultIfNull(profile.maxConsecutiveTalkSegments(), defaults.maxConsecutiveTalkSegments()),
				defaultIfNull(profile.musicBreakIntervalMinutes(), defaults.musicBreakIntervalMinutes()),
				defaultIfNull(profile.letterPriorityBoostThreshold(), defaults.letterPriorityBoostThreshold()),
				defaultIfNull(profile.allowSoftFallbackRetiming(), defaults.allowSoftFallbackRetiming()));
	}

	public static PreGenerationProfile toPreGenerationProfile(Map<String, Object> storedProfile) {
		Map<String, Object> safeProfile = storedProfile == null ? Map.of() : storedProfile;
		PreGenerationProfile defaults = defaultPreGenerationProfile();
		return new PreGenerationProfile(
				normalizeUpper(safeProfile.get("mode"), defaults.mode()),
				asInteger(safeProfile.get("maxPreparedMinutes"), defaults.maxPreparedMinutes()),
				asInteger(safeProfile.get("maxPreparedBlocks"), defaults.maxPreparedBlocks()),
				asBoolean(safeProfile.get("preferCacheReuse"), defaults.preferCacheReuse()));
	}

	public static ReplayProfile toReplayProfile(Map<String, Object> storedProfile) {
		Map<String, Object> safeProfile = storedProfile == null ? Map.of() : storedProfile;
		ReplayProfile defaults = defaultReplayProfile();
		return new ReplayProfile(
				normalizeUpper(safeProfile.get("intensity"), defaults.intensity()),
				asStringList(safeProfile.get("eligibleSegmentTypes"), defaults.eligibleSegmentTypes()),
				asInteger(safeProfile.get("minimumAssetAgeHours"), defaults.minimumAssetAgeHours()),
				asInteger(safeProfile.get("cooldownHours"), defaults.cooldownHours()),
				asInteger(safeProfile.get("maxReplaySharePercent"), defaults.maxReplaySharePercent()),
				asBoolean(safeProfile.get("excludeLetterSegments"), defaults.excludeLetterSegments()));
	}

	public static CompositionProfile toCompositionProfile(Map<String, Object> storedProfile) {
		Map<String, Object> safeProfile = storedProfile == null ? Map.of() : storedProfile;
		CompositionProfile defaults = defaultCompositionProfile();
		LinkedHashMap<String, Integer> targetSegmentShares = new LinkedHashMap<>();
		Map<?, ?> rawTargetShares = safeProfile.get("targetSegmentShares") instanceof Map<?, ?> map ? map : Map.of();
		for (Map.Entry<?, ?> entry : rawTargetShares.entrySet()) {
			targetSegmentShares.put(normalizeShareKey(entry.getKey()), asInteger(entry.getValue(), null));
		}
		for (String key : TARGET_SHARE_KEYS) {
			targetSegmentShares.putIfAbsent(key, defaults.targetSegmentShares().get(key));
		}
		return new CompositionProfile(
				Map.copyOf(targetSegmentShares),
				asInteger(safeProfile.get("maxConsecutiveTalkSegments"), defaults.maxConsecutiveTalkSegments()),
				asInteger(safeProfile.get("musicBreakIntervalMinutes"), defaults.musicBreakIntervalMinutes()),
				asInteger(safeProfile.get("letterPriorityBoostThreshold"), defaults.letterPriorityBoostThreshold()),
				asBoolean(safeProfile.get("allowSoftFallbackRetiming"), defaults.allowSoftFallbackRetiming()));
	}

	public static Map<String, Object> toMap(PreGenerationProfile profile) {
		PreGenerationProfile normalized = materializePreGenerationProfile(profile);
		LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
		stored.put("mode", normalized.mode());
		stored.put("maxPreparedMinutes", normalized.maxPreparedMinutes());
		stored.put("maxPreparedBlocks", normalized.maxPreparedBlocks());
		stored.put("preferCacheReuse", normalized.preferCacheReuse());
		return stored;
	}

	public static Map<String, Object> toMap(ReplayProfile profile) {
		ReplayProfile normalized = materializeReplayProfile(profile);
		LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
		stored.put("intensity", normalized.intensity());
		stored.put("eligibleSegmentTypes", new ArrayList<>(normalized.eligibleSegmentTypes()));
		stored.put("minimumAssetAgeHours", normalized.minimumAssetAgeHours());
		stored.put("cooldownHours", normalized.cooldownHours());
		stored.put("maxReplaySharePercent", normalized.maxReplaySharePercent());
		stored.put("excludeLetterSegments", normalized.excludeLetterSegments());
		return stored;
	}

	public static Map<String, Object> toMap(CompositionProfile profile) {
		CompositionProfile normalized = materializeCompositionProfile(profile);
		LinkedHashMap<String, Object> stored = new LinkedHashMap<>();
		stored.put("targetSegmentShares", new LinkedHashMap<>(normalized.targetSegmentShares()));
		stored.put("maxConsecutiveTalkSegments", normalized.maxConsecutiveTalkSegments());
		stored.put("musicBreakIntervalMinutes", normalized.musicBreakIntervalMinutes());
		stored.put("letterPriorityBoostThreshold", normalized.letterPriorityBoostThreshold());
		stored.put("allowSoftFallbackRetiming", normalized.allowSoftFallbackRetiming());
		return stored;
	}

	public static void validateProfiles(
			PreGenerationProfile preGeneration,
			ReplayProfile replay,
			CompositionProfile composition) {
		validatePreGeneration(materializePreGenerationProfile(preGeneration));
		validateReplay(materializeReplayProfile(replay));
		validateComposition(materializeCompositionProfile(composition));
	}

	private static void validatePreGeneration(PreGenerationProfile profile) {
		if (!PRE_GENERATION_MODES.contains(profile.mode())) {
			throw validation("preGeneration.mode", "未対応の preGeneration.mode です。", profile.mode());
		}
		if (profile.maxPreparedMinutes() < 0) {
			throw validation("preGeneration.maxPreparedMinutes", "preGeneration.maxPreparedMinutes は 0 以上で指定してください。", profile.maxPreparedMinutes());
		}
		if (profile.maxPreparedBlocks() < 0) {
			throw validation("preGeneration.maxPreparedBlocks", "preGeneration.maxPreparedBlocks は 0 以上で指定してください。", profile.maxPreparedBlocks());
		}
	}

	private static void validateReplay(ReplayProfile profile) {
		if (!REPLAY_INTENSITIES.contains(profile.intensity())) {
			throw validation("replay.intensity", "未対応の replay.intensity です。", profile.intensity());
		}
		if (profile.eligibleSegmentTypes().isEmpty()) {
			throw validation("replay.eligibleSegmentTypes", "replay.eligibleSegmentTypes は 1 件以上必要です。", null);
		}
		for (String segmentType : profile.eligibleSegmentTypes()) {
			try {
				SegmentType.valueOf(segmentType);
			} catch (IllegalArgumentException exception) {
				throw validation("replay.eligibleSegmentTypes", "未対応の segmentType が含まれています。", segmentType);
			}
		}
		if (profile.excludeLetterSegments() && profile.eligibleSegmentTypes().contains(SegmentType.LETTER.name())) {
			throw validation("replay.eligibleSegmentTypes", "excludeLetterSegments=true の場合は LETTER を eligibleSegmentTypes に含められません。", SegmentType.LETTER.name());
		}
		if (profile.minimumAssetAgeHours() < 0) {
			throw validation("replay.minimumAssetAgeHours", "replay.minimumAssetAgeHours は 0 以上で指定してください。", profile.minimumAssetAgeHours());
		}
		if (profile.cooldownHours() < 0) {
			throw validation("replay.cooldownHours", "replay.cooldownHours は 0 以上で指定してください。", profile.cooldownHours());
		}
		if (profile.maxReplaySharePercent() < 0 || profile.maxReplaySharePercent() > 100) {
			throw validation("replay.maxReplaySharePercent", "replay.maxReplaySharePercent は 0 から 100 の範囲で指定してください。", profile.maxReplaySharePercent());
		}
	}

	private static void validateComposition(CompositionProfile profile) {
		if (!profile.targetSegmentShares().keySet().containsAll(TARGET_SHARE_KEYS) || profile.targetSegmentShares().size() != TARGET_SHARE_KEYS.size()) {
			throw validation("composition.targetSegmentShares", "targetSegmentShares は talk/letter/music/jingle の 4 要素で指定してください。", profile.targetSegmentShares().keySet());
		}
		int totalShare = 0;
		for (String key : TARGET_SHARE_KEYS) {
			Integer share = profile.targetSegmentShares().get(key);
			if (share == null || share < 0) {
				throw validation("composition.targetSegmentShares." + key, "targetSegmentShares は 0 以上で指定してください。", share);
			}
			totalShare += share;
		}
		if (totalShare != 100) {
			throw validation("composition.targetSegmentShares", "targetSegmentShares の合計は 100 である必要があります。", totalShare);
		}
		if (profile.maxConsecutiveTalkSegments() < 1) {
			throw validation("composition.maxConsecutiveTalkSegments", "composition.maxConsecutiveTalkSegments は 1 以上で指定してください。", profile.maxConsecutiveTalkSegments());
		}
		if (profile.musicBreakIntervalMinutes() < 1) {
			throw validation("composition.musicBreakIntervalMinutes", "composition.musicBreakIntervalMinutes は 1 以上で指定してください。", profile.musicBreakIntervalMinutes());
		}
		if (profile.letterPriorityBoostThreshold() < 0) {
			throw validation("composition.letterPriorityBoostThreshold", "composition.letterPriorityBoostThreshold は 0 以上で指定してください。", profile.letterPriorityBoostThreshold());
		}
	}

	private static ApiException validation(String field, String message, Object value) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("field", field);
		if (value != null) {
			details.put("value", value);
		}
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, details);
	}

	private static <T> T defaultIfNull(T value, T fallback) {
		return value == null ? fallback : value;
	}

	private static Integer asInteger(Object value, Integer fallback) {
		if (value == null) {
			return fallback;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(value.toString());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static Boolean asBoolean(Object value, Boolean fallback) {
		if (value == null) {
			return fallback;
		}
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		return Boolean.parseBoolean(value.toString());
	}

	private static String normalizeUpper(Object value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String normalized = value.toString().trim();
		if (normalized.isBlank()) {
			return fallback;
		}
		return normalized.toUpperCase(Locale.ROOT);
	}

	private static String normalizeShareKey(Object value) {
		return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
	}

	private static List<String> asStringList(Object value, List<String> fallback) {
		if (!(value instanceof Iterable<?> iterable)) {
			return fallback;
		}
		List<String> normalized = new ArrayList<>();
		for (Object element : iterable) {
			String item = normalizeUpper(element, null);
			if (item != null) {
				normalized.add(item);
			}
		}
		return normalized.isEmpty() ? fallback : List.copyOf(normalized);
	}

	public record PreGenerationProfile(
			String mode,
			Integer maxPreparedMinutes,
			Integer maxPreparedBlocks,
			Boolean preferCacheReuse) {
	}

	public record ReplayProfile(
			String intensity,
			List<String> eligibleSegmentTypes,
			Integer minimumAssetAgeHours,
			Integer cooldownHours,
			Integer maxReplaySharePercent,
			Boolean excludeLetterSegments) {
	}

	public record CompositionProfile(
			Map<String, Integer> targetSegmentShares,
			Integer maxConsecutiveTalkSegments,
			Integer musicBreakIntervalMinutes,
			Integer letterPriorityBoostThreshold,
			Boolean allowSoftFallbackRetiming) {
	}
}
