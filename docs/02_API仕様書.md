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
- 設定更新、局管理、番組編成管理、レター管理、履歴参照、監視 API は `X-Admin-Token` を要求する
- 認証区分は次の表、`src/test/resources/contracts/api-auth-matrix.json`、生成 OpenAPI の `security` を一致させる
- `PUBLIC` は `X-Admin-Token` 不要、`ADMIN` は `X-Admin-Token` 必須を表す
- Web 管理 UI は管理トークンを `NEXT_PUBLIC_*`、browser storage、Cookie へ埋め込まない。同一 origin の Next.js BFF が認証済み session を検証し、管理 API に限って server-side の `SEEDSHIFT_ADMIN_TOKEN` を `X-Admin-Token` として注入する
- BFF は browser から受け取った `X-Admin-Token`、Cookie、`X-CSRF-Token` を Server へ転送しない。管理対象外の公開 API には管理トークンを注入しない
- `POST /api/auth/login`、`GET /api/auth/session`、`POST /api/auth/logout` は Web BFF 専用 endpoint であり、Spring Boot の共通 `/api` 契約、生成 OpenAPI、`api-auth-matrix.json` には含めない
- Web の session は `HttpOnly`、`SameSite=Strict`、production では `Secure` の Cookie とし、状態変更を伴う管理 API proxy と logout は `X-CSRF-Token` を要求する
- 将来 `Spring Security` を導入しても DTO を崩さない

| Method | Path | Access |
|---|---|---|
| `GET` | `/api/assets/audio/{assetId}.wav` | `PUBLIC` |
| `POST` | `/api/clients/capabilities` | `PUBLIC` |
| `GET` | `/api/health` | `PUBLIC` |
| `GET` | `/api/letters` | `ADMIN` |
| `POST` | `/api/letters` | `PUBLIC` |
| `GET` | `/api/letters/{id}` | `ADMIN` |
| `POST` | `/api/letters/{id}/reply` | `ADMIN` |
| `POST` | `/api/letters/{id}/status` | `ADMIN` |
| `POST` | `/api/letters/public/history` | `PUBLIC` |
| `GET` | `/api/management/dashboard` | `ADMIN` |
| `GET` | `/api/management/stations/{stationId}/content` | `ADMIN` |
| `POST` | `/api/management/stations/{stationId}/pre-generations` | `ADMIN` |
| `GET` | `/api/monitor/assets/consistency` | `ADMIN` |
| `GET` | `/api/monitor/logs` | `ADMIN` |
| `GET` | `/api/monitor/summary` | `ADMIN` |
| `GET` | `/api/play-history` | `ADMIN` |
| `GET` | `/api/play-history/{id}` | `ADMIN` |
| `GET` | `/api/program-templates` | `ADMIN` |
| `POST` | `/api/program-templates` | `ADMIN` |
| `GET` | `/api/program-templates/{id}` | `ADMIN` |
| `PUT` | `/api/program-templates/{id}` | `ADMIN` |
| `GET` | `/api/radio/next-segment` | `PUBLIC` |
| `GET` | `/api/radio/next-speech-directive` | `PUBLIC` |
| `POST` | `/api/radio/play` | `PUBLIC` |
| `POST` | `/api/radio/playback-events` | `PUBLIC` |
| `GET` | `/api/radio/program` | `PUBLIC` |
| `GET` | `/api/radio/queue` | `PUBLIC` |
| `GET` | `/api/radio/status` | `PUBLIC` |
| `POST` | `/api/radio/stop` | `PUBLIC` |
| `POST` | `/api/radio/tune` | `PUBLIC` |
| `GET` | `/api/settings` | `ADMIN` |
| `PUT` | `/api/settings` | `ADMIN` |
| `POST` | `/api/settings/test-connections` | `ADMIN` |
| `GET` | `/api/stations` | `PUBLIC` |
| `POST` | `/api/stations` | `ADMIN` |
| `GET` | `/api/stations/{id}` | `PUBLIC` |
| `PUT` | `/api/stations/{id}` | `ADMIN` |
| `GET` | `/api/stations/{id}/programming` | `ADMIN` |
| `PUT` | `/api/stations/{id}/programming` | `ADMIN` |
| `POST` | `/api/stations/{id}/programming/preview` | `ADMIN` |
| `GET` | `/api/stream/events` | `PUBLIC` |

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

- `programming` は `GET /api/stations/{id}/programming` の応答と同じ文脈で扱う局ごとの番組編成ポリシーであり、`preGeneration`, `replay`, `composition` の 3 つの runtime profile を含む
- これらの profile は `station_programming_policy` の保存内容と 1 対 1 で対応し、StationDetail はその正本を読み出した denormalized view とみなす

### 4.2.1 StationUpsertRequest / StationResponse

`POST /api/stations` と `PUT /api/stations/{id}` は station 基本情報を保存する管理 API である。`PUT` では path の `id` と body の `id` を一致させ、`version` を楽観ロックに使う。

```json
{
  "version": 4,
  "id": "station-night",
  "name": "Midnight Echo",
  "frequencyMHz": 81.3,
  "genre": "talk",
  "languagePersonaId": "persona-night-main",
  "defaultVoiceProfileId": "voice-night-main",
  "isActive": true,
  "programmingEnabled": true,
  "defaultProgramTemplateId": "tmpl-night-regular"
}
```

