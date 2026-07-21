package com.seedshiftradio.settings;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.SpeechDirectiveResponse;

@Component
public class HttpTtsProvider implements TtsProvider {

	private static final String ADAPTER_VOICEVOX = "VOICEVOX";
	private static final String ADAPTER_IRODORI = "IRODORI_OPENAI_TTS";
	private static final String PLACEHOLDER_PROVIDER_KEY = "seedshift-placeholder";

	private final PlaceholderTtsProvider placeholderTtsProvider;
	private final ObjectMapper objectMapper;

	public HttpTtsProvider(PlaceholderTtsProvider placeholderTtsProvider, ObjectMapper objectMapper) {
		this.placeholderTtsProvider = placeholderTtsProvider;
		this.objectMapper = objectMapper;
	}

	@Override
	public SynthesizedAudio synthesize(
			ProviderRegistry.ResolvedProvider provider,
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			TtsRuntimeProfile runtimeProfile) {
		String adapter = resolveAdapter(provider, directive);
		return switch (adapter) {
			case ADAPTER_IRODORI -> synthesizeIrodori(provider, item, directive, runtimeProfile);
			case ADAPTER_VOICEVOX -> synthesizeVoicevox(provider, item, directive);
			default -> placeholderTtsProvider.synthesize(provider, item, directive);
		};
	}

	private SynthesizedAudio synthesizeVoicevox(
			ProviderRegistry.ResolvedProvider provider,
			QueueItemEntity item,
			SpeechDirectiveResponse directive) {
		VoiceHint voiceHint = VoiceHint.parse(directive.voiceHint());
		String speaker = ADAPTER_VOICEVOX.equals(voiceHint.engine()) && voiceHint.speakerKey() != null
				? voiceHint.speakerKey()
				: "1";
		String text = synthesisText(directive);
		String audioQuery = sendVoicevoxAudioQuery(provider, text, speaker);
		byte[] audio = sendVoicevoxSynthesis(provider, audioQuery, speaker);
		ensureWavAudio(audio);
		Map<String, Object> metadata = baseMetadata(provider, item, directive, ADAPTER_VOICEVOX);
		metadata.put("speakerKey", speaker);
		metadata.put("styleKey", voiceHint.styleKey());
		metadata.put("responseFormat", "wav");
		return new SynthesizedAudio(audio, provider.providerKey() + ":voicevox:" + speaker, metadata);
	}

