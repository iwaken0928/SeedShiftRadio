package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seedshiftradio.domain.ProviderErrorCode;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.domain.SegmentType;
import com.seedshiftradio.domain.SlotRole;
import com.seedshiftradio.radio.QueueItemEntity;
import com.seedshiftradio.radio.SpeechDirectiveResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class HttpTtsProviderTests {

	@TempDir
	Path tempDir;

	HttpServer httpServer;

	final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@AfterEach
	void tearDown() {
		if (httpServer != null) {
			httpServer.stop(0);
		}
	}

	@Test
	void synthesizeUsesVoicevoxAudioQueryAndSynthesis() throws Exception {
		byte[] wav = wavBytes();
		AtomicReference<String> audioQueryText = new AtomicReference<>();
		AtomicReference<String> audioQuerySpeaker = new AtomicReference<>();
		AtomicReference<String> synthesisSpeaker = new AtomicReference<>();
		httpServer = startServer(Map.of(
				"/audio_query", exchange -> {
					Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
					audioQueryText.set(query.get("text"));
					audioQuerySpeaker.set(query.get("speaker"));
					write(exchange, 200, "application/json", "{\"accent_phrases\":[]}".getBytes(StandardCharsets.UTF_8));
				},
				"/synthesis", exchange -> {
					synthesisSpeaker.set(parseQuery(exchange.getRequestURI().getRawQuery()).get("speaker"));
					write(exchange, 200, "audio/wav", wav);
				}));
		HttpTtsProvider provider = provider();

		TtsProvider.SynthesizedAudio audio = provider.synthesize(
				new ProviderRegistry.ResolvedProvider(
						ProviderType.TTS,
						"tts",
						"voicevox",
						baseUrl(),
						"/version",
						1_000,
						List.of("TTS_GEN", "VOICEVOX"),
						false),
				queueItem(),
				directive("VOICEVOX:4:normal"));

		assertArrayEquals(wav, audio.audioBytes());
		assertEquals("こんにちは。", audioQueryText.get());
		assertEquals("4", audioQuerySpeaker.get());
		assertEquals("4", synthesisSpeaker.get());
		assertEquals("VOICEVOX", audio.metadata().get("adapter"));
		assertEquals("4", audio.metadata().get("speakerKey"));
		assertFalse(audio.metadata().containsKey("normalizedText"));
		assertFalse(audio.metadata().containsValue("こんにちは。"));
	}

	@Test
	void synthesizeUsesIrodoriOpenAiCompatibleSpeechEndpoint() throws Exception {
		byte[] wav = wavBytes();
		Path tokenFile = tempDir.resolve("irodori-token.txt");
		Files.writeString(tokenFile, "secret-token", StandardCharsets.UTF_8);
		AtomicReference<JsonNode> requestBody = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		httpServer = startServer(Map.of(
				"/v1/audio/speech", exchange -> {
					authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
					requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
					write(exchange, 200, "audio/wav", wav);
				}));
		HttpTtsProvider provider = provider();

		TtsProvider.SynthesizedAudio audio = provider.synthesize(
				new ProviderRegistry.ResolvedProvider(
						ProviderType.TTS,
						"tts",
						"irodori",
						baseUrl(),
						"/health",
						1_000,
						List.of("TTS_GEN", "IRODORI_TTS", "OPENAI_AUDIO_SPEECH"),
						"IRODORI_OPENAI_TTS",
						"file:" + tokenFile,
						"irodori-tts",
						Map.of(),
						false),
				queueItem(),
				directive("IRODORI_TTS:voice-night:soft"),
				new TtsRuntimeProfile(
						"profile-night",
						"station-night",
						"irodori",
						"IRODORI_TTS",
						"voice-night",
						"soft",
						new BigDecimal("1.15"),
						Map.of(
								"responseFormat", "wav",
								"irodori", Map.of(
										"numSteps", 24,
										"chunking", false,
										"lora_adapter", "C:/private/model")),
						"voices/approved-night.wav",
						"consent-policy-night"));

		assertArrayEquals(wav, audio.audioBytes());
		assertEquals("Bearer secret-token", authorization.get());
		assertEquals("irodori-tts", requestBody.get().path("model").asText());
		assertEquals("こんにちは。", requestBody.get().path("input").asText());
		assertEquals("approved-night", requestBody.get().path("voice").asText());
		assertEquals("wav", requestBody.get().path("response_format").asText());
		assertEquals(1.15d, requestBody.get().path("speed").asDouble());
		assertEquals(24, requestBody.get().path("irodori").path("num_steps").asInt());
		assertEquals(false, requestBody.get().path("irodori").path("chunking_enabled").asBoolean());
		assertFalse(requestBody.get().path("irodori").has("lora_adapter"));
		assertEquals("IRODORI_OPENAI_TTS", audio.metadata().get("adapter"));
		assertEquals("profile-night", audio.metadata().get("voiceProfileId"));
		assertEquals(64, audio.metadata().get("consentPolicyHash").toString().length());
		assertFalse(audio.metadata().containsValue("consent-policy-night"));
		assertFalse(audio.metadata().containsValue("voices/approved-night.wav"));
		assertFalse(audio.metadata().containsKey("normalizedText"));
		assertFalse(audio.metadata().containsValue("こんにちは。"));
	}

	@Test
	void synthesizeClassifiesIrodoriAuthenticationFailureBeforeResponseBodyHints() throws Exception {
		httpServer = startServer(Map.of(
				"/v1/audio/speech", exchange -> write(
						exchange,
						401,
						"application/json",
						"{\"error\":\"consent required\"}".getBytes(StandardCharsets.UTF_8))));
		HttpTtsProvider provider = provider();

		TtsSynthesisException exception = assertThrows(
				TtsSynthesisException.class,
				() -> provider.synthesize(
						new ProviderRegistry.ResolvedProvider(
								ProviderType.TTS,
								"tts",
								"irodori",
								baseUrl(),
								"/health",
								1_000,
								List.of("TTS_GEN", "IRODORI_TTS"),
								"IRODORI_OPENAI_TTS",
								null,
								"irodori-tts",
								Map.of(),
								false),
						queueItem(),
						directive("IRODORI_TTS:voice-night:soft")));

		assertEquals(ProviderErrorCode.PROVIDER_AUTH_FAILED, exception.providerErrorCode());
	}

	@Test
	void placeholderProviderWinsOverVoiceHintAdapter() {
		HttpTtsProvider provider = provider();

		TtsProvider.SynthesizedAudio audio = provider.synthesize(
				new ProviderRegistry.ResolvedProvider(
						ProviderType.TTS,
						"tts",
						"seedshift-placeholder",
						"http://127.0.0.1:1",
						"/health",
						100,
						List.of("TTS_GEN"),
						false),
				queueItem(),
				directive("VOICEVOX:4:normal"));

		assertEquals(true, audio.metadata().get("placeholder"));
		assertEquals("seedshift-placeholder", audio.metadata().get("providerKey"));
		assertFalse(audio.metadata().containsKey("voiceHint"));
		assertEquals('R', audio.audioBytes()[0]);
		assertEquals('I', audio.audioBytes()[1]);
		assertEquals('F', audio.audioBytes()[2]);
		assertEquals('F', audio.audioBytes()[3]);
	}

	private HttpTtsProvider provider() {
		return new HttpTtsProvider(new PlaceholderTtsProvider(new PlaceholderAudioFactory()), objectMapper);
	}

	private QueueItemEntity queueItem() {
		QueueItemEntity item = mock(QueueItemEntity.class);
		when(item.getId()).thenReturn("queue-tts-1");
		when(item.getSegmentType()).thenReturn(SegmentType.TALK);
		when(item.getSlotRole()).thenReturn(SlotRole.TOPIC);
		when(item.getDurationMs()).thenReturn(10_000);
		return item;
	}

	private SpeechDirectiveResponse directive(String voiceHint) {
		return new SpeechDirectiveResponse(
				"sd-queue-tts-1",
				"こんにちは。",
				"こんにちは。",
				List.of(),
				"calm",
				"medium",
				List.of(),
				"persona-night-main",
				voiceHint,
				"corr-tts-1");
	}

	private HttpServer startServer(Map<String, HttpHandler> handlers) throws IOException {
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		handlers.forEach(server::createContext);
		server.start();
		return server;
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + httpServer.getAddress().getPort();
	}

	private Map<String, String> parseQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return Map.of();
		}
		return java.util.Arrays.stream(rawQuery.split("&"))
				.map(entry -> entry.split("=", 2))
				.collect(java.util.stream.Collectors.toMap(
						parts -> decode(parts[0]),
						parts -> parts.length > 1 ? decode(parts[1]) : ""));
	}

	private String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private void write(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(statusCode, body.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(body);
		}
	}

	private byte[] wavBytes() {
		return new byte[] {
				'R', 'I', 'F', 'F',
				36, 0, 0, 0,
				'W', 'A', 'V', 'E',
				'f', 'm', 't', ' '
		};
	}
}