`name`, `genre`, `languagePersonaId`, `defaultVoiceProfileId` は必須、`frequencyMHz` は `0.1` 以上とする。`languagePersonaId`, `defaultVoiceProfileId`, `defaultProgramTemplateId` は Server 側で参照整合性を確認する。`defaultProgramTemplateId` は `GLOBAL` または同一 station scope の template のみ許可する。Web `/settings` の station 基本情報 editor は `programmingEnabled` と `defaultProgramTemplateId` を直接編集せず、保存済み summary を保持して送信する。

`defaultVoiceProfileId` はチャンネルごとの差別化の主契約である。`VoiceProfile.scope=GLOBAL` は複数 station から参照でき、`scope=STATION` は同一 station のみ参照できる。Irodori-TTS の局専用 voice profile では、API response に voice id や style preset の短い識別子は出してよいが、参照音声の実ファイル path、個人名、同意書本文は返さない。

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

### 4.5 QueueSnapshot

```json
{
  "sessionId": "playout-20260320-001",
  "stationId": "station-night",
  "items": [
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
      "durationMs": 28000,
      "status": "READY"
    }
  ],
  "correlationId": "corr-abc123"
}
```

`QueueItem.contentOrigin` は少なくとも次を取りうる。

- `LIVE_GEN`: MusicGen などで新規生成した asset
- `CACHE_REUSED`: 既存 generated asset を再利用した asset
- `ARCHIVE_REPLAY`: 過去放送 archive の再放送
- `MUSIC_LOCAL_FALLBACK`: MusicGen 失敗時に local music library へ切り替えた asset
- `MUSIC_LOCAL_PLACEHOLDER`: local music slot に対応する実ファイルがないため silent placeholder を割り当てた asset
- `JINGLE_FALLBACK`: MusicGen 失敗かつ local music library も使えないため jingle placeholder を割り当てた asset
- `PLACEHOLDER`: queue 維持用の fallback placeholder segment

### 4.6 LetterDetail

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

### 4.7 LetterPublicLookup

```json
{
  "letters": [
    {
      "id": "letter-0001",
      "stationId": "station-night",
      "radioName": "夜更かしペンギン",
      "subject": "最近の作業BGM",
      "status": "ADOPTED",
      "adoptedInSessionId": "playout-20260320-001",
      "createdAt": "2026-03-20T09:00:00Z",
      "playHistory": [
        {
          "id": "play-history-0001",
          "sessionId": "playout-20260320-001",
          "stationId": "station-night",
          "segmentType": "LETTER",
          "title": "レター",
          "resultStatus": "DONE",
          "playedAt": "2026-03-20T09:15:00Z"
        }
      ]
    }
  ]
}
```

- 公開側 `/letters` はこの最小 DTO を使って、自分が送ったレターの採用状況と放送履歴を参照する
- `body` と `replies` は返さない
- 未知の `letterId` は無視し、見つかったレターだけを返す

### 4.8 PlayHistoryItem

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

### 4.9 ProgramBlockSummary

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
  "startedAt": "2026-03-20T09:00:00Z",
  "slots": [
    {
      "id": "block-slot-001",
      "slotId": "slot-talk-open",
      "role": "OPENING",
      "constraintMode": "HARD",
      "resolvedSegmentType": "TALK",
      "targetDurationMs": 30000,
      "status": "QUEUED",
      "slotContext": {},
      "title": "オープニング"
    }
  ],
  "correlationId": "corr-abc123"
}
```

### 4.10 SpeechDirective

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
  "voiceHint": "irodori:night-main"
}
```

`voiceHint` は `engine:profileKey` の短い識別子とし、Irodori-TTS の場合も参照音声の実ファイル path や個人名は含めない。Server-side TTS では `voiceHint` を asset metadata と監査追跡に使い、Client-side TTS では Native Client の adapter 解決に使う。

### 4.11 SubtitlePayload

```json
{
  "sessionId": "playout-20260320-01",
  "itemId": "queue-0012",
  "speechDirectiveId": "sd-0012",
  "text": "こんばんは、ミッドナイト・エコーです。",
  "updatedAt": "2026-03-20T09:00:05Z"
}
```

- `itemId`, `speechDirectiveId` は再生中 item がない時は `null`
- 再生停止や current item 消失時は `text` を空文字で送って字幕をクリアする

### 4.12 BufferWarningPayload

```json
{
  "sessionId": "playout-20260320-001",
  "readyCount": 1,
  "occurredAt": "2026-03-20T09:14:00Z"
}
```

