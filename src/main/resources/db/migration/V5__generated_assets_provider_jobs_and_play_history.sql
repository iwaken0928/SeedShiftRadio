CREATE TABLE generated_asset (
    id VARCHAR(100) PRIMARY KEY,
    asset_type VARCHAR(40) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    provider_fingerprint VARCHAR(255) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    queue_item_id VARCHAR(100),
    provider_job_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_generated_asset_queue_item FOREIGN KEY (queue_item_id) REFERENCES queue_item (id)
);

CREATE TABLE provider_job (
    id VARCHAR(100) PRIMARY KEY,
    job_type VARCHAR(40) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    provider_key VARCHAR(100),
    queue_item_id VARCHAR(100),
    status VARCHAR(40) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    external_ref VARCHAR(255),
    error_code VARCHAR(100),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_provider_job_queue_item FOREIGN KEY (queue_item_id) REFERENCES queue_item (id)
);

CREATE TABLE play_history (
    id VARCHAR(100) PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    station_id VARCHAR(100) NOT NULL,
    queue_item_id VARCHAR(100) NOT NULL,
    program_block_id VARCHAR(100),
    program_slot_id VARCHAR(100),
    segment_type VARCHAR(40) NOT NULL,
    title VARCHAR(255) NOT NULL,
    playback_mode VARCHAR(40) NOT NULL,
    result_status VARCHAR(40) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    played_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_play_history_session FOREIGN KEY (session_id) REFERENCES playout_session (id),
    CONSTRAINT fk_play_history_station FOREIGN KEY (station_id) REFERENCES station (id),
    CONSTRAINT fk_play_history_queue_item FOREIGN KEY (queue_item_id) REFERENCES queue_item (id)
);

ALTER TABLE queue_item
    ADD COLUMN asset_id VARCHAR(100),
    ADD CONSTRAINT fk_queue_item_asset FOREIGN KEY (asset_id) REFERENCES generated_asset (id);

ALTER TABLE generated_asset
    ADD CONSTRAINT fk_generated_asset_provider_job FOREIGN KEY (provider_job_id) REFERENCES provider_job (id);
