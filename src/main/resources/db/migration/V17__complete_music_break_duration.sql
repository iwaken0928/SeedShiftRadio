UPDATE program_template
SET version = 5
WHERE id = 'tmpl-night-regular';

UPDATE program_template_slot
SET target_duration_ms = 120000,
    slot_policy = slot_policy
        || '{"songForm":"VERSE_CHORUS_VERSE_CHORUS_BRIDGE_OUTRO","outroLeadSeconds":20,"fadeOutSeconds":6}'::jsonb
WHERE id = 'slot-night-regular-topic'
  AND program_template_id = 'tmpl-night-regular';