	private String sendVoicevoxAudioQuery(ProviderRegistry.ResolvedProvider provider, String text, String speaker) {
		String path = "/audio_query?text=" + encode(text) + "&speaker=" + encode(speaker);
		HttpRequest request = HttpRequest.newBuilder(resolve(provider, path))
				.POST(HttpRequest.BodyPublishers.noBody())
				.timeout(timeout(provider))
				.header("Accept", "application/json")
				.build();
		HttpResponse<String> response = sendString(request, ADAPTER_VOICEVOX);
		if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().isBlank()) {
			throw failure(ADAPTER_VOICEVOX, response.statusCode(), response.body());
		}
		return response.body();
	}

	private byte[] sendVoicevoxSynthesis(ProviderRegistry.ResolvedProvider provider, String audioQuery, String speaker) {
		String path = "/synthesis?speaker=" + encode(speaker);
		HttpRequest request = HttpRequest.newBuilder(resolve(provider, path))
				.POST(HttpRequest.BodyPublishers.ofString(audioQuery, StandardCharsets.UTF_8))
				.timeout(timeout(provider))
				.header("Accept", "audio/wav")
				.header("Content-Type", "application/json")
				.build();
		HttpResponse<byte[]> response = sendBytes(request, ADAPTER_VOICEVOX);
		if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().length == 0) {
			throw failure(ADAPTER_VOICEVOX, response.statusCode(), null);
		}
		return response.body();
	}

	private SynthesizedAudio synthesizeIrodori(
			ProviderRegistry.ResolvedProvider provider,
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			TtsRuntimeProfile runtimeProfile) {
		VoiceHint voiceHint = VoiceHint.parse(directive.voiceHint());
		String voice = resolveIrodoriVoice(runtimeProfile, voiceHint);
		String model = provider.defaultModelProfileId() == null || provider.defaultModelProfileId().isBlank()
				? "irodori-tts"
				: provider.defaultModelProfileId();
		String responseFormat = resolveResponseFormat(runtimeProfile);
		Map<String, Object> irodoriOptions = resolveIrodoriOptions(runtimeProfile);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("input", synthesisText(directive));
		body.put("voice", voice);
		body.put("response_format", responseFormat);
		body.put("speed", runtimeProfile == null ? 1.0d : runtimeProfile.speed().doubleValue());
		if (!irodoriOptions.isEmpty()) {
			body.put("irodori", irodoriOptions);
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(provider, "/v1/audio/speech"))
				.POST(HttpRequest.BodyPublishers.ofByteArray(jsonBytes(body)))
				.timeout(timeout(provider))
				.header("Accept", "audio/wav")
				.header("Content-Type", "application/json");
		String apiKey = resolveSecret(provider.apiKeyRef());
		if (apiKey != null && !apiKey.isBlank()) {
			builder.header("Authorization", "Bearer " + apiKey);
		}
		HttpResponse<byte[]> response = sendBytes(builder.build(), ADAPTER_IRODORI);
		if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().length == 0) {
			throw failure(ADAPTER_IRODORI, response.statusCode(), bodyPreview(response.body()));
		}
		byte[] audio = response.body();
		ensureWavAudio(audio);
		Map<String, Object> metadata = baseMetadata(provider, item, directive, ADAPTER_IRODORI);
		metadata.put("model", model);
		metadata.put("responseFormat", responseFormat);
		metadata.put("streamingSupported", false);
		if (runtimeProfile == null) {
			metadata.put("voiceIdHash", sha256(voice));
			metadata.put("stylePreset", voiceHint.styleKey());
		} else {
			metadata.put("stationId", runtimeProfile.stationId());
			metadata.put("voiceProfileId", runtimeProfile.voiceProfileId());
			metadata.put("engineType", runtimeProfile.engineType());
			metadata.put("speed", runtimeProfile.speed());
			metadata.put("stylePreset", runtimeProfile.styleKey());
			if (runtimeProfile.referenceVoiceRef() == null) {
				metadata.put("voiceIdHash", sha256(voice));
			} else {
				metadata.put("referenceVoiceHash", sha256(runtimeProfile.referenceVoiceRef()));
				metadata.put("consentPolicyHash", sha256(runtimeProfile.consentPolicyRef()));
			}
		}
		String fingerprintSource = model + ":" + voice + ":" + body.get("speed") + ":" + irodoriOptions;
		return new SynthesizedAudio(audio, provider.providerKey() + ":irodori:" + sha256(fingerprintSource), metadata);
	}

	private String resolveIrodoriVoice(TtsRuntimeProfile runtimeProfile, VoiceHint voiceHint) {
		if (runtimeProfile != null) {
			if (!isIrodoriEngine(runtimeProfile.engineType())) {
				throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_REJECTED, "Irodori TTS と音声プロファイルの engine が一致しません。");
			}
			if (runtimeProfile.referenceVoiceRef() != null) {
				return canonicalVoiceId(runtimeProfile.referenceVoiceRef());
			}
			if (runtimeProfile.speakerKey() != null && !runtimeProfile.speakerKey().isBlank()) {
				return runtimeProfile.speakerKey();
			}
		}
		return isIrodoriEngine(voiceHint.engine()) && voiceHint.speakerKey() != null
				? voiceHint.speakerKey()
				: "none";
	}

	private String canonicalVoiceId(String referenceVoiceRef) {
		String fileName = java.nio.file.Path.of(referenceVoiceRef).getFileName().toString();
		int extensionIndex = fileName.lastIndexOf('.');
		String voiceId = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
		if (voiceId.isBlank()) {
			throw new TtsSynthesisException(ProviderErrorCode.VOICE_REF_NOT_FOUND, "TTS 参照音声を解決できません。");
		}
		return voiceId;
	}

	private String resolveResponseFormat(TtsRuntimeProfile runtimeProfile) {
		Object configured = runtimeProfile == null ? null : runtimeProfile.providerOptions().get("responseFormat");
		String responseFormat = configured == null ? "wav" : configured.toString().toLowerCase(Locale.ROOT);
		if (!"wav".equals(responseFormat)) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_REJECTED, "TTS asset は WAV 形式だけを利用できます。");
		}
		return responseFormat;
	}

	private Map<String, Object> resolveIrodoriOptions(TtsRuntimeProfile runtimeProfile) {
		if (runtimeProfile == null) {
			return Map.of();
		}
		Object configured = runtimeProfile.providerOptions().get("irodori");
		if (!(configured instanceof Map<?, ?> source)) {
			return Map.of();
		}
		Map<String, Object> sanitized = new LinkedHashMap<>();
		copyOption(source, sanitized, "num_steps", "num_steps", "numSteps");
		copyOption(source, sanitized, "seed", "seed");
		copyOption(source, sanitized, "cfg_scale_text", "cfg_scale_text", "cfgScaleText");
		copyOption(source, sanitized, "cfg_scale_speaker", "cfg_scale_speaker", "cfgScaleSpeaker");
		copyOption(source, sanitized, "t_schedule_mode", "t_schedule_mode", "tScheduleMode");
		copyOption(source, sanitized, "sway_coeff", "sway_coeff", "swayCoeff");
		copyOption(source, sanitized, "chunking_enabled", "chunking_enabled", "chunkingEnabled", "chunking");
		copyOption(source, sanitized, "chunk_min_chars", "chunk_min_chars", "chunkMinChars");
		return Map.copyOf(sanitized);
	}

	private void copyOption(Map<?, ?> source, Map<String, Object> target, String targetKey, String... sourceKeys) {
		for (String sourceKey : sourceKeys) {
			Object value = source.get(sourceKey);
			Object normalized = normalizeIrodoriOption(targetKey, value);
			if (normalized != null) {
				target.put(targetKey, normalized);
				return;
			}
		}
	}

	private Object normalizeIrodoriOption(String key, Object value) {
		return switch (key) {
			case "num_steps" -> integerInRange(value, 1, 200);
			case "seed" -> value instanceof Number number ? number.longValue() : null;
			case "cfg_scale_text", "cfg_scale_speaker" -> decimalInRange(value, 0.0d, 20.0d);
			case "t_schedule_mode" -> value instanceof String text && ("linear".equals(text) || "sway".equals(text)) ? text : null;
			case "sway_coeff" -> decimalInRange(value, -10.0d, 10.0d);
			case "chunking_enabled" -> value instanceof Boolean ? value : null;
			case "chunk_min_chars" -> integerInRange(value, 1, 1_000);
			default -> null;
		};
	}

	private Integer integerInRange(Object value, int minimum, int maximum) {
		if (!(value instanceof Number number)) {
			return null;
		}
		int normalized = number.intValue();
		return normalized >= minimum && normalized <= maximum ? normalized : null;
	}

	private Double decimalInRange(Object value, double minimum, double maximum) {
		if (!(value instanceof Number number)) {
			return null;
		}
		double normalized = number.doubleValue();
		return Double.isFinite(normalized) && normalized >= minimum && normalized <= maximum ? normalized : null;
	}

	private Map<String, Object> baseMetadata(
			ProviderRegistry.ResolvedProvider provider,
			QueueItemEntity item,
			SpeechDirectiveResponse directive,
			String adapter) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("queueItemId", item.getId());
		metadata.put("segmentType", item.getSegmentType().name());
		metadata.put("slotRole", item.getSlotRole().name());
		metadata.put("providerKey", provider.providerKey());
		metadata.put("adapter", adapter);
		metadata.put("speechDirectiveId", directive.id());
		metadata.put("normalizedTextHash", sha256(directive.normalizedText()));
		metadata.put("pronunciationHintCount", directive.pronunciationHints().size());
		metadata.put("pauseHintCount", directive.pauseHints().size());
		metadata.put("voiceHintHash", sha256(directive.voiceHint()));
		metadata.put("personaRef", directive.personaRef());
		metadata.put("archiveEligible", false);
		return metadata;
	}

	private String resolveAdapter(ProviderRegistry.ResolvedProvider provider, SpeechDirectiveResponse directive) {
		String configured = upper(provider.adapter());
		if (ADAPTER_IRODORI.equals(configured) || ADAPTER_VOICEVOX.equals(configured)) {
			return configured;
		}
		if (PLACEHOLDER_PROVIDER_KEY.equals(provider.providerKey())) {
			return "PLACEHOLDER";
		}
		VoiceHint voiceHint = VoiceHint.parse(directive.voiceHint());
		if (isIrodoriEngine(voiceHint.engine())
				|| provider.capabilities().contains("IRODORI_TTS")
				|| provider.capabilities().contains("OPENAI_AUDIO_SPEECH")) {
			return ADAPTER_IRODORI;
		}
		if (ADAPTER_VOICEVOX.equals(voiceHint.engine())
				|| provider.capabilities().contains("VOICEVOX")
				|| provider.providerKey().toLowerCase(Locale.ROOT).contains("voicevox")) {
			return ADAPTER_VOICEVOX;
		}
		return "PLACEHOLDER";
	}

	private String synthesisText(SpeechDirectiveResponse directive) {
		String text = directive.normalizedText() == null || directive.normalizedText().isBlank()
				? directive.text()
				: directive.normalizedText();
		if (text == null || text.isBlank()) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_REJECTED, "TTS 入力テキストが空です。");
		}
		return text;
	}

	private HttpResponse<String> sendString(HttpRequest request, String adapter) {
		try {
			return httpClient(request).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (HttpTimeoutException exception) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_TIMEOUT, adapter + " TTS provider がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_UNREACHABLE, adapter + " TTS provider へ接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_INTERRUPTED, adapter + " TTS provider 呼び出しが中断されました。", exception);
		}
	}

	private HttpResponse<byte[]> sendBytes(HttpRequest request, String adapter) {
		try {
			return httpClient(request).send(request, HttpResponse.BodyHandlers.ofByteArray());
		} catch (HttpTimeoutException exception) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_TIMEOUT, adapter + " TTS provider がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_UNREACHABLE, adapter + " TTS provider へ接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_INTERRUPTED, adapter + " TTS provider 呼び出しが中断されました。", exception);
		}
	}

	private HttpClient httpClient(HttpRequest request) {
		return HttpClient.newBuilder()
				.connectTimeout(request.timeout().orElse(Duration.ofSeconds(5)))
				.build();
	}

	private TtsSynthesisException failure(String adapter, int statusCode, String body) {
		return new TtsSynthesisException(classify(statusCode, body), adapter + " TTS provider が HTTP " + statusCode + " を返しました。");
	}

	private ProviderErrorCode classify(int statusCode, String body) {
		ProviderErrorCode httpErrorCode = ProviderErrorClassifier.fromHttpStatus(statusCode);
		if (httpErrorCode != ProviderErrorCode.PROVIDER_REJECTED) {
			return httpErrorCode;
		}
		String normalizedBody = body == null ? "" : body.toLowerCase(Locale.ROOT);
		if (normalizedBody.contains("consent")) {
			return ProviderErrorCode.VOICE_CONSENT_REQUIRED;
		}
		if (normalizedBody.contains("voice") && (normalizedBody.contains("not found") || normalizedBody.contains("missing"))) {
			return ProviderErrorCode.VOICE_REF_NOT_FOUND;
		}
		return httpErrorCode;
	}

	private void ensureWavAudio(byte[] audio) {
		if (audio.length < 12
				|| audio[0] != 'R'
				|| audio[1] != 'I'
				|| audio[2] != 'F'
				|| audio[3] != 'F'
				|| audio[8] != 'W'
				|| audio[9] != 'A'
				|| audio[10] != 'V'
				|| audio[11] != 'E') {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_BAD_RESPONSE, "TTS provider が WAV 以外の応答を返しました。");
		}
	}

	private byte[] jsonBytes(Map<String, Object> body) {
		try {
			return objectMapper.writeValueAsBytes(body);
		} catch (JsonProcessingException exception) {
			throw new TtsSynthesisException(ProviderErrorCode.PROVIDER_BAD_RESPONSE, "TTS request の JSON 化に失敗しました。", exception);
		}
	}

	private URI resolve(ProviderRegistry.ResolvedProvider provider, String path) {
		return URI.create(provider.baseUrl() + path);
	}

	private Duration timeout(ProviderRegistry.ResolvedProvider provider) {
		return Duration.ofMillis(Math.max(100, provider.timeoutMs()));
	}

	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String resolveSecret(String secretRef) {
		if (secretRef == null || secretRef.isBlank()) {
			return null;
		}
		if (secretRef.startsWith("env:")) {
			return System.getenv(secretRef.substring("env:".length()));
		}
		if (secretRef.startsWith("file:")) {
			try {
				return java.nio.file.Files.readString(java.nio.file.Path.of(secretRef.substring("file:".length()))).trim();
			} catch (IOException exception) {
				return null;
			}
		}
		return null;
	}

	private String bodyPreview(byte[] body) {
		if (body == null || body.length == 0) {
			return null;
		}
		return new String(body, 0, Math.min(body.length, 256), StandardCharsets.UTF_8);
	}

	private boolean isIrodoriEngine(String engine) {
		return "IRODORI_TTS".equals(engine) || ADAPTER_IRODORI.equals(engine);
	}

	private String upper(String value) {
		return value == null || value.isBlank() ? null : value.toUpperCase(Locale.ROOT);
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte current : bytes) {
				builder.append(String.format("%02x", current));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 が利用できません。", exception);
		}
	}

	private record VoiceHint(String engine, String speakerKey, String styleKey) {

		static VoiceHint parse(String value) {
			if (value == null || value.isBlank()) {
				return new VoiceHint(null, null, null);
			}
			String[] parts = value.split(":", 3);
			return new VoiceHint(
					parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : null,
					parts.length > 1 && !parts[1].isBlank() ? parts[1] : null,
					parts.length > 2 && !parts[2].isBlank() ? parts[2] : null);
		}
	}
}
