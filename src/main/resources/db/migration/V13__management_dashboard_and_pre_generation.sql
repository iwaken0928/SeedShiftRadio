ALTER TABLE playout_session
    ADD COLUMN purpose VARCHAR(40) NOT NULL DEFAULT 'LIVE';

CREATE INDEX idx_playout_session_purpose_started
    ON playout_session (purpose, started_at DESC);

CREATE TABLE pre_generation_request (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    program_template_id VARCHAR(100),
    target_program_count INTEGER NOT NULL,
    include_speech BOOLEAN NOT NULL,
    include_music BOOLEAN NOT NULL,
    status VARCHAR(40) NOT NULL,
    materialized_program_count INTEGER NOT NULL DEFAULT 0,
    materialized_segment_count INTEGER NOT NULL DEFAULT 0,
    queued_music_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_pre_generation_station FOREIGN KEY (station_id) REFERENCES station (id),
    CONSTRAINT fk_pre_generation_session FOREIGN KEY (session_id) REFERENCES playout_session (id),
    CONSTRAINT fk_pre_generation_template FOREIGN KEY (program_template_id) REFERENCES program_template (id),
    CONSTRAINT uq_pre_generation_session UNIQUE (session_id),
    CONSTRAINT chk_pre_generation_target_program_count
        CHECK (target_program_count BETWEEN 1 AND 10),
    CONSTRAINT chk_pre_generation_target_kind
        CHECK (include_speech OR include_music)
);

CREATE INDEX idx_pre_generation_station_requested
    ON pre_generation_request (station_id, requested_at DESC);

CREATE INDEX idx_pre_generation_status_updated
    ON pre_generation_request (status, updated_at DESC);