### 4.13 ErrorResponse

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
| `POST` | `/api/letters` | レター投稿 |
| `POST` | `/api/letters/public/history` | 公開用レター採用履歴取得 |
| `GET` | `/api/letters` | レター一覧取得 |
| `GET` | `/api/letters/{id}` | レター詳細取得 |
| `POST` | `/api/letters/{id}/status` | レター状態更新 |
| `POST` | `/api/letters/{id}/reply` | レター返信追加 |
| `GET` | `/api/play-history` | 放送履歴一覧取得 |
| `GET` | `/api/play-history/{id}` | 放送履歴詳細取得 |
| `GET` | `/api/settings` | 設定取得 |
| `PUT` | `/api/settings` | 設定更新 |
| `POST` | `/api/settings/test-connections` | Provider 接続テスト |
| `GET` | `/api/management/dashboard` | 管理トップ向けの全体状況と局別コンテンツ集約 |
| `GET` | `/api/management/stations/{stationId}/content` | 指定局の番組・台本・音声・曲 asset 保有量 |
| `POST` | `/api/management/stations/{stationId}/pre-generations` | オフエア事前生成 request 受付 |
| `GET` | `/api/health` | ヘルス参照 |
| `GET` | `/api/monitor/summary` | 監視サマリ参照 |
| `GET` | `/api/monitor/logs` | 構造化運用ログ参照 |
| `GET` | `/api/monitor/assets/consistency` | generated asset 整合性検査 |

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

### 6.3.1 `POST /letters/public/history`

公開側の採用履歴参照 API とする。`X-Admin-Token` は不要で、本文と返信は返さない。

Request:

```json
{
  "letterIds": ["letter-0001", "letter-0002"]
}
```

Response:

```json
{
  "letters": [
    {
      "id": "letter-0001",
      "stationId": "station-night",
      "radioName": "夜更かしペンギン",
      "subject": "最近の作業BGM",
      "status": "ADOPTED",
      "adoptedInSessionId": "playout-20260320-001",
      "createdAt": "2026-03-20T09:00:00Z",
      "playHistory": [
        {
          "id": "play-history-0001",
          "sessionId": "playout-20260320-001",
          "stationId": "station-night",
          "segmentType": "LETTER",
          "title": "レター",
          "resultStatus": "DONE",
          "playedAt": "2026-03-20T09:15:00Z"
        }
      ]
    }
  ]
}
```

- 未知の `letterId` は無視し、見つかったレターだけを返す
- `body` と `replies` は返さず、公開 UI で必要な最小要約だけを返す

### 6.3.2 `GET /letters/{id}`

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

### 6.3.3 `POST /letters/{id}/status`

`ADOPTED` へ更新する場合は採用先 `sessionId` を必須とする。

```json
{
  "status": "ADOPTED",
  "sessionId": "playout-20260320-001"
}
```

### 6.3.4 `POST /letters/{id}/reply`

`X-Admin-Token` が必要。

```json
{
  "replyText": "今夜は静かなアンビエントを中心に流します。"
}
```

### 6.3.5 `GET /play-history`

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

### 6.3.6 `GET /play-history/{id}`

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
- `SEGMENT_ERROR` は再生開始前の `READY` item または現在 `PLAYING` 中の item を受け付け、対象 item を `FAILED` として縮退補充を試みる
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

### 6.6 `GET /stations/{id}/programming`

管理者向けの番組編成ポリシー取得 API とする。`X-Admin-Token` が必要。

Response:

```json
{
  "stationId": "station-night",
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
  "updatedAt": "2026-03-20T09:00:00Z"
}
```

`GET /api/stations/{id}` の `programming` はこの応答の要約ビューであり、UI では一覧表示向けの軽量な局詳細として扱う。`GET /api/stations/{id}/programming` は編集画面向けの完全版とする。

### 6.6.1 `PUT /stations/{id}/programming`

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
  "updatedAt": "2026-03-20T09:00:00Z"
}
```

`PUT /stations/{id}/programming` では station 側の `programmingEnabled` と `defaultProgramTemplateId` も同期更新し、`version` は楽観ロック用に扱う。`preGeneration`, `replay`, `composition` は station ごとの実行時調整プロファイルとして扱い、既存 `ProgramTemplate` の版を壊さずに運用中 block の深さ、キャッシュ優先度、再放送比率、番組の混ぜ方を調整できるようにする。`StationDetail.programming` はこの完全版の要約、`station_programming_policy` は完全版の正本とする。

Web の `/settings` ではこの API を station programming policy editor の保存先として使う。Import / Export の対象である `/api/settings` `config.json` とは別の DB 正本であり、保存後は station detail と station list を再取得して要約ビューを更新する。Preview UI は既定では保存済み policy を評価しつつ、必要に応じて未保存 `StationProgrammingPolicy` draft と `ProgramTemplate` draft を request payload に含めて評価できる。preview request に含めた draft は DB へ保存せず、実行中 block にも反映しない。

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
  },
  "policyDraft": {
    "version": 3,
    "enabled": true,
    "defaultTemplateId": "tmpl-preview-draft",
    "fallbackStrategy": "LEGACY_RATIO",
    "planningHorizonMinutes": 30,
    "preGeneration": {
      "mode": "ASSISTED",
      "maxPreparedMinutes": 12,
      "maxPreparedBlocks": 2,
      "preferCacheReuse": true
    },
    "replay": {
      "intensity": "LIGHT",
      "eligibleSegmentTypes": ["MUSIC_AI", "MUSIC_LOCAL"],
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
        "days": ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"],
        "startTime": "00:00",
        "endTime": "23:59",
        "minimumPendingLetters": 1,
        "requiredProviderStates": [],
        "templateId": "tmpl-preview-draft"
      }
    ]
  },
  "templateDraft": {
    "id": "tmpl-preview-draft",
    "scope": "STATION",
    "stationId": "station-night",
    "name": "深夜レター拾い draft",
    "version": 0,
    "targetDurationMinutes": 20,
    "planningHorizonMinutes": 15,
    "isActive": true,
    "editorialPolicy": {
      "tone": "calm"
    },
    "fallbackTemplateId": "tmpl-night-regular",
    "slots": [
      {
        "slotId": "letter-main",
        "role": "LETTER",
        "constraintMode": "HARD",
        "candidateSegmentTypes": ["LETTER", "TALK"],
        "fallbackSegmentTypes": ["TALK"],
        "targetDurationMs": 120000,
        "slotPolicy": {
          "preferFreshGeneration": true
        }
      }
    ]
  }
}
```

