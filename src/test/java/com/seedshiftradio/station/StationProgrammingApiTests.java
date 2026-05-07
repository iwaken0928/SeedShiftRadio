package com.seedshiftradio.station;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.seedshiftradio.support.TestSettingsFixture;

@Testcontainers(disabledWithoutDocker = true)
@Tag("docker")
@SpringBootTest(properties = {
		"seedshift.radio.security.admin-token=test-admin-token",
		"seedshift.radio.config.path=./build/test-settings/station-programming-api-config.json",
		"jobrunr.background-job-server.enabled=false"
})
class StationProgrammingApiTests {

	private static final String TEST_CONFIG_PATH = "./build/test-settings/station-programming-api-config.json";

	static {
		TestSettingsFixture.writeFastLocalConfig(TEST_CONFIG_PATH);
	}

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("seedshift_radio")
			.withUsername("seedshift")
			.withPassword("seedshift");

	@Autowired
	WebApplicationContext webApplicationContext;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	@Test
	void programmingProfileRoundTripsThroughGetEndpoints() throws Exception {
		mockMvc.perform(put("/api/stations/{id}/programming", "station-night")
						.header("X-Admin-Token", "test-admin-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validProgrammingPolicyRequest()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stationId").value("station-night"))
				.andExpect(jsonPath("$.enabled").value(true));

		mockMvc.perform(get("/api/stations/{id}/programming", "station-night")
						.header("X-Admin-Token", "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stationId").value("station-night"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.preGeneration.mode").value("ASSISTED"))
				.andExpect(jsonPath("$.preGeneration.maxPreparedMinutes").value(12))
				.andExpect(jsonPath("$.preGeneration.maxPreparedBlocks").value(2))
				.andExpect(jsonPath("$.preGeneration.preferCacheReuse").value(true))
				.andExpect(jsonPath("$.replay.intensity").value("LIGHT"))
				.andExpect(jsonPath("$.replay.eligibleSegmentTypes[0]").value("MUSIC_AI"))
				.andExpect(jsonPath("$.replay.minimumAssetAgeHours").value(6))
				.andExpect(jsonPath("$.replay.cooldownHours").value(72))
				.andExpect(jsonPath("$.replay.maxReplaySharePercent").value(20))
				.andExpect(jsonPath("$.replay.excludeLetterSegments").value(true))
				.andExpect(jsonPath("$.composition.targetSegmentShares.talk").value(40))
				.andExpect(jsonPath("$.composition.targetSegmentShares.letter").value(20))
				.andExpect(jsonPath("$.composition.targetSegmentShares.music").value(35))
				.andExpect(jsonPath("$.composition.targetSegmentShares.jingle").value(5))
				.andExpect(jsonPath("$.composition.maxConsecutiveTalkSegments").value(2))
				.andExpect(jsonPath("$.composition.musicBreakIntervalMinutes").value(8))
				.andExpect(jsonPath("$.composition.letterPriorityBoostThreshold").value(4))
				.andExpect(jsonPath("$.composition.allowSoftFallbackRetiming").value(true));

		mockMvc.perform(get("/api/stations/{id}", "station-night"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.programming.preGeneration.mode").value("ASSISTED"))
				.andExpect(jsonPath("$.programming.replay.intensity").value("LIGHT"))
				.andExpect(jsonPath("$.programming.composition.targetSegmentShares.music").value(35));
	}

	@Test
	void programmingProfileRejectsUnknownEligibleSegmentTypes() throws Exception {
		mockMvc.perform(put("/api/stations/{id}/programming", "station-night")
						.header("X-Admin-Token", "test-admin-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(programmingPolicyRequest(
								"""
								["MUSIC_AI", "NOT_A_SEGMENT"]
								""",
								"""
								{
								  "talk": 40,
								  "letter": 20,
								  "music": 35,
								  "jingle": 5
								}
								""")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void programmingProfileRejectsInvalidTargetSegmentShares() throws Exception {
		mockMvc.perform(put("/api/stations/{id}/programming", "station-night")
						.header("X-Admin-Token", "test-admin-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(programmingPolicyRequest(
								"""
								["MUSIC_AI", "MUSIC_LOCAL"]
								""",
								"""
								{
								  "talk": 50,
								  "letter": 20,
								  "music": 20,
								  "jingle": 20
								}
								""")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	private String validProgrammingPolicyRequest() {
		return programmingPolicyRequest(
				"""
				["MUSIC_AI", "MUSIC_LOCAL", "JINGLE"]
				""",
				"""
				{
				  "talk": 40,
				  "letter": 20,
				  "music": 35,
				  "jingle": 5
				}
				""");
	}

	private String programmingPolicyRequest(String eligibleSegmentTypesJson, String targetSegmentSharesJson) {
		String request = """
				{
				  "version": 4,
				  "enabled": true,
				  "defaultTemplateId": "tmpl-night-regular",
				  "fallbackStrategy": "LEGACY_RATIO",
				  "planningHorizonMinutes": 20,
				  "preGeneration": {
				    "mode": "ASSISTED",
				    "maxPreparedMinutes": 12,
				    "maxPreparedBlocks": 2,
				    "preferCacheReuse": true
				  },
				  "replay": {
				    "intensity": "LIGHT",
				    "eligibleSegmentTypes": %s,
				    "minimumAssetAgeHours": 6,
				    "cooldownHours": 72,
				    "maxReplaySharePercent": 20,
				    "excludeLetterSegments": true
				  },
				  "composition": {
				    "targetSegmentShares": %s,
				    "maxConsecutiveTalkSegments": 2,
				    "musicBreakIntervalMinutes": 8,
				    "letterPriorityBoostThreshold": 4,
				    "allowSoftFallbackRetiming": true
				  },
				  "rules": [
				    {
				      "priority": 100,
				      "days": ["MON", "TUE", "WED", "THU", "FRI"],
				      "startTime": "22:00",
				      "endTime": "02:00",
				      "minimumPendingLetters": 0,
				      "requiredProviderStates": [],
				      "templateId": "tmpl-night-regular"
				    },
				    {
				      "priority": 120,
				      "days": ["SAT", "SUN"],
				      "startTime": "22:00",
				      "endTime": "02:00",
				      "minimumPendingLetters": 3,
				      "requiredProviderStates": ["MUSICGEN_UP"],
				      "templateId": "tmpl-night-letter"
				    }
				  ]
				}
				""";
		assertTrue(request.contains("preGeneration"));
		return request.formatted(eligibleSegmentTypesJson.strip(), targetSegmentSharesJson.strip());
	}
}
