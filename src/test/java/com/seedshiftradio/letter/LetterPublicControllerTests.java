package com.seedshiftradio.letter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.seedshiftradio.common.api.ApiErrorHandler;
import com.seedshiftradio.domain.LetterStatus;
import com.seedshiftradio.domain.PlayHistoryResultStatus;
import com.seedshiftradio.domain.SegmentType;

@ExtendWith(MockitoExtension.class)
class LetterPublicControllerTests {

	MockMvc mockMvc;

	@Mock
	LetterPublicService letterPublicService;

	@BeforeEach
	void setUp() {
		LetterPublicDtos.LetterPublicPlayHistorySummary playHistory = new LetterPublicDtos.LetterPublicPlayHistorySummary(
				"play-001",
				"session-001",
				"station-night",
				SegmentType.LETTER,
				"レター",
				PlayHistoryResultStatus.DONE,
				Instant.parse("2026-03-20T09:15:00Z"));
		LetterPublicDtos.LetterPublicSummary summary = new LetterPublicDtos.LetterPublicSummary(
				"letter-001",
				"station-night",
				"夜更かしペンギン",
				"最近の作業BGM",
				LetterStatus.ADOPTED,
				"playout-001",
				Instant.parse("2026-03-20T09:00:00Z"),
				List.of(playHistory));
		when(letterPublicService.lookup(any())).thenReturn(new LetterPublicDtos.LetterPublicLookupResponse(List.of(summary)));
		mockMvc = MockMvcBuilders.standaloneSetup(new LetterPublicController(letterPublicService))
				.setControllerAdvice(new ApiErrorHandler())
				.build();
	}

	@Test
	void historyEndpointDoesNotRequireAdminToken() throws Exception {
		mockMvc.perform(post("/api/letters/public/history")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "letterIds": ["letter-001", "missing"]
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.letters[0].id").value("letter-001"))
				.andExpect(jsonPath("$.letters[0].subject").value("最近の作業BGM"))
				.andExpect(jsonPath("$.letters[0].radioName").value("夜更かしペンギン"))
				.andExpect(jsonPath("$.letters[0].playHistory[0].id").value("play-001"));
	}
}
