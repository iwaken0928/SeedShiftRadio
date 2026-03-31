# AIローカルラジオアプリ API仕様書

## 1. 目的

本書は Web Client と将来の C# Native Client が共通利用する API 契約を定義する。MVP は REST + SSE を標準とする。

## 2. 共通方針

- Base URL は `/api` とする
- レスポンスは JSON を基本とする
- 生成済み音声や音楽は署名付きでない内部 URL を返す
- エラー形式は統一する
- OpenAPI は `springdoc-openapi` で自動生成する

## 3. 認証/認可方針

- MVP の一般操作 API は同一 LAN / localhost 利用を前提に無認証を許容する
- 設定更新、局管理、番組編成管理、監視 API は `X-Admin-Token` による最小保護を推奨する
- `GET /api/stations`, `GET /api/stations/{id}`, `POST /api/letters`, `GET /api/radio/*`, `POST /api/radio/tune`, `POST /api/radio/playback-events`, `GET /api/health` は一般操作 API として扱う
- `POST|PUT /api/stations*`, `GET|POST|PUT /api/program-templates*`, `GET|PUT /api/stations/{id}/programming`, `POST /api/stations/{id}/programming/preview`, `GET /api/letters`, `GET /api/letters/{id}`, `POST /api/letters/{id}/status`, `POST /api/letters/{id}/reply`, `GET /api/play-history`, `GET /api/play-history/{id}`, `GET /api/monitor/summary` は `X-Admin-Token` 前提とする
- 将来 `Spring Security` を導入しても DTO を崩さない

## 4. 主要DTO

### 4.1 StationSummary

```json
{
  "id": "station-night",
  "name": "Midnight Echo",
  "frequencyMHz": 81.3,
  "genre": "talk",
  "isActive": true,
  "programmingEnabled": true,
  "defaultProgramTemplateId": "tmpl-night-regular"
}
```

### 4.2 StationDetail

```json
{
  "id": "station-night",
  "name": "Midnight Echo",
  "frequencyMHz": 81.3,
  "genre": "talk",
  "languagePersonaId": "persona-night-main",
  "defaultVoiceProfileId": "voice-night-main",
  "isActive": true,
  "version": 4,
  "programming": {
    "enabled": true,
    "defaultTemplateId": "tmpl-night-regular",
    "fallbackStrategy": "LEGACY_RATIO",
    "planningHorizonMinutes": 20,
    "preGeneration": {
      "mode": "ASSISTED",
      "maxPreparedMinutes": 12,
      "maxPreparedBlocks": 2,
      "preferCacheReuse": true
    },
    "replay": {
      "intensity": "LIGHT",
      "eligibleSegmentTypes": ["MUSIC_AI", "MUSIC_LOCAL", "JINGLE"],
      "minimumAssetAgeHours": 6,
      "cooldownHours": 72,
      "maxReplaySharePercent": 20,
      "excludeLetterSegments": true
    },
    "composition": {
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
    }
  }
}
```

### 4.3 RadioStatus

```json
{
  "sessionId": "playout-20260320-001",
  "stationId": "station-night",
  "programBlockId": "program-20260320-01",
  "programTemplateId": "tmpl-night-regular",
  "programTitle": "深夜の作業ノート",
  "state": "PLAYING",
  "currentItemId": "queue-0012",
  "bufferReadyCount": 2,
  "degraded": false,
  "updatedAt": "2026-03-20T09:00:00Z"
}
```

### 4.4 QueueItem

```json
{
  "id": "queue-0012",
  "programBlockId": "program-20260320-01",
  "programSlotId": "slot-talk-open",
  "slotRole": "OPENING",
  "type": "TALK",
  "title": "オープニングトーク",
  "playbackMode": "SERVER_AUDIO",
  "assetUrl": "/api/assets/audio/queue-0012.wav",
  "contentOrigin": "CACHE_REUSED",
  "preparedAt": "2026-03-20T08:58:00Z",
  "replayOfPlayHistoryId": null,
  "speechDirectiveId": null,
  "letterId": "letter-0001",
  "durationMs": 28000,
  "status": "READY"
}
```

### 4.5 LetterDetail