`policyDraft`, `templateDraft` は任意です。未指定時は保存済み `StationProgrammingPolicy` / `ProgramTemplate` を評価し、persisted policy が未作成でも station の `defaultProgramTemplateId` から runtime と同じ既定 policy を合成して評価します。指定した時だけ request 内の draft payload を優先します。`templateDraft` は preview 対象 station に対して有効な `GLOBAL` または同一 station の `STATION` scope だけを受け付け、inactive template は preview でも選択しません。

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

`fallbackApplied` は template 未解決時の固定比率 fallback だけでなく、slot ごとの `fallbackSegmentTypes` や最小代替 `TALK` への縮退が発生した場合も `true` になります。`validationWarnings` には `NO_RULE`, `LEGACY_RATIO_FALLBACK`, `SLOT_FALLBACK` などの安全な要約だけを返します。

### 6.8 `GET /api/settings`

`config.json` の現在値に `version` を付けて返し、Web/Native が同じ契約で設定を表示できるようにします。サーバーは起動時に `schemaVersion` も検証し、一致しない場合には `400` を返します。

`playout` は全局共通の上限値として扱い、station ごとの `pre_generation_policy` や queue warmup/refill の深さはこの範囲内に収めます。`targetReadyCount` と `minReadyDurationMs` は現在のサーバー実装で既に利用されている基本項目で、`minimumReadyCount`, `maxPreparedDurationMs`, `maxPreparedBlocks`, `scriptAheadCount`, `ttsAheadCount`, `musicAheadCount`, `idlePrefetchEnabled` も `/api/settings` と `config.json` の往復対象として実装済みです。`scriptAheadCount`, `ttsAheadCount`, `musicAheadCount` は段階的に runtime 反映されており、`idlePrefetchEnabled` は「manual play を待っている間に、`minimumReadyCount` / `minReadyDurationMs` を満たした後も extra prefetch を続けるか」を制御するフラグとして first step 反映済みです。

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
  "programming": {
    "defaultPlanningHorizonMinutes": 20,
    "legacyRatioFallback": true,
    "seedImportRef": "file:./data/config/programming-seed.json"
  },
  "providers": {
    "llm": {
      "defaultProvider": "ollama",
      "fallbackProviders": [],
      "providers": {
        "ollama": {
          "baseUrl": "http://127.0.0.1:11434",
          "healthPath": "/api/tags",
          "timeoutMs": 120000,
          "capabilities": ["SCRIPT_GEN"],
          "adapter": "OLLAMA",
          "defaultModelProfileId": "qwen3:8b"
        }
      }
    },
    "tts": {
      "defaultProvider": "irodori",
      "fallbackProviders": ["voicevox"],
      "providers": {
        "irodori": {
          "baseUrl": "http://127.0.0.1:8088",
          "healthPath": "/health",
          "timeoutMs": 180000,
          "capabilities": [
            "TTS_GEN",
            "OPENAI_AUDIO_SPEECH",
            "IRODORI_TTS",
            "VOICE_CLONE",
            "STYLE_EMOJI",
            "LONG_TEXT_CHUNKING",
            "CHUNK_SSE_AVAILABLE"
          ],
          "adapter": "IRODORI_OPENAI_TTS",
          "apiKeyRef": "env:IRODORI_TTS_API_KEY",
          "defaultModelProfileId": "irodori-tts"
        },
        "voicevox": {
          "baseUrl": "http://127.0.0.1:50021",
          "healthPath": "/version",
          "timeoutMs": 5000,
          "capabilities": ["TTS_GEN", "VOICEVOX"]
        }
      }
    },
    "musicGen": {
      "defaultProvider": "ace-step",
      "fallbackProviders": [],
      "providers": {
        "ace-step": {
          "baseUrl": "http://127.0.0.1:8001",
          "healthPath": "/health",
          "timeoutMs": 10000,
          "capabilities": ["MUSIC_GEN", "ACE_STEP", "JAPANESE_LYRICS"],
          "adapter": "ACE_STEP",
          "apiKeyRef": "env:ACESTEP_API_KEY",
          "defaultModelProfileId": "ace-ja-fast",
          "modelProfiles": {
            "ace-ja-fast": {
              "model": "acestep-v15-turbo",
              "lmModel": "acestep-5Hz-lm-0.6B",
              "thinking": true,
              "lyricsLanguage": "ja",
              "lyricsTransliterationMode": "native",
              "outputFormat": "wav",
              "maxDurationSeconds": 120
            },
            "ace-ja-xl-fast": {
              "model": "acestep-v15-xl-turbo",
              "lmModel": "acestep-5Hz-lm-1.7B",
              "thinking": true,
              "lyricsLanguage": "ja",
              "lyricsTransliterationMode": "native",
              "outputFormat": "wav",
              "maxDurationSeconds": 240
            }
          }
        }
      }
    }
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

クライアントから送られた `version` と `schemaVersion` を現在の `config.json` と照合し、`version` は楽観ロック、`schemaVersion` は契約互換性確認に使います。`server`, `paths`, `playout`, `cache`, `programming`, `providers`, `security`, `features` を受け付け、機密値は `env:`/`file:` 参照の形でそのまま保持します。`paths` は `dataRoot`, `musicLibrary` に加え、Irodori 参照音声向けの `voiceReferenceRoot` を持てます。`playout` は先行生成の深さと内部準備量の上限を、`cache` は内部保存サイズ、再利用範囲、retention/eviction の上限を決めます。`programming` は planning の既定値と legacy fallback の土台設定を保持し、`providers` は種別ごとの `defaultProvider`, `fallbackProviders`, endpoint map を一括更新します。`generated_asset.cache_key` と `generated_asset.reuse_scope` はこの設定と組み合わせて cache hit 判定に使い、retention/eviction job は `expires_at`, 種別ごとの max bytes, `cleanupBatchSize` を参照します。

```json
{
  "version": 4,
  "schemaVersion": "2026-04",
  "server": { ... },
  "paths": { ... },
  "playout": { ... },
  "cache": { ... },
  "programming": { ... },
  "providers": { ... },
  "security": { ... },
  "features": { ... }
}
```

`cache` の `scriptMaxBytes`, `ttsMaxBytes`, `musicMaxBytes` は各 asset 種別ごとの保存上限を表します。`scriptReuseScope`, `ttsReuseScope`, `musicReuseScope` は `DISABLED`, `SESSION`, `STATION`, `GLOBAL`, `ARCHIVE_ONLY` のいずれかを取り、再利用候補の検索範囲を制御します。`cleanupBatchSize` は eviction job の一回あたり処理量です。eviction は参照整合性を壊さないため DB record は残し、payload file を削除したうえで `byte_size=0`, `cache_key=null`, `reuse_scope=DISABLED` とし、短い eviction metadata だけを残します。

`playout.minimumReadyCount` は `playout.targetReadyCount` 以下、`playout.maxPreparedDurationMs` は `playout.minReadyDurationMs` 以上で指定する必要があります。`maxPreparedBlocks`, `scriptAheadCount`, `ttsAheadCount`, `musicAheadCount` は 0 以上で受け付け、`idlePrefetchEnabled` は manual play 待機中に安全バッファ達成後の extra prefetch を許可するフラグです。

`programming.defaultPlanningHorizonMinutes` は 1 以上、`programming.legacyRatioFallback` は最終 fallback 許可フラグ、`programming.seedImportRef` は `file:` / `env:` を含む参照文字列です。`providers.*.providers.{key}` は `baseUrl`, `healthPath`, `timeoutMs`, `capabilities` を持ち、必要に応じて `adapter`, `apiKeyRef`, `defaultModelProfileId` を持ちます。`providers.musicGen.providers.{key}` は追加で `modelProfiles` を持ちます。LLM の `adapter` は `OLLAMA` または `OPENAI_COMPATIBLE` を必須とし、`defaultModelProfileId` は実 Provider へ送る model 名として必須です。LLM の timeout 未指定時は、Ollama のモデルロードを含む初回生成を考慮して 120000 ms に補正します。MusicGen の `adapter` は `MUSICGEN_WORKER` または `ACE_STEP`、TTS の `adapter` は `VOICEVOX` または `IRODORI_OPENAI_TTS` を使います。`apiKeyRef` は空値または `env:` / `file:` 参照だけを許可します。Web 初期実装では provider key の追加削除より先に既存 endpoint の編集と default/fallback 切替を優先します。

Irodori-TTS は OpenAI互換 `POST /v1/audio/speech` を使う内部 provider であり、外部公開 API として `/v1/audio/speech` を SeedShiftRadio から再公開しません。Web / C# Client は従来どおり `QueueItem.assetUrl`, `/api/assets/audio/{assetId}.wav`, `SpeechDirective.voiceHint` を利用します。

`modelProfiles` の各要素は `model`, `lmModel`, `thinking`, `lyricsLanguage`, `lyricsTransliterationMode`, `outputFormat`, `maxDurationSeconds` を持ちます。`lyricsTransliterationMode` は `native`, `kana`, `romaji`、`outputFormat` は v1 の `/api/assets/audio/{assetId}.wav` 契約に合わせて `wav` または `wav32` を受け付けます。未知 profile id や profile 上限を超える duration は Server 側 validation / 正規化で拒否または補正します。

Web の `/settings` Import / Export は専用 API を追加せず、`GET /api/settings` の取得結果から `SettingsUpdateRequest` 互換 JSON を export し、import した JSON を draft に反映してから既存の `PUT /api/settings` で保存します。Export JSON には `updatedAt` と `configPath` を含めません。Import 時は `schemaVersion` の一致を Web 側でも確認し、`version` は現在の保存済み設定に合わせてから送信します。最終的な整合性検証と書き込み対象の固定は Server 側の `PUT /api/settings` が担います。

### 6.10 `POST /api/settings/test-connections`

Provider に対する接続テストを一括実行し、種別ごとの `status` を返します。レスポンスは `checkedAt` と `providers` map を持ち、各 payload は `/api/monitor/summary`, `/api/health`, SSE `provider.health.changed` と同一形式です。Web の `/settings` では未保存 draft ではなく、直前に保存された `config.json` を対象にテストします。`apiKeyRef` の環境変数または file が未設定、空、読み取り不能の場合は、匿名 health endpoint が成功しても対象 Provider を `DOWN / PROVIDER_AUTH_FAILED` として返します。

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

生成済み audio asset がある場合は `generated_asset.storage_path` を優先して `audio/wav` で返し、見つからない場合のみ `features.streaming.placeholderEnabled` に従って placeholder を返します。TALK / LETTER の server-side TTS は `providers.tts.defaultProvider` から `fallbackProviders` の順に VOICEVOX または Irodori OpenAI TTS を試行し、成功した WAV を `queue_item.assetId` / `assetUrl` と `generated_asset` に紐づけます。TTS audio metadata は本文を含まず、`normalizedTextHash`, `providerKey`, `adapter`, `voiceHint`, `speakerKey` / `voiceId`, `fallbackErrorCode` などの短い値に限定します。

### 6.12 Provider Health

`/api/monitor/summary` と `/api/health` は station/queue 情報に加えて、最新の provider health snapshot を `providerHealth` map として返します。key は `llm`, `tts`, `musicGen` で、各値は `ProviderHealthPayload` です。`status` は `UP/DEGRADED/DOWN`、`lastCheckedAt`、`responseTimeMs`、`message`、`capabilities`、`metadata` を含み、SSE `provider.health.changed` でも同じ map 形式を送るためクライアントが再利用しやすくなっています。ACE-Step では `metadata` に `adapter`, `defaultModelProfileId`, `modelProfileIds`, `queueSize`, `queuedJobs`, `runningJobs`, `averageJobSeconds`, `defaultModel`, `models` などの短い状態値だけを入れます。VOICEVOX では `adapter`, `responseFormat`, `streamingSupported=false` を返します。Irodori-TTS-Server 自体は `stream_format=sse` を提供しますが、現行 SeedShiftRadio adapter は完成 WAV だけを扱うため `streamingSupported=false` を返します。`capabilities` の `CHUNK_SSE_AVAILABLE` は upstream 能力、`streamingSupported` は現行 adapter の有効化状態を表します。Irodori-TTS の metadata には `adapter`, `model`, `responseFormat`, `chunkingEnabled`, `voiceRefStatus`, `models` のような診断値だけを入れ、参照音声の path、個人名、本文、秘密値は含めません。

```json
{
  "providerType": "musicGen",
  "status": "DEGRADED",
  "lastCheckedAt": "2026-03-20T09:12:00Z",
  "responseTimeMs": 312,
  "message": "FastAPI worker がタイムアウト",
  "capabilities": ["ace-step:fast"],
  "metadata": {
    "adapter": "ACE_STEP",
    "defaultModelProfileId": "ace-ja-fast",
    "queueSize": 4,
    "runningJobs": 2,
    "defaultModel": "acestep-v15-turbo"
  }
}
```

### 6.13 Music Generation Job Contract

音楽生成ジョブの submit / poll / download は Server 内部の `MusicGenerationProvider` 契約で扱い、Web / C# Client は通常 `queue_item`, `generated_asset`, `provider_job`, `/api/assets/audio/{assetId}.wav`, `/api/monitor/summary` を通じて状態を参照します。外部公開 API として prompt / lyrics 本文を返さない方針を維持します。

`MusicGenerationRequest` は `purpose`, `mode`, `prompt`, `lyrics`, `lyricsLanguage`, `durationSeconds`, `bpm`, `keyScale`, `timeSignature`, `seed`, `modelProfileId`, `outputFormat` を持ちます。`lyricsLanguage=ja` は ACE-Step で `vocal_language=ja` に写像され、既定 profile は `ace-ja-fast` です。

`MusicGenerationJob` 相当の状態は `provider_job` と監視 DTO へ集約し、`queued/running/succeeded/failed/canceled`, `providerTaskId`, `assetId`, `model`, `lmModel`, `seed`, `duration`, `errorCode` を短い metadata として扱います。prompt / lyrics / letter body / radioName / API key は API response、SSE、標準ログへ生で出しません。

`provider_job.status` は `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` に限定し、`DEGRADED` は Provider health と playout state だけで表します。Provider 失敗時の `errorCode` は共通の `PROVIDER_UNREACHABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_REJECTED`, `PROVIDER_RESOURCE_EXHAUSTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED`、または TTS 固有の `VOICE_REF_NOT_FOUND`, `VOICE_CONSENT_REQUIRED` とします。外部 Provider または Worker の未知 code は `PROVIDER_BAD_RESPONSE` へ正規化します。

### 6.14 MonitorSummary

`GET /api/monitor/summary` は `ProviderHealth` に加えて、READY queue の合計 duration、generated asset cache の集約値、archive pool / replay 集約値、`provider_job` から復元した `runningJobs` / `recentErrors`、SSE 履歴から抽出した `auditEvents` を返します。`cache` と `archive` は prompt や本文を含まず、件数、byte 数、hit/replay rate などの数値だけを返します。`runningJobs` は `RUNNING` の provider job、`recentErrors` は `FAILED` の provider job を新しい順で返し、`auditEvents` は `radio.status.changed`, `queue.updated`, `program.changed`, `subtitle.updated`, `provider.health.changed`, `buffer.warning`, `letter.updated`, `provider.job.*` を要約したものです。

```json
{
  "sessionId": "playout-20260320-01",
  "stationId": "station-night",
  "state": "PLAYING",
  "bufferReadyCount": 2,
  "queueReadyDurationMs": 90000,
  "pendingLetterCount": 3,
  "degraded": false,
  "cache": {
    "checkedAt": "2026-03-20T09:14:00Z",
    "assetCount": 42,
    "byteSize": 128450560,
    "cacheHitCount": 7,
    "cacheHitRate": 0.1428,
    "expiredAssetCount": 2,
    "byType": {
      "SCRIPT": { "assetType": "SCRIPT", "assetCount": 12, "byteSize": 102400, "cacheHitCount": 1, "cacheHitRate": 0.0769 },
      "AUDIO": { "assetType": "AUDIO", "assetCount": 20, "byteSize": 28450160, "cacheHitCount": 2, "cacheHitRate": 0.0909 },
      "MUSIC": { "assetType": "MUSIC", "assetCount": 10, "byteSize": 99998000, "cacheHitCount": 4, "cacheHitRate": 0.2857 }
    }
  },
  "archive": {
    "eligibleArchiveCount": 3,
    "totalArchiveCount": 4,
    "archiveReplayCount": 3,
    "totalPlaybackCount": 12,
    "archiveReplayRate": 0.25
  },
  "runningJobs": [
    {
      "id": "provider-job-running-001",
      "jobType": "MUSIC_GEN",
      "providerType": "MUSIC",
      "providerKey": "ace-step",
      "queueItemId": "queue-001",
      "status": "RUNNING",
      "externalRef": "worker-job-001",
      "errorCode": null,
      "startedAt": "2026-03-20T09:00:00Z",
      "endedAt": null
    }
  ],
  "recentErrors": [
    {
      "id": "provider-job-failed-001",
      "jobType": "TTS_GEN",
      "providerType": "TTS",
      "providerKey": "voicevox",
      "queueItemId": "queue-002",
      "status": "FAILED",
      "externalRef": "worker-job-002",
      "errorCode": "PROVIDER_TIMEOUT",
      "startedAt": "2026-03-20T09:10:00Z",
      "endedAt": "2026-03-20T09:11:00Z"
    }
  ],
  "auditEvents": [
    {
      "id": "12",
      "eventType": "buffer.warning",
      "occurredAt": "2026-03-20T09:14:00Z",
      "summary": "session=playout-20260320-01, ready=1"
    }
  ]
}
```

`auditEvents` は監査用の要約であり、レター本文全文やプロンプト全文は含めません。

### 6.15 Asset Consistency

`GET /api/monitor/assets/consistency` は管理者向けの read-only 検査 API として、`generated_asset` の DB metadata と `dataRoot/assets/{audio,scripts,music}` 配下の payload file の不整合を返します。`byteSize=0` の asset は eviction 済み payload として扱い、missing file には数えません。

Response:

```json
{
  "checkedAt": "2026-03-20T09:30:00Z",
  "assetCount": 42,
  "checkedAssetCount": 36,
  "missingFileCount": 1,
  "byteSizeMismatchCount": 1,
  "contentHashMismatchCount": 1,
  "orphanFileCount": 2,
  "unreadableFileCount": 0,
  "issueCount": 5,
  "issuesTruncated": false,
  "issues": [
    {
      "issueType": "MISSING_FILE",
      "assetId": "asset-missing",
      "assetType": "AUDIO",
      "storagePath": "assets/audio/asset-missing.wav",
      "expectedByteSize": 123456,
      "actualByteSize": null,
      "message": "payload file が通常ファイルとして存在しません。"
    }
  ]
}
```

- `issueType` は `MISSING_FILE`, `BYTE_SIZE_MISMATCH`, `CONTENT_HASH_MISMATCH`, `ORPHAN_FILE`, `UNREADABLE_FILE` のいずれか
- `storagePath` は `dataRoot` からの相対パスまたは data root 外を示す短い表示用パスであり、絶対パスは返さない
- `issues` は最大 100 件のサンプルとし、超過時は `issuesTruncated=true` を返す
- raw metadata、prompt、lyrics、letter body、radioName、API key、管理トークンは返さない

### 6.16 Management Dashboard / Pre-generation

`GET /api/management/dashboard` は管理画面トップ向けの集約 API とする。
既存 `MonitorSummaryResponse` を `system` として再利用し、局数、有効局数、番組テンプレート数、局別 `StationContentInventory`、直近 10 件の `PreGenerationResponse` を返す。
局別 asset 数量は `generated_asset.queue_item_id -> queue_item.program_block_id -> program_block.station_id` をたどり、payload を保持する `byte_size > 0` の record を `SCRIPT`, `AUDIO`, `MUSIC` ごとに集計する。prompt、lyrics、台本本文、レター本文、radioName、秘密値は返さない。

`GET /api/management/stations/{stationId}/content` は 1 局分の `StationContentInventory` を返す。
`programCount` は局の全 `program_block`、`preGeneratedProgramCount` は `playout_session.purpose=PRE_GENERATION` の block、`generatedAssetCount` / `generatedAssetBytes` と種別別件数はその block から生成された asset を表す。

`POST /api/management/stations/{stationId}/pre-generations` は次の request を受け付け、`202 Accepted` で `PreGenerationResponse` を返す。

```json
{
  "programTemplateId": "tmpl-night-regular",
  "targetProgramCount": 2,
  "includeSpeech": true,
  "includeMusic": true
}
```

- `programTemplateId` は省略可能。省略時は局の保存済み `StationProgrammingPolicy` と現在時刻からテンプレートを解決する
- 指定時は有効な `GLOBAL` または同一局の `STATION` scope template だけを許可する
- `targetProgramCount` は 1 から 10
- `includeSpeech=false` と `includeMusic=false` の同時指定は Server が `400 VALIDATION_ERROR` で拒否し、Web UI でも送信前に防止する
- Server は `playout_session.purpose=PRE_GENERATION` のオフエアセッションを新規作成し、ライブの最新 session、radio status、現在番組、再生 queue を変更しない
- script / TTS は JobRunr の事前生成 job 内で materialize し、`MUSIC_AI` は既存 `GenerateMusicJob` へ非同期投入する
- `PreGenerationRequestStatus` は `QUEUED`, `RUNNING`, `MATERIALIZED`, `FAILED`。`MATERIALIZED` は番組 block と queue item の作成、および必要な MusicGen job 投入が完了した状態で、全 MusicGen job の成功を意味しない
- MusicGen の成否は既存 `provider_job`, `/api/monitor/summary`, 局別 asset 集計で確認する
- Provider 起因の例外を特定できた場合は `PROVIDER_TIMEOUT`, `PROVIDER_UNREACHABLE`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_RESOURCE_EXHAUSTED` などの共通 code を `errorCode` に保存する。分類できない内部例外だけを `PRE_GENERATION_FAILED` とし、例外本文や生成入力は返さない

### 6.17 Operational Logs

`GET /api/monitor/logs?limit=100` は、再起動後も参照できる管理者向けの構造化運用ログを新しい順で返す。
`limit` は 1 から 200 に丸める。
初期対象は Provider job の `queued/running/succeeded/failed` と事前生成失敗で、Provider job の外側の transaction が rollback しても診断 record は独立 transaction で保持する。
保持期間は 30 日とし、新しい event の保存時に期限切れ record を削除する。

各要素は `id`, `level`, `category`, `eventType`, `sourceId`, `correlationId`, `providerType`, `providerKey`, `errorCode`, `message`, `occurredAt` を持つ。
`message` は分類済み code から生成する安全な運用文だけとし、prompt、lyrics、台本本文、Provider 応答本文、letter body、radioName、API key、管理トークンを保存・返却しない。
生のコンテナーログや例外 message をこの API で返さない。

## 7. SSE仕様

Endpoint:

- `GET /api/stream/events`

Event 種別:

| Event | Payload | 用途 |
|---|---|---|
| `connected` | `{ "connectedAt": "..." }` | 初回接続確認。履歴再送対象ではない |
| `radio.status.changed` | `RadioStatus` | 再生状態更新 |
| `queue.updated` | `QueueSnapshot` | キュー差し替え・Ready数更新 |
| `program.changed` | `ProgramBlockSummary` | 現在番組 block の切替・更新 |
| `subtitle.updated` | `SubtitlePayload` | 字幕更新 |
| `provider.health.changed` | `Record<string, ProviderHealthPayload>` | Provider health snapshot 更新 |
| `provider.job.queued/running/succeeded/failed` | `ProviderJobPayload` | 生成ジョブ状態。本文や秘密値は含めず id/status/errorCode のみ |
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

`PROVIDER_UNAVAILABLE` の `details` は `providerErrorCode` だけを返します。`providerErrorCode` は共通 Provider error code または TTS 固有 error code のいずれかとし、Provider 応答本文、endpoint、秘密値、prompt、lyrics、letter body、radioName は含めません。未知の外部 code は `PROVIDER_BAD_RESPONSE` へ正規化してから返します。

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

- OpenAPI JSON は `springdoc-openapi` が `/api/openapi` に生成する
- 管理 API は `components.securitySchemes.adminToken` と operation 単位の `security` で `X-Admin-Token` 必須を表す
- DTO は Server / Client 両方で再利用しやすいよう JSON naming を固定する
- `ApiContractTests` は生成 JSON を正規化した SHA-256 snapshot、Spring MVC handler、認証マトリクス、本書の表を比較する
- 現在の OpenAPI snapshot SHA-256 は `3e7971b919477a4f9e7099577b53a91ea75e4d498d9897ff46f3afa48efca84f` とする
- 意図した契約変更では `src/test/resources/contracts/api-auth-matrix.json`、`src/test/resources/contracts/openapi.sha256`、本書を同じ change set で更新する
- GitLab CI の `api-contract` job は `./gradlew apiContractTest` を実行し、endpoint、DTO schema、認証区分の drift を検出する
- 破壊的変更が必要な場合のみ `/api/v2` を追加する
