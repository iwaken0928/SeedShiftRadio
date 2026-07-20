package com.seedshiftradio.radio;

import java.util.List;

import org.springframework.stereotype.Component;

import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.settings.ProviderRegistry;

@Component
public class TemplateScriptProvider implements ScriptProvider {

	@Override
	public GeneratedScript generate(ProviderRegistry.ResolvedProvider provider, ScriptGenerationContext context) {
		String stationName = context.station() == null ? "この番組" : context.station().getName();
		String hostName = context.personality() == null ? stationName : context.personality().getDisplayName();
		String title = context.item().getTitle() == null || context.item().getTitle().isBlank()
				? context.item().getSegmentType().name()
				: context.item().getTitle();
		SlotRole slotRole = context.item().getSlotRole();
		if (slotRole == null) {
			return new GeneratedScript(title + "です。", List.of("FALLBACK_SLOT_ROLE"));
		}
		String text = switch (slotRole) {
			case OPENING -> "%s、%sがお送りします。%sです。".formatted(stationName, hostName, title);
			case LETTER -> buildLetterScript(stationName, context);
			case MUSIC_BREAK -> "ここで音楽をお送りします。%sです。".formatted(title);
			case ENDING -> "%sはこのあたりで一区切りです。%sです。".formatted(stationName, title);
			case TOPIC -> "%sの時間です。%sをお届けします。".formatted(title, stationName);
		};
		return new GeneratedScript(text, List.of("DETERMINISTIC_FALLBACK"));
	}

	private String buildLetterScript(String stationName, ScriptGenerationContext context) {
		if (context.letter() == null) {
			return "%sでは、レターを紹介します。%sです。".formatted(stationName, context.item().getTitle());
		}
		String subject = sanitize(context.letter().getSubject(), 40);
		String excerpt = sanitize(context.letter().getBody(), 80);
		return "%sでは、届いたレターを紹介します。テーマは%s。内容を短くまとめると、%s、というお便りです。".formatted(
				stationName,
				subject.isBlank() ? "近況" : subject,
				excerpt.isBlank() ? "番組へのメッセージ" : excerpt);
	}

	private String sanitize(String value, int maxLength) {
		String sanitized = value == null
				? ""
				: value.replaceAll("[\\r\\n\\t]+", " ")
						.replaceAll("(?i)(ignore previous instructions|system prompt|プロンプト|命令)", " ")
						.replaceAll("[<>\\[\\]{}]", " ")
						.trim();
		return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
	}
}
