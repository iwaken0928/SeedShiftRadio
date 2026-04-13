ALTER TABLE queue_item
    ADD COLUMN content_origin VARCHAR(40) NOT NULL DEFAULT 'LIVE_GEN',
    ADD COLUMN replay_of_play_history_id VARCHAR(100),
    ADD CONSTRAINT fk_queue_item_replay_history FOREIGN KEY (replay_of_play_history_id) REFERENCES play_history (id);

ALTER TABLE play_history
    ADD COLUMN content_origin VARCHAR(40) NOT NULL DEFAULT 'LIVE_GEN',
    ADD COLUMN replay_of_play_history_id VARCHAR(100),
    ADD CONSTRAINT fk_play_history_replay_history FOREIGN KEY (replay_of_play_history_id) REFERENCES play_history (id);

CREATE INDEX idx_queue_item_content_origin ON queue_item (content_origin);
CREATE INDEX idx_play_history_content_origin_played_at ON play_history (content_origin, played_at);