```json
{
  "id": "letter-0001",
  "stationId": "station-night",
  "radioName": "夜更かしペンギン",
  "subject": "最近の作業BGM",
  "body": "深夜作業でおすすめの音を教えてください。",
  "status": "ADOPTED",
  "adoptedInSessionId": "playout-20260320-001",
  "createdAt": "2026-03-20T09:00:00Z",
  "replies": [
    {
      "id": "reply-0001",
      "replyText": "今夜は静かなアンビエントを中心に流します。",
      "createdAt": "2026-03-20T09:10:00Z"
    }
  ],
  "playHistory": [
    {
      "id": "play-history-0001",
      "sessionId": "playout-20260320-001",
      "queueItemId": "queue-0012",
      "programBlockId": "program-20260320-01",
      "programSlotId": "slot-letter-1",
      "segmentType": "LETTER",
      "title": "レター",
      "playbackMode": "SERVER_AUDIO",
      "resultStatus": "DONE",
      "playedAt": "2026-03-20T09:15:00Z"
    }
  ]
}
```

### 4.6 PlayHistoryItem

```json
{
  "id": "play-history-0001",
  "sessionId": "playout-20260320-001",
  "stationId": "station-night",
  "queueItemId": "queue-0012",
  "letterId": "letter-0001",
  "programBlockId": "program-20260320-01",
  "programSlotId": "slot-letter-1",
  "segmentType": "LETTER",
  "title": "レター",
  "playbackMode": "SERVER_AUDIO",
  "contentOrigin": "LIVE_GEN",
  "replayOfPlayHistoryId": null,
  "resultStatus": "DONE",
  "correlationId": "corr-abc123",
  "playedAt": "2026-03-20T09:15:00Z"
}
```

### 4.7 ProgramBlockSummary

```json
{
  "id": "program-20260320-01",
  "stationId": "station-night",
  "templateId": "tmpl-night-regular",
  "templateVersion": 3,
  "title": "深夜の作業ノート",
  "status": "ACTIVE",
  "plannedDurationMs": 1200000,
  "remainingSlotCount": 3,
  "startedAt": "2026-03-20T09:00:00Z"
}
```

### 4.8 SpeechDirective

```json
{
  "id": "sd-0012",
  "text": "こんばんは、Midnight Echo です。",
  "normalizedText": "こんばんは、ミッドナイト・エコーです。",
  "pronunciationHints": [
    { "surface": "Echo", "reading": "エコー" }
  ],
  "emotion": "calm",
  "tempo": "medium",
  "pauseHints": [
    { "index": 8, "durationMs": 250 }
  ],
  "personaRef": "persona-night-main",
  "voiceHint": "voicevox:4"
}
```

### 4.9 ErrorResponse

```json
{
  "timestamp": "2026-03-20T09:00:00Z",
  "code": "QUEUE_NOT_READY",
  "message": "Next segment is not ready",
  "details": {
    "stationId": "station-night"
  },
  "correlationId": "corr-abc123"
}
```

## 5. REST API一覧

| Method | Path | 用途 |
|---|---|---|
| `GET` | `/api/stations` | 局一覧取得 |
| `GET` | `/api/stations/{id}` | 局詳細取得 |
| `POST` | `/api/stations` | 局作成 |
| `PUT` | `/api/stations/{id}` | 局更新 |
| `GET` | `/api/stations/{id}/programming` | 局の番組編成設定取得 |
| `PUT` | `/api/stations/{id}/programming` | 局の番組編成設定更新 |
| `POST` | `/api/stations/{id}/programming/preview` | 局の番組編成プレビュー |
| `GET` | `/api/program-templates` | 番組テンプレート一覧取得 |
| `GET` | `/api/program-templates/{id}` | 番組テンプレート詳細取得 |
| `POST` | `/api/program-templates` | 番組テンプレート作成 |
| `PUT` | `/api/program-templates/{id}` | 番組テンプレート更新 |
| `POST` | `/api/clients/capabilities` | クライアント能力申告 |
| `POST` | `/api/radio/tune` | 局切替 |
| `POST` | `/api/radio/play` | 再生開始 |
| `POST` | `/api/radio/stop` | 再生停止 |
| `GET` | `/api/radio/status` | 現在の再生状態取得 |
| `GET` | `/api/radio/program` | 現在の番組 block 取得 |
| `GET` | `/api/radio/queue` | 現在キュー取得 |
| `GET` | `/api/radio/next-segment` | 次の再生候補取得 |
| `GET` | `/api/radio/next-speech-directive` | Client-side TTS 用指示取得。`clientId` 指定時は登録済み能力で `voiceHint` を最適化 |
| `POST` | `/api/radio/playback-events` | クライアント再生イベント通知 |
| `GET` | `/api/letters` | レター一覧取得 |
| `GET` | `/api/letters/{id}` | レター詳細取得 |
| `POST` | `/api/letters` | レター投稿 |
| `POST` | `/api/letters/{id}/status` | レター状態更新 |
| `POST` | `/api/letters/{id}/reply` | レター返信追加 |
| `GET` | `/api/play-history` | 放送履歴一覧取得 |
| `GET` | `/api/play-history/{id}` | 放送履歴詳細取得 |
| `GET` | `/api/settings` | 設定取得 |
| `PUT` | `/api/settings` | 設定更新 |
| `POST` | `/api/settings/test-connections` | Provider 接続テスト |
| `GET` | `/api/health` | ヘルス参照 |
| `GET` | `/api/monitor/summary` | 監視サマリ参照 |

