ALTER TABLE queue_item
    ADD COLUMN letter_id VARCHAR(100),
    ADD CONSTRAINT fk_queue_item_letter FOREIGN KEY (letter_id) REFERENCES letter (id);

ALTER TABLE play_history
    ADD COLUMN letter_id VARCHAR(100),
    ADD CONSTRAINT fk_play_history_letter FOREIGN KEY (letter_id) REFERENCES letter (id);

CREATE INDEX idx_queue_item_session_letter ON queue_item (session_id, letter_id);
CREATE INDEX idx_play_history_letter_played_at ON play_history (letter_id, played_at);
