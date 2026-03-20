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
- 設定更新と監視 API は `X-Admin-Token` による最小保護を推奨する
- 将来 `Spring Security` を導入しても DTO を崩さない

## 4. 主要DTO

### 4.1 StationSummary

```json
{
  "id": "station-night",
  "name": "Midnight Echo",
  "frequencyMHz": 81.3,
  "genre": "talk",
  "isActive": true
}
```

### 4.2 RadioStatus

```json
{
  "sessionId": "playout-20260320-001",
  "stationId": "station-night",
  "state": "PLAYING",
  "currentItemId": "queue-0012",
  "bufferReadyCount": 2,
  "degraded": false,
  "updatedAt": "2026-03-20T09:00:00Z"
}
```

### 4.3 QueueItem

```json
{
  "id": "queue-0012",
  "type": "TALK",
  "title": "オープニングトーク",
  "playbackMode": "SERVER_AUDIO",
  "assetUrl": "/api/assets/audio/queue-0012.wav",
  "speechDirectiveId": null,
  "durationMs": 28000,
  "status": "READY"
}
```

### 4.4 SpeechDirective

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

### 4.5 ErrorResponse

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
| `GET` | `/stations` | 局一覧取得 |
| `GET` | `/stations/{id}` | 局詳細取得 |
| `POST` | `/clients/capabilities` | クライアント能力申告 |
| `POST` | `/radio/tune` | 局切替 |
| `POST` | `/radio/play` | 再生開始 |
| `POST` | `/radio/stop` | 再生停止 |
| `GET` | `/radio/status` | 現在の再生状態取得 |
| `GET` | `/radio/queue` | 現在キュー取得 |
| `GET` | `/radio/next-segment` | 次の再生候補取得 |
| `GET` | `/radio/next-speech-directive` | Client-side TTS 用指示取得 |
| `POST` | `/radio/playback-events` | クライアント再生イベント通知 |
| `GET` | `/letters` | レター一覧取得 |
| `POST` | `/letters` | レター投稿 |
| `POST` | `/letters/{id}/status` | レター状態更新 |
| `POST` | `/letters/{id}/reply` | レター返信追加 |
| `GET` | `/settings` | 設定取得 |
| `PUT` | `/settings` | 設定更新 |
| `POST` | `/settings/test-connections` | Provider 接続テスト |
| `GET` | `/health` | ヘルス参照 |
| `GET` | `/monitor/summary` | 監視サマリ参照 |

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
  "queueWarmupStarted": true
}
```

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

## 7. SSE仕様

Endpoint:

- `GET /stream/events`

Event 種別:

| Event | Payload | 用途 |
|---|---|---|
| `radio.status.changed` | `RadioStatus` | 再生状態更新 |
| `queue.updated` | `QueueSnapshot` | キュー差し替え・Ready数更新 |
| `subtitle.updated` | `SubtitlePayload` | 字幕更新 |
| `provider.health.changed` | `ProviderHealthPayload` | Provider 異常通知 |
| `buffer.warning` | `BufferWarningPayload` | 先読み不足通知 |
| `letter.updated` | `LetterSummary` | レター一覧反映 |

SSE は `Last-Event-ID` を受け付け、短時間切断時の再購読に備える。

## 8. エラー体系

| Code | HTTP | 意味 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 入力不正 |
| `NOT_FOUND` | 404 | 対象なし |
| `CONFLICT` | 409 | 状態競合 |
| `QUEUE_NOT_READY` | 409 | 次セグメント未生成 |
| `PROVIDER_UNAVAILABLE` | 503 | Provider 利用不可 |
| `ADMIN_AUTH_REQUIRED` | 401 | 管理操作の認証不足 |
| `INTERNAL_ERROR` | 500 | サーバー内部エラー |

## 9. API設計ルール

- レター投稿は `Idempotency-Key` を受け付けて二重投稿を防止する
- 設定更新は楽観ロック用 `version` を含める
- QueueItem は `status` と `playbackMode` を持ち、 Web と Native で共通利用する
- `SpeechDirective` は Native 向けの主契約だが、Web もデバッグ表示に利用できる
- 監視系 API は UI 用の集約 DTO を返し、生ログ全文は返さない

## 10. OpenAPI生成方針

- `/v3/api-docs` を開発時のみ有効化する
- DTO は Server / Client 両方で再利用しやすいよう JSON naming を固定する
- 破壊的変更が必要な場合のみ `/api/v2` を追加する
