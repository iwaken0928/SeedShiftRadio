CREATE TABLE client_capabilities (
    client_id VARCHAR(100) PRIMARY KEY,
    client_type VARCHAR(100) NOT NULL,
    supports_client_side_tts BOOLEAN NOT NULL DEFAULT FALSE,
    supported_voice_engines JSONB NOT NULL DEFAULT '[]'::jsonb,
    preferred_playback_mode VARCHAR(40) NOT NULL,
    local_voice_profiles JSONB NOT NULL DEFAULT '[]'::jsonb,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
