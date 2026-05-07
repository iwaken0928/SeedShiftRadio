CREATE TABLE broadcast_archive (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100) NOT NULL,
    source_play_history_id VARCHAR(100) NOT NULL UNIQUE,
    segment_type VARCHAR(40) NOT NULL,
    title VARCHAR(255) NOT NULL,
    primary_asset_id VARCHAR(100) NOT NULL,
    script_asset_id VARCHAR(100),
    archive_status VARCHAR(40) NOT NULL,
    replay_weight INTEGER NOT NULL DEFAULT 1,
    replay_count INTEGER NOT NULL DEFAULT 0,
    eligible_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_replayed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_broadcast_archive_station FOREIGN KEY (station_id) REFERENCES station (id),
    CONSTRAINT fk_broadcast_archive_play_history FOREIGN KEY (source_play_history_id) REFERENCES play_history (id),
    CONSTRAINT fk_broadcast_archive_primary_asset FOREIGN KEY (primary_asset_id) REFERENCES generated_asset (id),
    CONSTRAINT fk_broadcast_archive_script_asset FOREIGN KEY (script_asset_id) REFERENCES generated_asset (id)
);

CREATE INDEX idx_broadcast_archive_replay_lookup
    ON broadcast_archive (station_id, segment_type, archive_status, eligible_from);

CREATE INDEX idx_broadcast_archive_expires_at
    ON broadcast_archive (expires_at);
