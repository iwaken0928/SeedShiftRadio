CREATE TABLE station (
    id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    frequency_mhz NUMERIC(4, 1) NOT NULL,
    genre VARCHAR(100) NOT NULL,
    language_persona_id VARCHAR(100) NOT NULL,
    default_voice_profile_id VARCHAR(100) NOT NULL,
    programming_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_program_template_id VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE personality (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100),
    display_name VARCHAR(255) NOT NULL,
    language_tone VARCHAR(100) NOT NULL,
    first_person VARCHAR(100) NOT NULL,
    sentence_style VARCHAR(100) NOT NULL,
    ng_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    pronunciation_dictionary_ref VARCHAR(255),
    CONSTRAINT fk_personality_station FOREIGN KEY (station_id) REFERENCES station (id)
);

CREATE TABLE voice_profile (
    id VARCHAR(100) PRIMARY KEY,
    engine_type VARCHAR(100) NOT NULL,
    speaker_key VARCHAR(100) NOT NULL,
    style_key VARCHAR(100),
    speed NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    pitch NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    playback_mode VARCHAR(40) NOT NULL
);

CREATE TABLE program_template (
    id VARCHAR(100) PRIMARY KEY,
    scope VARCHAR(40) NOT NULL,
    station_id VARCHAR(100),
    name VARCHAR(255) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    target_duration_minutes INTEGER NOT NULL,
    planning_horizon_minutes INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    editorial_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    fallback_template_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_program_template_station FOREIGN KEY (station_id) REFERENCES station (id)
);

CREATE TABLE program_template_slot (
    id VARCHAR(100) PRIMARY KEY,
    program_template_id VARCHAR(100) NOT NULL,
    sequence_no INTEGER NOT NULL,
    role VARCHAR(40) NOT NULL,
    constraint_mode VARCHAR(40) NOT NULL,
    candidate_segment_types JSONB NOT NULL,
    fallback_segment_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_duration_ms INTEGER NOT NULL,
    slot_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_template_slot_template FOREIGN KEY (program_template_id) REFERENCES program_template (id)
);

CREATE TABLE station_programming_policy (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100) NOT NULL UNIQUE,
    version INTEGER NOT NULL DEFAULT 1,
    default_template_id VARCHAR(100),
    fallback_strategy VARCHAR(40) NOT NULL,
    planning_horizon_minutes INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_programming_policy_station FOREIGN KEY (station_id) REFERENCES station (id),
    CONSTRAINT fk_programming_policy_template FOREIGN KEY (default_template_id) REFERENCES program_template (id)
);

CREATE TABLE program_rule (
    id VARCHAR(100) PRIMARY KEY,
    policy_id VARCHAR(100) NOT NULL,
    priority INTEGER NOT NULL,
    days_of_week VARCHAR(100) NOT NULL,
    start_time VARCHAR(20) NOT NULL,
    end_time VARCHAR(20) NOT NULL,
    minimum_pending_letters INTEGER NOT NULL DEFAULT 0,
    required_provider_states JSONB NOT NULL DEFAULT '[]'::jsonb,
    template_id VARCHAR(100) NOT NULL,
    CONSTRAINT fk_program_rule_policy FOREIGN KEY (policy_id) REFERENCES station_programming_policy (id),
    CONSTRAINT fk_program_rule_template FOREIGN KEY (template_id) REFERENCES program_template (id)
);

CREATE TABLE letter (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100),
    radio_name VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    adopted_in_session_id VARCHAR(100),
    idempotency_key VARCHAR(255) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_letter_station FOREIGN KEY (station_id) REFERENCES station (id)
);

CREATE TABLE letter_reply (
    id VARCHAR(100) PRIMARY KEY,
    letter_id VARCHAR(100) NOT NULL,
    reply_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_letter_reply_letter FOREIGN KEY (letter_id) REFERENCES letter (id)
);

CREATE TABLE playout_session (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100) NOT NULL,
    state VARCHAR(40) NOT NULL,
    current_queue_item_id VARCHAR(100),
    current_program_block_id VARCHAR(100),
    buffer_ready_count INTEGER NOT NULL DEFAULT 0,
    degraded_reason VARCHAR(100),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id VARCHAR(100) NOT NULL,
    CONSTRAINT fk_playout_session_station FOREIGN KEY (station_id) REFERENCES station (id)
);

CREATE TABLE program_block (
    id VARCHAR(100) PRIMARY KEY,
    station_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    program_template_id VARCHAR(100),
    program_template_version INTEGER,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL,
    planned_duration_ms INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    CONSTRAINT fk_program_block_station FOREIGN KEY (station_id) REFERENCES station (id),
    CONSTRAINT fk_program_block_session FOREIGN KEY (session_id) REFERENCES playout_session (id),
    CONSTRAINT fk_program_block_template FOREIGN KEY (program_template_id) REFERENCES program_template (id)
);

CREATE TABLE program_block_slot (
    id VARCHAR(100) PRIMARY KEY,
    program_block_id VARCHAR(100) NOT NULL,
    template_slot_id VARCHAR(100),
    sequence_no INTEGER NOT NULL,
    role VARCHAR(40) NOT NULL,
    constraint_mode VARCHAR(40) NOT NULL,
    resolved_segment_type VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    target_duration_ms INTEGER NOT NULL,
    slot_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_program_block_slot_block FOREIGN KEY (program_block_id) REFERENCES program_block (id),
    CONSTRAINT fk_program_block_slot_template_slot FOREIGN KEY (template_slot_id) REFERENCES program_template_slot (id)
);

