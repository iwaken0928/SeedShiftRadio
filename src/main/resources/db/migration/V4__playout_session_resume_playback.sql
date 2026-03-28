ALTER TABLE playout_session
    ADD COLUMN requested_by VARCHAR(255),
    ADD COLUMN resume_playback BOOLEAN NOT NULL DEFAULT FALSE;
