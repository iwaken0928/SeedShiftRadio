ALTER TABLE generated_asset
    ADD COLUMN byte_size BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reuse_scope VARCHAR(40) NOT NULL DEFAULT 'STATION',
    ADD COLUMN reuse_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN archive_eligible BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE generated_asset
SET
    byte_size = COALESCE(byte_size, 0),
    reuse_scope = COALESCE(reuse_scope, 'STATION'),
    reuse_count = COALESCE(reuse_count, 0),
    last_accessed_at = COALESCE(last_accessed_at, created_at, now()),
    archive_eligible = COALESCE(archive_eligible, FALSE);

ALTER TABLE generated_asset
    ALTER COLUMN byte_size DROP DEFAULT,
    ALTER COLUMN reuse_scope DROP DEFAULT,
    ALTER COLUMN reuse_count DROP DEFAULT,
    ALTER COLUMN last_accessed_at DROP DEFAULT,
    ALTER COLUMN archive_eligible DROP DEFAULT;

CREATE INDEX idx_generated_asset_type_expires_at
    ON generated_asset (asset_type, expires_at);