CREATE TABLE queue_item (
    id VARCHAR(100) PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    sequence_no INTEGER NOT NULL,
    segment_type VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    program_block_id VARCHAR(100),
    program_slot_id VARCHAR(100),
    slot_role VARCHAR(40) NOT NULL,
    title VARCHAR(255) NOT NULL,
    playback_mode VARCHAR(40) NOT NULL,
    asset_url VARCHAR(255),
    speech_directive_id VARCHAR(100),
    duration_ms INTEGER NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    asset_banned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_queue_item_session FOREIGN KEY (session_id) REFERENCES playout_session (id),
    CONSTRAINT fk_queue_item_block FOREIGN KEY (program_block_id) REFERENCES program_block (id),
    CONSTRAINT fk_queue_item_slot FOREIGN KEY (program_slot_id) REFERENCES program_block_slot (id),
    CONSTRAINT uq_queue_item_session_sequence UNIQUE (session_id, sequence_no)
);

INSERT INTO station (
    id,
    name,
    frequency_mhz,
    genre,
    language_persona_id,
    default_voice_profile_id,
    programming_enabled,
    default_program_template_id,
    is_active,
    version
) VALUES (
    'station-night',
    'Midnight Echo',
    81.3,
    'talk',
    'persona-night-main',
    'voice-night-main',
    TRUE,
    'tmpl-night-regular',
    TRUE,
    1
);

INSERT INTO personality (
    id,
    station_id,
    display_name,
    language_tone,
    first_person,
    sentence_style,
    ng_policy,
    pronunciation_dictionary_ref
) VALUES (
    'persona-night-main',
    'station-night',
    'Echo',
    'calm',
    'わたし',
    'desu-masu',
    '{}'::jsonb,
    'dict-night-main'
);

INSERT INTO voice_profile (
    id,
    engine_type,
    speaker_key,
    style_key,
    speed,
    pitch,
    playback_mode
) VALUES (
    'voice-night-main',
    'VOICEVOX',
    '4',
    'normal',
    1.00,
    1.00,
    'SERVER_AUDIO'
);

INSERT INTO program_template (
    id,
    scope,
    station_id,
    name,
    version,
    target_duration_minutes,
    planning_horizon_minutes,
    is_active,
    editorial_policy,
    fallback_template_id
) VALUES
(
    'tmpl-night-regular',
    'STATION',
    'station-night',
    '深夜の作業ノート',
    3,
    20,
    20,
    TRUE,
    '{"mood":"calm","tempo":"steady"}'::jsonb,
    NULL
),
(
    'tmpl-night-letter',
    'STATION',
    'station-night',
    '深夜レター拾い',
    3,
    20,
    15,
    TRUE,
    '{"mood":"warm","tempo":"steady"}'::jsonb,
    'tmpl-night-regular'
);

INSERT INTO program_template_slot (
    id,
    program_template_id,
    sequence_no,
    role,
    constraint_mode,
    candidate_segment_types,
    fallback_segment_types,
    target_duration_ms,
    slot_policy
) VALUES
(
    'slot-night-regular-open',
    'tmpl-night-regular',
    1,
    'OPENING',
    'HARD',
    '["TALK","JINGLE"]'::jsonb,
    '["JINGLE"]'::jsonb,
    30000,
    '{"topicHints":["こんばんは","作業ノート"]}'::jsonb
),
(
    'slot-night-regular-topic',
    'tmpl-night-regular',
    2,
    'TOPIC',
    'SOFT',
    '["TALK","MUSIC_LOCAL"]'::jsonb,
    '["JINGLE"]'::jsonb,
    60000,
    '{"topicHints":["最近の話題","集中"]}'::jsonb
),
(
    'slot-night-regular-end',
    'tmpl-night-regular',
    3,
    'ENDING',
    'HARD',
    '["TALK"]'::jsonb,
    '["JINGLE"]'::jsonb,
    30000,
    '{"requiredPhrases":["また次の番組で"]}'::jsonb
),
(
    'slot-night-letter-open',
    'tmpl-night-letter',
    1,
    'OPENING',
    'HARD',
    '["JINGLE","TALK"]'::jsonb,
    '["TALK"]'::jsonb,
    30000,
    '{"topicHints":["レターの時間"]}'::jsonb
),
(
    'slot-night-letter-main',
    'tmpl-night-letter',
    2,
    'LETTER',
    'HARD',
    '["LETTER","TALK"]'::jsonb,
    '["TALK"]'::jsonb,
    120000,
    '{"topicHints":["最近届いたレター","夜更かし"]}'::jsonb
),
(
    'slot-night-letter-break',
    'tmpl-night-letter',
    3,
    'MUSIC_BREAK',
    'SOFT',
    '["MUSIC_LOCAL","MUSIC_AI"]'::jsonb,
    '["JINGLE","TALK"]'::jsonb,
    180000,
    '{"topicHints":["一息"]}'::jsonb
);

INSERT INTO station_programming_policy (
    id,
    station_id,
    version,
    default_template_id,
    fallback_strategy,
    planning_horizon_minutes
) VALUES (
    'policy-station-night',
    'station-night',
    4,
    'tmpl-night-regular',
    'LEGACY_RATIO',
    20
);

INSERT INTO program_rule (
    id,
    policy_id,
    priority,
    days_of_week,
    start_time,
    end_time,
    minimum_pending_letters,
    required_provider_states,
    template_id
) VALUES
(
    'rule-station-night-weekday',
    'policy-station-night',
    100,
    'MON,TUE,WED,THU,FRI',
    '22:00',
    '02:00',
    0,
    '[]'::jsonb,
    'tmpl-night-regular'
),
(
    'rule-station-night-weekend-letter',
    'policy-station-night',
    120,
    'SAT,SUN',
    '22:00',
    '02:00',
    3,
    '["MUSICGEN_UP"]'::jsonb,
    'tmpl-night-letter'
);
