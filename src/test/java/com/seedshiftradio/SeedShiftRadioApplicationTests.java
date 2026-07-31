package com.seedshiftradio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.seedshiftradio.domain.ProviderJobStatus;
import com.seedshiftradio.domain.ProviderJobType;
import com.seedshiftradio.domain.ProviderType;
import com.seedshiftradio.programming.ProgramTemplateRepository;
import com.seedshiftradio.radio.ProgramBlockRepository;
import com.seedshiftradio.settings.GeneratedAssetRepository;
import com.seedshiftradio.settings.ProviderJobEntity;
import com.seedshiftradio.settings.ProviderJobRepository;
import com.seedshiftradio.settings.ProviderJobService;

@Testcontainers(disabledWithoutDocker = true)
@Tag("docker")
@SpringBootTest
class SeedShiftRadioApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("seedshift_radio")
			.withUsername("seedshift")
			.withPassword("seedshift");

	@Autowired
	ProviderJobService providerJobService;

	@Autowired
	ProviderJobRepository providerJobRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	ProgramTemplateRepository programTemplateRepository;

	@Autowired
	ProgramBlockRepository programBlockRepository;

	@Autowired
	GeneratedAssetRepository generatedAssetRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void defaultProgramPlacesMusicGenAfterTheOpeningTalk() {
		var slot = jdbcTemplate.queryForMap("""
				select sequence_no, role, constraint_mode, target_duration_ms,
				       candidate_segment_types::text as candidate_segment_types,
				       fallback_segment_types::text as fallback_segment_types,
				       slot_policy::text as slot_policy
				from program_template_slot
				where id = 'slot-night-regular-topic'
				""");

		assertEquals(2, slot.get("sequence_no"));
		assertEquals("MUSIC_BREAK", slot.get("role"));
		assertEquals("HARD", slot.get("constraint_mode"));
		assertEquals(120000, slot.get("target_duration_ms"));
		assertEquals("[\"MUSIC_AI\"]", slot.get("candidate_segment_types"));
		assertEquals("[\"MUSIC_LOCAL\", \"JINGLE\"]", slot.get("fallback_segment_types"));
		org.junit.jupiter.api.Assertions.assertTrue(slot.get("slot_policy").toString().contains("\"outroLeadSeconds\": 20"));
		org.junit.jupiter.api.Assertions.assertTrue(slot.get("slot_policy").toString().contains("\"fadeOutSeconds\": 6"));
		assertEquals(
				5,
				jdbcTemplate.queryForObject(
						"select version from program_template where id = 'tmpl-night-regular'",
						Integer.class));
	}

	@Test
	@Transactional
	void staleProviderJobRecoveryUsesTheDatabaseConditionAndIsIdempotent() {
		Instant now = Instant.parse("2026-07-23T00:30:00Z");
		Instant cutoff = now.minusSeconds(15 * 60);
		ProviderJobEntity stale = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN, ProviderType.MUSIC, "worker-primary", null, "corr-stale");
		providerJobService.markRunning(stale.getId(), "worker-stale");
		ProviderJobEntity fresh = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN, ProviderType.MUSIC, "worker-primary", null, "corr-fresh");
		providerJobService.markRunning(fresh.getId(), "worker-fresh");
		ProviderJobEntity queued = providerJobService.createQueuedJob(
				ProviderJobType.MUSIC_GEN, ProviderType.MUSIC, "worker-primary", null, "corr-queued");

		providerJobRepository.flush();
		setUpdatedAt(stale.getId(), now.minusSeconds(30 * 60));
		setUpdatedAt(fresh.getId(), now.minusSeconds(5 * 60));
		setUpdatedAt(queued.getId(), now.minusSeconds(30 * 60));

		List<String> candidates = providerJobService.findStaleRunning(cutoff, 100).stream()
				.map(ProviderJobEntity::getId)
				.toList();

		assertEquals(List.of(stale.getId()), candidates);
		assertTrue(providerJobService.failIfStaleRunning(stale.getId(), cutoff, now));
		assertFalse(providerJobService.failIfStaleRunning(stale.getId(), cutoff, now));
		assertFalse(providerJobService.failIfStaleRunning(fresh.getId(), cutoff, now));
		assertFalse(providerJobService.failIfStaleRunning(queued.getId(), cutoff, now));

		ProviderJobEntity recovered = providerJobRepository.findById(stale.getId()).orElseThrow();
		assertEquals(ProviderJobStatus.FAILED, recovered.getStatus());
		assertEquals("PROVIDER_INTERRUPTED", recovered.getErrorCode());
		assertEquals(now, recovered.getEndedAt());
		assertEquals(ProviderJobStatus.RUNNING, providerJobRepository.findById(fresh.getId()).orElseThrow().getStatus());
		assertEquals(ProviderJobStatus.QUEUED, providerJobRepository.findById(queued.getId()).orElseThrow().getStatus());
	}

	@Test
	@Transactional
	void stationContentInventoryCountsStoredPayloadsAndDeletionIncludesTombstones() {
		jdbcTemplate.update("""
				insert into station (
					id, name, frequency_mhz, genre, language_persona_id,
					default_voice_profile_id, programming_enabled, is_active
				) values (?, ?, ?, ?, ?, ?, true, true)
				""",
				"station-inventory-test",
				"Inventory Test",
				91.1,
				"TEST",
				"persona-inventory-test",
				"voice-inventory-test");
		long templateCountBefore = programTemplateRepository.countActiveApplicableToStation("station-inventory-test");
		jdbcTemplate.update("""
				insert into program_template (
					id, scope, station_id, name, target_duration_minutes,
					planning_horizon_minutes, is_active
				) values (?, 'STATION', ?, ?, 10, 30, true)
				""",
				"template-inventory-test",
				"station-inventory-test",
				"Inventory Template");
		assertEquals(
				templateCountBefore + 1,
				programTemplateRepository.countActiveApplicableToStation("station-inventory-test"));

		jdbcTemplate.update("""
				insert into playout_session (
					id, station_id, state, correlation_id, purpose
				) values (?, ?, 'STOPPED', ?, 'PRE_GENERATION')
				""",
				"session-inventory-test",
				"station-inventory-test",
				"corr-inventory-test");
		jdbcTemplate.update("""
				insert into program_block (
					id, station_id, session_id, title, status, planned_duration_ms
				) values (?, ?, ?, ?, 'PLANNED', 60000)
				""",
				"block-inventory-test",
				"station-inventory-test",
				"session-inventory-test",
				"Inventory Program");
		jdbcTemplate.update("""
				insert into queue_item (
					id, session_id, sequence_no, segment_type, status,
					program_block_id, slot_role, title, playback_mode,
					duration_ms, correlation_id
				) values (?, ?, 1, 'MUSIC_AI', 'READY', ?, 'MUSIC_BREAK', ?, 'SERVER_AUDIO', 60000, ?)
				""",
				"queue-inventory-test",
				"session-inventory-test",
				"block-inventory-test",
				"Inventory Music",
				"corr-inventory-test");
		insertGeneratedAsset("asset-inventory-music", "MUSIC", 4096);
		insertGeneratedAsset("asset-inventory-audio", "AUDIO", 2048);
		insertGeneratedAsset("asset-inventory-evicted", "MUSIC", 0);
		jdbcTemplate.update(
				"update generated_asset set archive_eligible = true where id = ?",
				"asset-inventory-audio");

		assertEquals(1L, programBlockRepository.countPreGeneratedByStationId("station-inventory-test"));
		List<GeneratedAssetRepository.StationAssetStats> stats =
				generatedAssetRepository.summarizeByStationId("station-inventory-test");
		assertEquals(2, stats.size());
		assertEquals(1L, stats.stream()
				.filter(stat -> "MUSIC".equals(stat.getAssetType()))
				.findFirst()
				.orElseThrow()
				.getAssetCount());
		assertEquals(4096L, stats.stream()
				.filter(stat -> "MUSIC".equals(stat.getAssetType()))
				.findFirst()
				.orElseThrow()
				.getByteSize());
		var deletable = generatedAssetRepository.findDeletablePreGeneratedAssetsByStationId(
				"station-inventory-test",
				List.of("MUSIC", "AUDIO"));
		assertEquals(
				Set.of("asset-inventory-music", "asset-inventory-evicted"),
				Set.copyOf(deletable.stream().map(asset -> asset.getId()).toList()));
	}

	private void setUpdatedAt(String providerJobId, Instant updatedAt) {
		assertEquals(1, jdbcTemplate.update(
				"update provider_job set updated_at = ? where id = ?",
				Timestamp.from(updatedAt),
				providerJobId));
	}

	private void insertGeneratedAsset(String id, String assetType, long byteSize) {
		jdbcTemplate.update("""
				insert into generated_asset (
					id, asset_type, storage_path, content_hash, provider_fingerprint,
					metadata, queue_item_id, byte_size, reuse_scope, reuse_count,
					last_accessed_at, archive_eligible
				) values (?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, 'STATION', 0, now(), false)
				""",
				id,
				assetType,
				"assets/test/" + id,
				"hash-" + id,
				"test-provider",
				"queue-inventory-test",
				byteSize);
	}

}
