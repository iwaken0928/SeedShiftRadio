ALTER TABLE generated_asset
    ADD COLUMN cache_key VARCHAR(255);

CREATE INDEX idx_generated_asset_type_cache_key_created_at
    ON generated_asset (asset_type, cache_key, created_at);
