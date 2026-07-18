package com.seedshiftradio.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.seedshiftradio.domain.PlaybackMode;
import com.seedshiftradio.support.TestSettingsFixture;

@Testcontainers(disabledWithoutDocker = true)
@Tag("docker")
@SpringBootTest(properties = {
		"seedshift.radio.config.path=./build/test-settings/voice-profile-persistence-config.json",
		"jobrunr.background-job-server.enabled=false",
		"jobrunr.dashboard.enabled=false"
})
class VoiceProfilePersistenceTests {

	private static final String TEST_CONFIG_PATH = "./build/test-settings/voice-profile-persistence-config.json";

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
	VoiceProfileRepository voiceProfileRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void migratedNightVoiceProfileHasStationScopedMetadata() {
		VoiceProfileEntity profile = voiceProfileRepository.findById("voice-night-main").orElseThrow();

		assertEquals("STATION", profile.getScope());
		assertEquals("station-night", profile.getStationId());
		assertEquals("voicevox", profile.getProviderKey());
		assertNotNull(profile.getProviderOptions());
		assertTrue(profile.getProviderOptions().isEmpty());
	}

	@Test
	void legacyVoiceProfileSharedByMultipleStationsRemainsGlobal() {
		String schema = "voice_profile_shared_migration";
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				postgres.getJdbcUrl(),
				postgres.getUsername(),
				postgres.getPassword());
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("11"))
				.load()
				.migrate();

		JdbcTemplate isolatedDatabase = new JdbcTemplate(dataSource);
		isolatedDatabase.update(
				"""
				INSERT INTO voice_profile_shared_migration.station (
				    id, name, frequency_mhz, genre, language_persona_id,
				    default_voice_profile_id, programming_enabled, is_active
				) VALUES ('station-shared', '共有局', 90.1, 'talk', 'persona-shared',
				          'voice-night-main', FALSE, TRUE)
				""");

		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.load()
				.migrate();

		Map<String, Object> migrated = isolatedDatabase.queryForMap(
				"""
				SELECT scope, station_id, provider_key
				FROM voice_profile_shared_migration.voice_profile
				WHERE id = 'voice-night-main'
				""");
		assertEquals("GLOBAL", migrated.get("scope"));
		assertNull(migrated.get("station_id"));
		assertEquals("voicevox", migrated.get("provider_key"));
	}

	@Test
	void globalVoiceProfileMetadataRoundTrips() {
		Map<String, Object> providerOptions = new LinkedHashMap<>();
		providerOptions.put("responseFormat", "wav");
		providerOptions.put("irodori", Map.of("numSteps", 12, "chunking", false));

		VoiceProfileEntity profile = new VoiceProfileEntity();
		profile.setId("voice-global-irodori");
		profile.setEngineType("IRODORI_TTS");
		profile.setScope("GLOBAL");
		profile.setStationId(null);
		profile.setProviderKey("irodori");
		profile.setSpeakerKey("voice-global-main");
		profile.setStyleKey("calm");
		profile.setProviderOptions(providerOptions);
		profile.setReferenceVoiceRef("voices/voice-global-main.wav");
		profile.setConsentPolicyRef("consent-global-main");
		profile.setSpeed(new BigDecimal("0.95"));
		profile.setPitch(new BigDecimal("1.00"));
		profile.setPlaybackMode(PlaybackMode.SERVER_AUDIO);

		voiceProfileRepository.saveAndFlush(profile);

		VoiceProfileEntity reloaded = voiceProfileRepository.findById(profile.getId()).orElseThrow();
		assertEquals("GLOBAL", reloaded.getScope());
		assertNull(reloaded.getStationId());
		assertEquals("irodori", reloaded.getProviderKey());
		assertEquals(providerOptions, reloaded.getProviderOptions());
		assertEquals("voices/voice-global-main.wav", reloaded.getReferenceVoiceRef());
		assertEquals("consent-global-main", reloaded.getConsentPolicyRef());
	}

	@Test
	void databaseRejectsUnsafeOrUnconsentedReferenceVoice() {
		assertReferenceRejected("voice-unsafe-absolute", "/srv/voices/person.wav", "consent-absolute");
		assertReferenceRejected("voice-unsafe-uri", "file:/srv/voices/person.wav", "consent-uri");
		assertReferenceRejected("voice-unsafe-traversal", "voices/../private/person.wav", "consent-traversal");
		assertReferenceRejected("voice-without-consent", "voices/person.wav", null);
		assertFalse(voiceProfileRepository.existsById("voice-unsafe-absolute"));
		assertFalse(voiceProfileRepository.existsById("voice-unsafe-uri"));
		assertFalse(voiceProfileRepository.existsById("voice-unsafe-traversal"));
		assertFalse(voiceProfileRepository.existsById("voice-without-consent"));
	}

	private void assertReferenceRejected(String id, String referenceVoiceRef, String consentPolicyRef) {
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
				"""
				INSERT INTO voice_profile (
				    id, engine_type, scope, station_id, provider_key, speaker_key, style_key,
				    speed, pitch, playback_mode, provider_options, reference_voice_ref, consent_policy_ref
				) VALUES (?, 'IRODORI_TTS', 'GLOBAL', NULL, 'irodori', 'voice-test', 'calm',
				          1.00, 1.00, 'SERVER_AUDIO', '{}'::jsonb, ?, ?)
				""",
				id,
				referenceVoiceRef,
				consentPolicyRef));
	}
}
