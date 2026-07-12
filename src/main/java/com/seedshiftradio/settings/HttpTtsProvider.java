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
	public SynthesizedAudio synthesize(ProviderRegistry.ResolvedProvider provider, QueueItemEntity item, SpeechDirectiveResponse directive) {
		String adapter = resolveAdapter(provider, directive);
		return switch (adapter) {
			case ADAPTER_IRODORI -> synthesizeIrodori(provider, item, directive);
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
			SpeechDirectiveResponse directive) {
		VoiceHint voiceHint = VoiceHint.parse(directive.voiceHint());
		String voice = isIrodoriEngine(voiceHint.engine()) && voiceHint.speakerKey() != null
				? voiceHint.speakerKey()
				: "none";
		String model = provider.defaultModelProfileId() == null || provider.defaultModelProfileId().isBlank()
				? "irodori-tts"
				: provider.defaultModelProfileId();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("input", synthesisText(directive));
		body.put("voice", voice);
		body.put("response_format", "wav");
		body.put("speed", 1.0d);
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
		metadata.put("voiceId", voice);
		metadata.put("styleKey", voiceHint.styleKey());
		metadata.put("responseFormat", "wav");
		metadata.put("streamingSupported", false);
		return new SynthesizedAudio(audio, provider.providerKey() + ":irodori:" + sha256(model + ":" + voice), metadata);
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
		metadata.put("voiceHint", directive.voiceHint());
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
			throw new TtsSynthesisException("PROVIDER_REJECTED", "TTS 入力テキストが空です。");
		}
		return text;
	}

	private HttpResponse<String> sendString(HttpRequest request, String adapter) {
		try {
			return httpClient(request).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (HttpTimeoutException exception) {
			throw new TtsSynthesisException("PROVIDER_TIMEOUT", adapter + " TTS provider がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw new TtsSynthesisException("PROVIDER_UNREACHABLE", adapter + " TTS provider へ接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TtsSynthesisException("PROVIDER_INTERRUPTED", adapter + " TTS provider 呼び出しが中断されました。", exception);
		}
	}

	private HttpResponse<byte[]> sendBytes(HttpRequest request, String adapter) {
		try {
			return httpClient(request).send(request, HttpResponse.BodyHandlers.ofByteArray());
		} catch (HttpTimeoutException exception) {
			throw new TtsSynthesisException("PROVIDER_TIMEOUT", adapter + " TTS provider がタイムアウトしました。", exception);
		} catch (IOException exception) {
			throw new TtsSynthesisException("PROVIDER_UNREACHABLE", adapter + " TTS provider へ接続できません。", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TtsSynthesisException("PROVIDER_INTERRUPTED", adapter + " TTS provider 呼び出しが中断されました。", exception);
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

	private String classify(int statusCode, String body) {
		String normalizedBody = body == null ? "" : body.toLowerCase(Locale.ROOT);
		if (normalizedBody.contains("consent")) {
			return "VOICE_CONSENT_REQUIRED";
		}
		if (normalizedBody.contains("voice") && (normalizedBody.contains("not found") || normalizedBody.contains("missing"))) {
			return "VOICE_REF_NOT_FOUND";
		}
		if (statusCode == 408 || statusCode == 504) {
			return "PROVIDER_TIMEOUT";
		}
		if (statusCode == 429 || statusCode == 503) {
			return "PROVIDER_RESOURCE_EXHAUSTED";
		}
		if (statusCode >= 400 && statusCode < 500) {
			return "PROVIDER_REJECTED";
		}
		return "PROVIDER_BAD_RESPONSE";
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
			throw new TtsSynthesisException("PROVIDER_BAD_RESPONSE", "TTS provider が WAV 以外の応答を返しました。");
		}
	}

	private byte[] jsonBytes(Map<String, Object> body) {
		try {
			return objectMapper.writeValueAsBytes(body);
		} catch (JsonProcessingException exception) {
			throw new TtsSynthesisException("PROVIDER_BAD_RESPONSE", "TTS request の JSON 化に失敗しました。", exception);
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
