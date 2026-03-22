package com.seedshiftradio.radio;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "seedshift.radio.security.admin-token=test-admin-token")
class RadioApiTests {

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
				.andExpect(jsonPath("$.correlationId").exists());

		mockMvc.perform(get("/api/radio/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stationId").value("station-night"))
				.andExpect(jsonPath("$.state").value("PREPARING"))
				.andExpect(jsonPath("$.bufferReadyCount").value(greaterThanOrEqualTo(1)));

		mockMvc.perform(get("/api/radio/queue"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(greaterThanOrEqualTo(1)));

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

		mockMvc.perform(get("/api/monitor/summary").header("X-Admin-Token", "test-admin-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.correlationId").exists())
				.andExpect(jsonPath("$.readyQueueCount").value(greaterThanOrEqualTo(1)));
	}
}
