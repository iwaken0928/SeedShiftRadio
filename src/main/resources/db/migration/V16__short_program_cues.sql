UPDATE program_template_slot
SET target_duration_ms = 15000
WHERE role IN ('OPENING', 'ENDING')
  AND candidate_segment_types ->> 0 IN ('JINGLE', 'MUSIC_AI')
  AND target_duration_ms <> 15000;

UPDATE program_block_slot
SET target_duration_ms = 15000
WHERE role IN ('OPENING', 'ENDING')
  AND resolved_segment_type IN ('JINGLE', 'MUSIC_AI')
  AND status IN ('PLANNED', 'QUEUED')
  AND target_duration_ms <> 15000;

UPDATE queue_item
SET duration_ms = 15000,
    updated_at = now()
WHERE slot_role IN ('OPENING', 'ENDING')
  AND segment_type IN ('JINGLE', 'MUSIC_AI')
  AND status IN ('PLANNED', 'GENERATING', 'READY')
  AND duration_ms <> 15000;
