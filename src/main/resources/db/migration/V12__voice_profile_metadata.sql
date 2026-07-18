ALTER TABLE voice_profile
    ADD COLUMN scope VARCHAR(40) NOT NULL DEFAULT 'GLOBAL',
    ADD COLUMN station_id VARCHAR(100),
    ADD COLUMN provider_key VARCHAR(100),
    ADD COLUMN provider_options JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN reference_voice_ref VARCHAR(255),
    ADD COLUMN consent_policy_ref VARCHAR(255);

UPDATE voice_profile
SET provider_key = 'voicevox'
WHERE id = 'voice-night-main';

UPDATE voice_profile AS profile
SET scope = 'STATION',
    station_id = 'station-night'
WHERE profile.id = 'voice-night-main'
  AND EXISTS (
      SELECT 1
      FROM station AS owner_station
      WHERE owner_station.id = 'station-night'
        AND owner_station.default_voice_profile_id = profile.id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM station AS shared_station
      WHERE shared_station.id <> 'station-night'
        AND shared_station.default_voice_profile_id = profile.id
  );

ALTER TABLE voice_profile
    ADD CONSTRAINT fk_voice_profile_station
        FOREIGN KEY (station_id) REFERENCES station (id),
    ADD CONSTRAINT ck_voice_profile_scope
        CHECK (
            (scope = 'GLOBAL' AND station_id IS NULL)
            OR (scope = 'STATION' AND station_id IS NOT NULL)
        ),
    ADD CONSTRAINT ck_voice_profile_provider_options_object
        CHECK (jsonb_typeof(provider_options) = 'object'),
    ADD CONSTRAINT ck_voice_profile_reference_consent
        CHECK (
            reference_voice_ref IS NULL
            OR (
                btrim(reference_voice_ref) <> ''
                AND consent_policy_ref IS NOT NULL
                AND btrim(consent_policy_ref) <> ''
            )
        ),
    ADD CONSTRAINT ck_voice_profile_reference_safe
        CHECK (
            reference_voice_ref IS NULL
            OR (
                reference_voice_ref = btrim(reference_voice_ref)
                AND
                reference_voice_ref !~ '^[\\/]'
                AND reference_voice_ref !~ '^[A-Za-z]:[\\/]'
                AND reference_voice_ref !~ '^[A-Za-z][A-Za-z0-9+.-]*:'
                AND reference_voice_ref !~ '(^|[\\/])\.\.([\\/]|$)'
            )
        );