## 6. 主要API詳細

### 6.1 `POST /radio/tune`

Request:

```json
{
  "stationId": "station-night",
  "requestedBy": "web-client",
  "resumePlayback": true
}
```

Response:

```json
{
  "sessionId": "playout-20260320-001",
  "stationId": "station-night",
  "state": "PREPARING",
  "queueWarmupStarted": true,
  "correlationId": "corr-abc123"
}
```

- `resumePlayback=true` の場合でも初回応答は `PREPARING` を返し、その後の warmup 完了時に Server が自動で `PLAYING` へ進めてよい
- `resumePlayback=false` の場合は `PREPARING` のまま返し、Client が `POST /api/radio/play` で開始する
- `requestedBy` は `playout_session.requested_by` に保存し、監査と相関確認に使う

### 6.2 `POST /clients/capabilities`

```json
{
  "clientId": "web-local-chrome",
  "clientType": "WEB",
  "supportsClientSideTts": false,
  "supportedVoiceEngines": [],
  "preferredPlaybackMode": "SERVER_AUDIO"
}
```

- Server は `clientId` ごとに最新の能力申告を保持し、`GET /api/radio/next-speech-directive?clientId=...` の `voiceHint` 解決に利用する
- `localVoiceProfiles` がある場合、Server は既定 `VoiceProfile` よりクライアント側のローカル音声候補を優先して `SpeechDirective` を組み立ててよい

### 6.2.1 `GET /radio/next-speech-directive`

- 既定では次の `READY` item に対する `SpeechDirective` を返す
- `clientId` を指定した場合は、そのクライアントの最新 `client_capabilities` を参照し、`voiceHint` にローカル音声候補を反映する
- 未登録の `clientId` では station の既定 `VoiceProfile` を使う

### 6.3 `POST /letters`

```json
{
  "stationId": "station-night",
  "radioName": "夜更かしペンギン",
  "subject": "最近の作業BGM",
  "body": "深夜作業でおすすめの音を教えてください。"
}
```

Response:

```json
{
  "id": "letter-0001",
  "status": "UNREAD",
  "createdAt": "2026-03-20T09:00:00Z"
}
```

### 6.3.1 `GET /letters/{id}`

管理者向けの詳細取得 API とする。`X-Admin-Token` が必要。

Response:

```json
{
  "id": "letter-0001",
  "stationId": "station-night",
  "radioName": "夜更かしペンギン",
  "subject": "最近の作業BGM",
  "body": "深夜作業でおすすめの音を教えてください。",
  "status": "ADOPTED",
  "adoptedInSessionId": "playout-20260320-001",
  "createdAt": "2026-03-20T09:00:00Z",
  "replies": [],
  "playHistory": [
    {
      "id": "play-history-0001",
      "sessionId": "playout-20260320-001",
      "stationId": "station-night",
      "queueItemId": "queue-0012",
      "programBlockId": "program-20260320-01",
      "programSlotId": "slot-letter-1",
      "segmentType": "LETTER",
      "title": "レター: 最近の作業BGM",
      "playbackMode": "SERVER_AUDIO",
      "resultStatus": "DONE",
      "correlationId": "corr-abc123",
      "playedAt": "2026-03-20T09:15:00Z",
      "letter": {
        "letterId": "letter-0001",
        "radioName": "夜更かしペンギン",
        "subject": "最近の作業BGM",
        "adoptedInSessionId": "playout-20260320-001"
      }
    }
  ]
}
```

