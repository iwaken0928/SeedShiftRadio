package com.seedshiftradio.radio;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.seedshiftradio.settings.GeneratedAssetRepository;

@Testcontainers(disabledWithoutDocker = true)
@Tag("docker")
@SpringBootTest(properties = {
		"seedshift.radio.security.admin-token=test-admin-token",
		"seedshift.radio.config.path=./build/test-settings/radio-config.json"
})
class RadioApiTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("seedshift_radio")
			.withUsername("seedshift")
			.withPassword("seedshift");

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	PlayoutSessionRepository playoutSessionRepository;

	@Autowired
	QueueItemRepository queueItemRepository;

	@Autowired
	GeneratedAssetRepository generatedAssetRepository;

	@Autowired
	PlayHistoryRepository playHistoryRepository;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	@Test
	void adminTokenIsRequiredForMonitorSummary() throws Exception {
		mockMvc.perform(get("/api/monitor/summary"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_AUTH_REQUIRED"));
	}

	@Test
	void adminTokenIsRequiredForLetterList() throws Exception {
		mockMvc.perform(get("/api/letters"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_AUTH_REQUIRED"));
	}

	@Test
	void adminTokenIsRequiredForSettings() throws Exception {
		mockMvc.perform(get("/api/settings"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_AUTH_REQUIRED"));
	}

	@Test
	void tuneCreatesRadioSessionAndQueue() throws Exception {
		mockMvc.perform(post("/api/radio/tune")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "stationId": "station-night",
								  "requestedBy": "test",
								  "resumePlayback": true
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stationId").value("station-night"))
				.andExpect(jsonPath("$.state").value("PREPARING"))
				.andExpect(jsonPath("$.queueWarmupStarted").value(true))
				.andExpect(jsonPath("$.correlationId").exists());

		awaitWarmup("station-night", "PLAYING");

		PlayoutSessionEntity session = playoutSessionRepository.findFirstByOrderByStartedAtDesc().orElseThrow();
		assertEquals("test", session.getRequestedBy());
		assertTrue(session.isResumePlayback());
		assertTrue(queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()).stream()
				.filter(item -> item.getSegmentType() != com.seedshiftradio.domain.SegmentType.MUSIC_AI)
				.allMatch(item -> item.getAssetId() != null && generatedAssetRepository.findById(item.getAssetId()).isPresent()));

		mockMvc.perform(post("/api/radio/play"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("PLAYING"));

		mockMvc.perform(get("/api/radio/next-segment"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").exists())
				.andExpect(jsonPath("$.status").value("READY"));
	}

	@Test
	void monitorSummaryWorksWithAdminToken() throws Exception {
		mockMvc.perform(post("/api/radio/tune")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "stationId": "station-night",
								  "requestedBy": "test",
								  "resumePlayback": true
								}
								"""))
				.andExpect(status().isOk());

		awaitWarmup("station-night", "PLAYING");

		mockMvc.perform(get("/api/monitor/summary").header("X-Admin-Token", "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionId").exists())
				.andExpect(jsonPath("$.bufferReadyCount").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.providerHealth.llm.status").exists());
	}

	@Test
	void settingsEndpointReturnsConfigDocument() throws Exception {
		mockMvc.perform(get("/api/settings").header("X-Admin-Token", "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.schemaVersion").value("2026-03"))
				.andExpect(jsonPath("$.providers.llm.defaultProvider").exists())
				.andExpect(jsonPath("$.features.streaming.placeholderEnabled").value(true));
	}

	@Test
	void assetEndpointReturnsAudioWav() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/assets/audio/{assetId}.wav", "queue-asset-123"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", startsWith("audio/wav")))
				.andReturn();

		assertTrue(result.getResponse().getContentLengthLong() > 0);
	}

	@Test
	void tuneCanRemainPreparingWhenResumePlaybackIsFalse() throws Exception {
		mockMvc.perform(post("/api/radio/tune")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "stationId": "station-night",
								  "requestedBy": "test",
								  "resumePlayback": false
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("PREPARING"));

		awaitWarmup("station-night", "PREPARING");
	}

	@Test
	void nextSpeechDirectiveUsesRegisteredClientVoiceHint() throws Exception {
		mockMvc.perform(post("/api/clients/capabilities")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "clientId": "desktop-win-main",
								  "clientType": "CSHARP_NATIVE",
								  "supportsClientSideTts": true,
								  "supportedVoiceEngines": ["VOICEVOX", "VOICEROID"],
								  "preferredPlaybackMode": "CLIENT_TTS",
								  "localVoiceProfiles": [
								    {
								      "engine": "VOICEROID",
								      "profileKey": "yukari-main"
								    }
								  ]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.clientId").value("desktop-win-main"));

		mockMvc.perform(post("/api/radio/tune")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "stationId": "station-night",
								  "requestedBy": "test",
								  "resumePlayback": false
								}
								"""))
				.andExpect(status().isOk());

		awaitWarmup("station-night", "PREPARING");

		mockMvc.perform(get("/api/radio/next-speech-directive").param("clientId", "desktop-win-main"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.personaRef").value("persona-night-main"))
				.andExpect(jsonPath("$.voiceHint").value("VOICEROID:yukari-main"))
				.andExpect(jsonPath("$.text").exists());
	}

	@Test
	void playbackEventCreatesPlayHistory() throws Exception {
		mockMvc.perform(post("/api/radio/tune")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "stationId": "station-night",
								  "requestedBy": "test",
								  "resumePlayback": false
								}
								"""))
				.andExpect(status().isOk());

		awaitWarmup("station-night", "PREPARING");

		PlayoutSessionEntity session = playoutSessionRepository.findFirstByOrderByStartedAtDesc().orElseThrow();
		QueueItemEntity item = queueItemRepository.findBySessionIdOrderBySequenceNoAsc(session.getId()).stream()
				.filter(queueItem -> queueItem.getStatus() == com.seedshiftradio.domain.QueueItemStatus.READY)
				.findFirst()
				.orElseThrow();

		mockMvc.perform(post("/api/radio/playback-events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "clientId": "web-client",
								  "sessionId": "%s",
								  "itemId": "%s",
								  "eventType": "SEGMENT_STARTED",
								  "occurredAt": "2026-03-29T10:00:00Z"
								}
								""".formatted(session.getId(), item.getId())))
				.andExpect(status().isAccepted());

		mockMvc.perform(post("/api/radio/playback-events")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "clientId": "web-client",
								  "sessionId": "%s",
								  "itemId": "%s",
								  "eventType": "SEGMENT_ENDED",
								  "occurredAt": "2026-03-29T10:00:05Z"
								}
								""".formatted(session.getId(), item.getId())))
				.andExpect(status().isAccepted());

		assertTrue(playHistoryRepository.findBySessionIdOrderByPlayedAtDesc(session.getId()).stream()
				.anyMatch(history -> history.getQueueItemId().equals(item.getId())
						&& history.getResultStatus() == com.seedshiftradio.domain.PlayHistoryResultStatus.DONE));
	}

	private void awaitWarmup(String stationId, String expectedState) throws Exception {
		AssertionError lastAssertion = null;
		Exception lastException = null;
		for (int attempt = 0; attempt < 30; attempt++) {
			try {
				mockMvc.perform(get("/api/radio/status"))
						.andExpect(status().isOk())
						.andExpect(jsonPath("$.stationId").value(stationId))
						.andExpect(jsonPath("$.state").value(expectedState))
						.andExpect(jsonPath("$.bufferReadyCount").value(greaterThanOrEqualTo(1)));

				mockMvc.perform(get("/api/radio/queue"))
						.andExpect(status().isOk())
						.andExpect(jsonPath("$.items.length()").value(greaterThanOrEqualTo(1)));
				return;
			} catch (AssertionError exception) {
				lastAssertion = exception;
			} catch (Exception exception) {
				lastException = exception;
			}
			Thread.sleep(100L);
		}
		if (lastAssertion != null) {
			throw lastAssertion;
		}
		if (lastException != null) {
			throw lastException;
		}
		fail("Radio warmup did not complete in time.");
	}
}
