---
name: seedshift-radio-playout-contracts
description: Use when working on SeedShiftRadio playback state, queue lifecycle, tune/play/stop/playback-events APIs, SSE event flows, SpeechDirective delivery, client capabilities, or C# native compatibility. This skill helps keep `playout_session`, `queue_item`, `ProgramBlock`, and shared playback contracts aligned with `doc/02`, `doc/05`, and `doc/10`.
---

# SeedShiftRadio Playout Contracts

## 主に使う役割

- `architect`
- `server`
- `qa`

## 最初に見るドキュメント

- API, DTO, SSE 契約: `doc/02_API仕様書.md`
- セッション状態と queue 制御: `doc/05_プレイアウト・キュー制御設計書.md`
- Native client 互換: `doc/10_CSharpネイティブクライアント連携設計書.md`
- 運用と試験: `doc/11_運用・監視・セキュリティ・テスト設計書.md`

## 現物確認の起点

- 再生 API と状態遷移: `src/main/java/com/seedshiftradio/radio`
- SSE 配信と再送: `src/main/java/com/seedshiftradio/stream`
- 回帰確認: `src/test/java/com/seedshiftradio/radio`, `src/test/java/com/seedshiftradio/stream`

## ワークフロー

1. `Tune`, `Play`, `Stop`, `playback-events`, `next-speech-directive` のどれが変わるかを先に固定する
2. `doc/02_API仕様書.md` の DTO と `doc/05_プレイアウト・キュー制御設計書.md` の状態表を必ず突き合わせる
3. `sessionId` と `itemId` の整合、同一 session の `PLAYING` item 1 件制約、`PREPARING` 応答を先に確認する
4. `SpeechDirective` と `client_capabilities` を Web/Native 共通契約として扱い、クライアント別最適化は `voiceHint` 解決に閉じ込める
5. 変更後は `radio.status.changed`, `queue.updated`, `program.changed`, `provider.health.changed` のどれに影響するかを確認する

## ガードレール

- `Server` を `playout_session`, `queue_item`, `program_block` の正本にする
- `POST /api/radio/tune` は `resumePlayback=true` でも初回応答を `PREPARING` のまま返す
- `POST /api/radio/playback-events` は補助イベントとして扱い、正本の進行がイベント欠落で崩れないようにする
- `PLAYBACK_STOPPED` と `stop` は current item の扱いを統一し、前状態不正は `409 CONFLICT` にそろえる
- `SpeechDirective` と `QueueItem.playbackMode` の互換を壊さず、将来の `CLIENT_TTS` を残す

## 完了前チェック

- `doc/02_API仕様書.md`, `doc/05_プレイアウト・キュー制御設計書.md`, 必要なら `doc/10_CSharpネイティブクライアント連携設計書.md` を更新したか
- Queue と session の不変条件を test で押さえたか
- `Last-Event-ID` 再送や `provider.health.changed` への影響を見たか