- `replies` は `letter_reply` の作成順で返す
- `playHistory` はこのレターに紐づく放送履歴を新しい順で返す
- レター紐付けの正本は `queue_item.letter_id` と `play_history.letter_id` とし、`program_block_slot.slot_context` は件名など補助メタに使う

### 6.3.2 `POST /letters/{id}/status`

`ADOPTED` へ更新する場合は採用先 `sessionId` を必須とする。

```json
{
  "status": "ADOPTED",
  "sessionId": "playout-20260320-001"
}
```

### 6.3.3 `POST /letters/{id}/reply`

`X-Admin-Token` が必要。

```json
{
  "replyText": "今夜は静かなアンビエントを中心に流します。"
}
```

### 6.3.4 `GET /play-history`

管理者向けの放送履歴一覧 API とする。`X-Admin-Token` が必要。

Query:

- `stationId` 任意
- `sessionId` 任意
- `letterId` 任意
- `limit` 任意。既定は最新 50 件

Response:

```json
[
  {
    "id": "play-history-0001",
    "sessionId": "playout-20260320-001",
    "stationId": "station-night",
    "queueItemId": "queue-0012",
    "programBlockId": "program-20260320-01",
    "programSlotId": "slot-letter-1",
    "segmentType": "LETTER",
    "title": "レター: 最近の作業BGM",
    "playbackMode": "SERVER_AUDIO",
    "resultStatus": "DONE",
    "correlationId": "corr-abc123",
    "playedAt": "2026-03-20T09:15:00Z",
    "letter": {
      "letterId": "letter-0001",
      "radioName": "夜更かしペンギン",
      "subject": "最近の作業BGM",
      "adoptedInSessionId": "playout-20260320-001"
    }
  }
]
```

### 6.3.5 `GET /play-history/{id}`

管理者向けの放送履歴詳細 API とする。`X-Admin-Token` が必要。

Response は `GET /play-history` の各要素と同じ DTO を返す。

### 6.4 `POST /radio/playback-events`

```json
{
  "clientId": "web-local-chrome",
  "sessionId": "playout-20260320-001",
  "itemId": "queue-0012",
  "eventType": "SEGMENT_STARTED",
  "occurredAt": "2026-03-20T09:00:05Z"
}
```

この API は厳密同期ではなく、Server が体感ズレやエラー把握を行うための補助イベントとする。

- `sessionId` と `itemId` は同一 `playout_session` に属している必要がある
- `SEGMENT_STARTED` は `READY` item、`SEGMENT_ENDED` と `PLAYBACK_STOPPED` は現在 `PLAYING` 中の item のみ受け付ける
- 条件を満たさない場合は `409 CONFLICT` を返す

### 6.5 `GET /radio/program`

Response:

```json
{
  "id": "program-20260320-01",
  "stationId": "station-night",
  "templateId": "tmpl-night-regular",
  "templateVersion": 3,
  "title": "深夜の作業ノート",
  "status": "ACTIVE",
  "plannedDurationMs": 1200000,
  "remainingSlotCount": 3,
  "slots": [
    {
      "slotId": "slot-talk-open",
      "role": "OPENING",
      "constraintMode": "HARD",
      "targetDurationMs": 30000,
      "resolvedSegmentType": "TALK"
    }
  ]
}
```

### 6.6 `PUT /stations/{id}/programming`

Request:

