package com.seedshiftradio.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.seedshiftradio.common.api.ApiException;
import com.seedshiftradio.domain.PreGenerationRequestStatus;
import com.seedshiftradio.management.ManagementDtos.ManagementDashboardResponse;
import com.seedshiftradio.management.ManagementDtos.PreGenerationRequest;
import com.seedshiftradio.management.ManagementDtos.PreGenerationResponse;
import com.seedshiftradio.monitor.MonitorService;
import com.seedshiftradio.monitor.OperationalEventService;
import com.seedshiftradio.programming.ProgramTemplateRepository;
import com.seedshiftradio.programming.ProgrammingService;
import com.seedshiftradio.radio.PlayoutSessionEntity;
import com.seedshiftradio.radio.PlayoutSessionRepository;
import com.seedshiftradio.radio.ProgramBlockRepository;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.settings.GeneratedAssetRepository;
import com.seedshiftradio.station.StationEntity;
import com.seedshiftradio.station.StationRepository;

@ExtendWith(MockitoExtension.class)
class ManagementServiceTests {

	@Mock MonitorService monitorService;
	@Mock StationRepository stationRepository;
	@Mock ProgramTemplateRepository programTemplateRepository;
	@Mock ProgramBlockRepository programBlockRepository;
	@Mock GeneratedAssetRepository generatedAssetRepository;
	@Mock PreGenerationRequestRepository preGenerationRequestRepository;
	@Mock PlayoutSessionRepository playoutSessionRepository;
	@Mock ProgrammingService programmingService;
	@Mock RadioService radioService;
	@Mock ApplicationEventPublisher eventPublisher;
	@Mock OperationalEventService operationalEventService;

	ManagementService service;

	@BeforeEach
	void setUp() {
		service = new ManagementService(
				monitorService,
				stationRepository,
				programTemplateRepository,
				programBlockRepository,
				generatedAssetRepository,
				preGenerationRequestRepository,
				playoutSessionRepository,
				programmingService,
				radioService,
				eventPublisher,
				operationalEventService);
	}

	@Test
	void dashboardAggregatesProgramAndAssetInventoryByStation() {
		StationEntity station = org.mockito.Mockito.mock(StationEntity.class);
		when(station.getId()).thenReturn("station-night");
		when(station.getName()).thenReturn("Midnight Echo");
		when(station.isActive()).thenReturn(true);
		when(station.isProgrammingEnabled()).thenReturn(true);
		when(stationRepository.findAll()).thenReturn(List.of(station));
		when(programTemplateRepository.count()).thenReturn(3L);
		when(programTemplateRepository.countActiveApplicableToStation("station-night")).thenReturn(2L);
		when(programBlockRepository.countByStationId("station-night")).thenReturn(5L);
		when(programBlockRepository.countPreGeneratedByStationId("station-night")).thenReturn(3L);
		when(programBlockRepository.findTopByStationIdOrderByStartedAtDesc("station-night")).thenReturn(Optional.empty());
		GeneratedAssetRepository.StationAssetStats music = assetStats("MUSIC", 4L, 16_384L);
		GeneratedAssetRepository.StationAssetStats audio = assetStats("AUDIO", 7L, 8_192L);
		when(generatedAssetRepository.summarizeByStationId("station-night")).thenReturn(List.of(music, audio));
		when(preGenerationRequestRepository.findTop10ByOrderByRequestedAtDesc()).thenReturn(List.of());
		PreGenerationRequestEntity latest = preGenerationRequest("pregen-station-night", "station-night");
		when(preGenerationRequestRepository.findFirstByStationIdOrderByRequestedAtDesc("station-night"))
				.thenReturn(Optional.of(latest));

		ManagementDashboardResponse dashboard = service.dashboard();

		assertEquals(1L, dashboard.stationCount());
		assertEquals(1L, dashboard.activeStationCount());
		assertEquals(5L, dashboard.stations().getFirst().programCount());
		assertEquals(3L, dashboard.stations().getFirst().preGeneratedProgramCount());
		assertEquals(11L, dashboard.stations().getFirst().generatedAssetCount());
		assertEquals(24_576L, dashboard.stations().getFirst().generatedAssetBytes());
		assertEquals(4L, dashboard.stations().getFirst().musicAssetCount());
		assertEquals("pregen-station-night", dashboard.stations().getFirst().latestPreGeneration().id());
	}

	@Test
	void requestCreatesOffAirSessionAndPublishesJobAfterValidation() {
		StationEntity station = org.mockito.Mockito.mock(StationEntity.class);
		when(station.isActive()).thenReturn(true);
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station));
		when(playoutSessionRepository.save(any(PlayoutSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(preGenerationRequestRepository.save(any(PreGenerationRequestEntity.class))).thenAnswer(invocation -> {
			PreGenerationRequestEntity entity = invocation.getArgument(0);
			entity.onCreate();
			return entity;
		});

		PreGenerationResponse response = service.requestPreGeneration(
				"station-night",
				new PreGenerationRequest("tmpl-night", 2, true, true));

		ArgumentCaptor<PlayoutSessionEntity> sessionCaptor = ArgumentCaptor.forClass(PlayoutSessionEntity.class);
		verify(playoutSessionRepository).save(sessionCaptor.capture());
		assertTrue(sessionCaptor.getValue().isPreGeneration());
		assertEquals("station-night", sessionCaptor.getValue().getStationId());
		assertEquals("ADMIN_PRE_GENERATION", sessionCaptor.getValue().getRequestedBy());
		assertEquals(2, response.targetProgramCount());
		assertEquals("tmpl-night", response.programTemplateId());
		verify(programmingService).resolvePreGenerationPlan(
				org.mockito.ArgumentMatchers.eq("station-night"),
				org.mockito.ArgumentMatchers.eq("tmpl-night"),
				any());
		verify(eventPublisher).publishEvent(any(PreGenerationRequested.class));
	}

	@Test
	void requestRejectsWhenNoGenerationTargetIsSelected() {
		StationEntity station = org.mockito.Mockito.mock(StationEntity.class);
		when(station.isActive()).thenReturn(true);
		when(stationRepository.findById("station-night")).thenReturn(Optional.of(station));

		ApiException error = assertThrows(
				ApiException.class,
				() -> service.requestPreGeneration(
						"station-night",
						new PreGenerationRequest(null, 1, false, false)));

		assertEquals("VALIDATION_ERROR", error.getCode());
	}

	private GeneratedAssetRepository.StationAssetStats assetStats(String type, long count, long bytes) {
		GeneratedAssetRepository.StationAssetStats stats = org.mockito.Mockito.mock(GeneratedAssetRepository.StationAssetStats.class);
		when(stats.getAssetType()).thenReturn(type);
		when(stats.getAssetCount()).thenReturn(count);
		when(stats.getByteSize()).thenReturn(bytes);
		when(stats.getLatestCreatedAt()).thenReturn(Instant.parse("2026-07-26T01:00:00Z"));
		return stats;
	}

	private PreGenerationRequestEntity preGenerationRequest(String id, String stationId) {
		PreGenerationRequestEntity entity = new PreGenerationRequestEntity();
		entity.setId(id);
		entity.setStationId(stationId);
		entity.setSessionId("session-" + id);
		entity.setTargetProgramCount(1);
		entity.setIncludeSpeech(true);
		entity.setIncludeMusic(true);
		entity.setStatus(PreGenerationRequestStatus.MATERIALIZED);
		entity.onCreate();
		return entity;
	}
}
