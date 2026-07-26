UPDATE program_template
SET version = 4,
    editorial_policy = jsonb_set(
        jsonb_set(editorial_policy, '{format}', '"talk-then-music"'::jsonb, true),
        '{musicPolicy}',
        '"musicgen-first"'::jsonb,
        true
    )
WHERE id = 'tmpl-night-regular';

UPDATE program_template_slot
SET role = 'MUSIC_BREAK',
    constraint_mode = 'HARD',
    candidate_segment_types = '["MUSIC_AI"]'::jsonb,
    fallback_segment_types = '["MUSIC_LOCAL","JINGLE"]'::jsonb,
    slot_policy = '{"topicHints":["夜のドライブ","city pop","落ち着いたラジオ音楽"],"musicgenRequired":true}'::jsonb
WHERE id = 'slot-night-regular-topic'
  AND program_template_id = 'tmpl-night-regular';