```json
{
  "version": 4,
  "enabled": true,
  "defaultTemplateId": "tmpl-night-regular",
  "fallbackStrategy": "LEGACY_RATIO",
  "planningHorizonMinutes": 20,
  "preGeneration": {
    "mode": "ASSISTED",
    "maxPreparedMinutes": 12,
    "maxPreparedBlocks": 2,
    "preferCacheReuse": true
  },
  "replay": {
    "intensity": "LIGHT",
    "eligibleSegmentTypes": ["MUSIC_AI", "MUSIC_LOCAL", "JINGLE"],
    "minimumAssetAgeHours": 6,
    "cooldownHours": 72,
    "maxReplaySharePercent": 20,
    "excludeLetterSegments": true
  },
  "composition": {
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
  },
  "rules": [
    {
      "priority": 100,
      "days": ["MON", "TUE", "WED", "THU", "FRI"],
      "startTime": "22:00",
      "endTime": "02:00",
      "minimumPendingLetters": 0,
      "requiredProviderStates": [],
      "templateId": "tmpl-night-regular"
    },
    {
      "priority": 120,
      "days": ["SAT", "SUN"],
      "startTime": "22:00",
      "endTime": "02:00",
      "minimumPendingLetters": 3,
      "requiredProviderStates": ["MUSICGEN_UP"],
      "templateId": "tmpl-night-letter"
    }
  ]
}
```

Response:

```json
{
  "stationId": "station-night",
  "version": 5,
  "enabled": true,
  "updatedAt": "2026-03-20T09:00:00Z"
}
```

`PUT /stations/{id}/programming` では station 側の `programmingEnabled` と `defaultProgramTemplateId` も同期更新し、`version` は楽観ロック用に扱う。`preGeneration`, `replay`, `composition` は station ごとの実行時調整プロファイルとして扱い、既存 `ProgramTemplate` の版を壊さずに運用中 block の深さ、キャッシュ優先度、再放送比率、番組の混ぜ方を調整できるようにする。

### 6.7 `POST /stations/{id}/programming/preview`

Request:

```json
{
  "at": "2026-03-20T23:30:00+09:00",
  "pendingLetterCount": 4,
  "providerStates": {
    "musicGen": "UP",
    "tts": "UP",
    "llm": "UP"
  }
}
```

Response:

```json
{
  "stationId": "station-night",
  "selectedTemplateId": "tmpl-night-letter",
  "fallbackApplied": false,
  "program": {
    "title": "深夜レター拾い",
    "plannedDurationMs": 1200000
  },
  "slots": [
    {
      "slotId": "letter-main",
      "role": "LETTER",
      "constraintMode": "HARD",
      "targetDurationMs": 120000
    }
  ],
  "validationWarnings": []
}
```

### 6.8 `GET /api/settings`

`config.json` の現在値に `version` を付けて返し、Web/Native が同じ契約で設定を表示できるようにします。サーバーは起動時に `schemaVersion` も検証し、一致しない場合には `400` を返します。

```json
{
  "version": 4,
  "schemaVersion": "2026-04",
  "server": {
    "bindHost": "127.0.0.1",
    "port": 8080
  },
  "paths": {
    "dataRoot": "./data",
    "musicLibrary": "./data/library/music"
  },
  "playout": {
    "targetReadyCount": 3,
    "minimumReadyCount": 2,
    "minReadyDurationMs": 90000,
    "maxPreparedDurationMs": 480000,
    "maxPreparedBlocks": 2,
    "scriptAheadCount": 4,
    "ttsAheadCount": 3,
    "musicAheadCount": 2,
    "idlePrefetchEnabled": true
  },
  "cache": {
    "scriptMaxBytes": 134217728,
    "ttsMaxBytes": 536870912,
    "musicMaxBytes": 2147483648,
    "scriptRetentionDays": 7,
    "ttsRetentionDays": 30,
    "musicRetentionDays": 30,
    "scriptReuseScope": "STATION",
    "ttsReuseScope": "STATION",
    "musicReuseScope": "GLOBAL",
    "cleanupBatchSize": 200
  },
  "providers": {
    "llm": { "defaultProvider": "ollama" },
    "tts": { "defaultProvider": "voicevox" },
    "musicGen": { "defaultProvider": "ace-step" }
  },
  "features": {
    "streaming": {
      "placeholderEnabled": true
    }
  },
  "security": {
    "adminTokenRef": "env:SEEDSHIFT_ADMIN_TOKEN"
  }
}
```

### 6.9 `PUT /api/settings`

クライアントから送られた `version` と `schemaVersion` を現在の `config.json` と照合し、`version` は楽観ロック、`schemaVersion` は契約互換性確認に使います。`paths`, `playout`, `cache`, `providers`, `security`, `features` を受け付け、機密値は `env:`/`file:` 参照の形でそのまま保持します。`playout` は先行生成の深さと内部準備量の上限を、`cache` は内部保存サイズと再利用範囲を決める。

