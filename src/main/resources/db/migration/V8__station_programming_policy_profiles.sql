ALTER TABLE station_programming_policy
    ADD COLUMN pre_generation_policy JSONB NOT NULL DEFAULT '{
      "mode": "ASSISTED",
      "maxPreparedMinutes": 12,
      "maxPreparedBlocks": 2,
      "preferCacheReuse": true
    }'::jsonb,
    ADD COLUMN replay_policy JSONB NOT NULL DEFAULT '{
      "intensity": "LIGHT",
      "eligibleSegmentTypes": ["MUSIC_AI", "MUSIC_LOCAL", "JINGLE"],
      "minimumAssetAgeHours": 6,
      "cooldownHours": 72,
      "maxReplaySharePercent": 20,
      "excludeLetterSegments": true
    }'::jsonb,
    ADD COLUMN composition_policy JSONB NOT NULL DEFAULT '{
      "targetSegmentShares": {
        "talk": 40,
        "letter": 20,
        "music": 35,
        "jingle": 5
      },
      "maxConsecutiveTalkSegments": 2,
      "musicBreakIntervalMinutes": 8,
      "letterPriorityBoostThreshold": 4,
      "allowSoftFallbackRetiming": true
    }'::jsonb;
