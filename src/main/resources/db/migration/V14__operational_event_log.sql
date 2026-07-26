CREATE TABLE operational_event_log (
    id VARCHAR(100) PRIMARY KEY,
    level VARCHAR(20) NOT NULL,
    category VARCHAR(40) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    source_id VARCHAR(100),
    correlation_id VARCHAR(100),
    provider_type VARCHAR(40),
    provider_key VARCHAR(100),
    error_code VARCHAR(100),
    message VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_operational_event_occurred
    ON operational_event_log (occurred_at DESC);

CREATE INDEX idx_operational_event_category_occurred
    ON operational_event_log (category, occurred_at DESC);

CREATE INDEX idx_operational_event_level_occurred
    ON operational_event_log (level, occurred_at DESC);