```json
{
  "version": 4,
  "schemaVersion": "2026-04",
  "paths": { ... },
  "playout": { ... },
  "cache": { ... },
  "providers": { ... }
}
```

### 6.10 `POST /api/settings/test-connections`

Provider に対する接続テストを一括実行し、種別ごとの `status` を返します。レスポンスは `checkedAt` と `providers` map を持ち、各 payload は `/api/monitor/summary`, `/api/health`, SSE `provider.health.changed` と同一形式です。

```json
{
  "checkedAt": "2026-03-29T10:15:00Z",
  "providers": {
    "tts": {
      "providerType": "tts",
      "providerKey": "voicevox",
      "status": "UP",
      "message": "接続成功",
      "responseTimeMs": 53
    }
  }
}
```

### 6.11 `GET /api/assets/audio/{assetId}.wav`

生成済み audio asset がある場合は `generated_asset.storage_path` を優先して `audio/wav` で返し、見つからない場合のみ `features.streaming.placeholderEnabled` に従って placeholder を返します。`queue_item.assetId` と `generated_asset` により、Server が file 正本と DB メタを追跡します。

### 6.12 Provider Health

`/api/monitor/summary` と `/api/health` は station/queue 情報に加えて、最新の `ProviderHealthPayload` を返します。`status` は `UP/DEGRADED/DOWN`、`lastCheckedAt`、`responseTimeMs`、`message`、`capabilities` を含み、SSE `provider.health.changed` と同じフォーマットでクライアントが再利用しやすくなっています。

```json
{
  "providerType": "musicGen",
  "status": "DEGRADED",
  "lastCheckedAt": "2026-03-20T09:12:00Z",
  "responseTimeMs": 312,
  "message": "FastAPI worker がタイムアウト",
  "capabilities": ["ace-step:fast"]
}
```

## 7. SSE仕様

Endpoint:

- `GET /api/stream/events`

Event 種別:

| Event | Payload | 用途 |
|---|---|---|
| `radio.status.changed` | `RadioStatus` | 再生状態更新 |
| `queue.updated` | `QueueSnapshot` | キュー差し替え・Ready数更新 |
| `program.changed` | `ProgramBlockSummary` | 現在番組 block の切替・更新 |
| `subtitle.updated` | `SubtitlePayload` | 字幕更新 |
| `provider.health.changed` | `ProviderHealthPayload` | Provider 異常通知 |
| `buffer.warning` | `BufferWarningPayload` | 先読み不足通知 |
| `letter.updated` | `LetterSummary` | レター一覧反映 |

SSE は `Last-Event-ID` を受け付け、短時間切断時の再購読に備える。

## 8. エラー体系

| Code | HTTP | 意味 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 入力不正 |
| `INVALID_TEMPLATE` | 400 | 番組テンプレート不正 |
| `NOT_FOUND` | 404 | 対象なし |
| `CONFLICT` | 409 | 状態競合 |
| `QUEUE_NOT_READY` | 409 | 次セグメント未生成 |
| `PROVIDER_UNAVAILABLE` | 503 | Provider 利用不可 |
| `ADMIN_AUTH_REQUIRED` | 401 | 管理操作の認証不足 |
| `INTERNAL_ERROR` | 500 | サーバー内部エラー |

## 9. API設計ルール

- レター投稿は `Idempotency-Key` を受け付けて二重投稿を防止する
- 設定更新は楽観ロック用 `version` を含める
- 局管理と番組編成管理の更新も楽観ロック用 `version` を含める
- QueueItem は `status` と `playbackMode` を持ち、 Web と Native で共通利用する
- `RadioStatus` と `QueueItem` は番組 block との関連 ID を返し、 UI が局情報と番組情報を同時に表示できるようにする
- `SpeechDirective` は Native 向けの主契約だが、Web もデバッグ表示に利用できる
- 監視系 API は UI 用の集約 DTO を返し、生ログ全文は返さない
- `TuneResponse` は相関追跡のため `correlationId` を返す
- Preview API は副作用を持たず、未保存設定の検証にも使えるようにする

## 10. OpenAPI生成方針

- `/v3/api-docs` を開発時のみ有効化する
- DTO は Server / Client 両方で再利用しやすいよう JSON naming を固定する
- 破壊的変更が必要な場合のみ `/api/v2` を追加する
