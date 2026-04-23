# AIローカルラジオアプリ Web画面設計書

## 1. 目的

本書は MVP の Web UI を設計する。ラジオ画面を主役としつつ、レター、設定、監視の導線を整理する。

## 2. 採用方針

- フレームワークは `Next.js App Router + TypeScript` とする
- 初期描画は SSR を使い、再生状態や字幕は Client Component で更新する
- サーバー状態取得は `TanStack Query`、画面内の瞬間的なUI状態は `Zustand` を使う
- 音声再生は `HTMLAudioElement` を基本とし、必要時のみ `howler.js` を導入する

## 3. 画面一覧

| Route | 画面 | 主目的 |
|---|---|---|
| `/` | ラジオ画面 | 局選択、再生、字幕、キュー表示 |
| `/letters` | レター画面 | 投稿、一覧、状態確認 |
| `/settings` | 設定画面 | Provider 設定、局管理、番組管理、先行生成・再放送・キャッシュ設定、接続テスト |
| `/monitor` | 監視画面 | Provider health, worker status detail, buffer, generated asset cache, running jobs, recent errors, audit events |

## 4. レイアウト方針

- PC では 3 カラム構成を標準とする
- モバイルでは 1 カラム化し、`NowPlaying` と主要操作を最上部へ固定する
- ラジオ画面は「操作」「現在」「次」を常に同時把握できる配置を優先する

## 5. ラジオ画面

### 5.1 主要コンポーネント

| 領域 | 内容 |
|---|---|
| Header | アプリ名、現在局、接続状態 |
| Tuner Panel | 周波数ダイヤル、前後スキャン、局一覧 |
| Playback Panel | 再生/停止、音量、再生モード、NowPlaying |
| Program Panel | 現在番組タイトル、適用テンプレート、次スロット |
| Subtitle Panel | 現在発話テキスト、発話者名 |
| Queue Panel | 次に流れるセグメント、状態、生成中表示、live/cache/replay タグ |
| Provider Panel | LLM/TTS/MusicGen の health と buffer 状態 |

### 5.2 画面状態

| 状態 | 表示方針 |
|---|---|
| `IDLE` | 局未選択。推薦局と最近選んだ局を表示 |
| `PREPARING` | スケルトン表示と「先読み中」を表示 |
| `PLAYING` | 音声再生、字幕更新、Queue をライブ更新 |
| `DEGRADED` | 代替再生中バナーを表示 |
| `ERROR` | 再生停止と再試行導線を表示 |

### 5.3 音声プレイヤー挙動

- Queue の先頭 `READY` セグメントを順次再生する
- 次セグメントは再生終了 3 秒前を目安に preload する
- Tune 時は現在音声を即時停止せず、フェードアウト後に新局へ切り替える
- 再生エラー時は 1 回だけ同一 asset を再試行し、失敗なら次候補へ進む
- `RadioStatus` や `program.changed` を使って、局名と番組名を分けて表示する

## 6. レター画面

### 6.1 構成

- 投稿フォーム
- 投稿完了トースト
- 自分が送ったレターの簡易一覧
- 放送採用履歴

### 6.2 入力項目

- station
- radioName
- subject
- body

### 6.3 UIルール

- 本文は 1000 文字以内を初期上限とする
- `radioName` は直前値を LocalStorage に保持する
- 投稿時は二重送信防止を行う
- 公開側は投稿完了トーストと、この端末で送ったレターの簡易一覧を表示する
- 放送採用履歴は `POST /api/letters/public/history` の最小要約 DTO を使い、本文や返信は公開しない

## 7. 設定画面

### 7.1 セクション

- LLM
- TTS
- MusicGen
- Stations
- Program Templates
- Programming Rules
- Programming Preview
- Pre-generation / Replay
- Paths
- Cache
- Security
- Import / Export
- Connection Test

### 7.2 操作ルール

- 変更は一括保存とする
- 危険な項目は `localhost 以外へ bind` などの注意表示を出す
- API キー自体は平文表示せず、参照先のみ表示する
- 番組テンプレート編集では `HARD` / `SOFT` の違いを明示し、保存前に Preview を実行できるようにする
- station 基本情報編集では `PUT /api/stations/{id}` を使い、局 ID は読み取り専用、`version` 楽観ロック、dirty/reset/save、必須項目、周波数下限を UI でも確認する
- 局ごとの番組編成設定では `preGeneration`, `replay`, `composition` を 1 画面で編集できるようにし、再放送比率と番組構成比は slider と数値入力の両方を許容する
- 局ごとの番組編成 policy 編集では `GET/PUT /api/stations/{id}/programming` を使い、`version` 楽観ロック、dirty 表示、composition 合計 100%、enabled 時 rules 必須、Replay の LETTER 除外を UI でも確認する
- `Cache` セクションでは script / TTS / music の保持上限サイズ、保存日数、再利用範囲を個別に確認できるようにする
- `Import / Export` は `/api/settings` の `PUT` と同じ JSON 形に揃え、`updatedAt` や `configPath` など response metadata は保存対象にしない
- Import は即時保存せず draft に読み込み、内容確認後に `Save Settings` で既存の settings validation を通す
- Export 前にも `apiKeyRef` と `adminTokenRef` が `env:` / `file:` 参照であることを検証し、raw secret らしい draft は JSON download しない
- `Connection Test` の provider health 表示は metadata と message を Web 表示層で redaction し、prompt / lyrics / letter body / radioName / secret / raw response をそのまま描画しない
- 実行中の番組 block へ影響する変更は「次の番組から反映」と明示する

### 7.3 初期実装範囲

- 第一段の `/settings` は `server`, `paths`, `playout`, `cache`, `programming`, `providers`, `security`, `features` を 1 画面で一括編集する
- `providers` は `defaultProvider`, `fallbackProviders` に加え、既存 endpoint の `baseUrl`, `healthPath`, `timeoutMs`, `capabilities` を編集できるようにする
- `Test Connections` は未保存 draft ではなく、保存済み設定に対して実行する
- 管理トークンがない場合は導線を非表示にし、直接開いた時は管理画面であることを案内する
- `Import / Export` は Web 側で JSON download / file import として実装し、専用 API は増やさず既存の `GET /api/settings` と `PUT /api/settings` を使う
- Import 時は `schemaVersion` の一致を確認し、`version` は現在の保存済み設定へ合わせる。`apiKeyRef` と `adminTokenRef` は `env:` / `file:` 参照だけ受け付ける
- `Stations` は概要表示に加えて station 基本情報と station programming policy の編集保存を実装する。`Program Templates` は read 中心、`Programming Preview` は保存済み policy に対する preview とし、station 作成/複製、template 本体編集、未保存 draft preview は後続フェーズで拡張する

## 8. 監視画面

- Provider 状態
- バッファ残量
- READY queue の合計 duration
- 現在番組 block と template version
- generated asset cache の保存量、期限切れ候補、種別別 hit rate
- archive pool 件数と archive replay rate
- musicGen worker の queue size, queued/running jobs, average job seconds, adapter, default model / profile
- 進行中ジョブ
- 直近エラー
- 直近 10 件の監査イベント

監視画面は MVP では簡易版とし、全文ログ参照ではなくサマリ表示を原則とする。`provider_job` の running / failed 一覧と SSE 履歴由来の audit events を併記し、詳細な全文監査ログではなく要約を出す。
- summary は定期 refresh し、provider status, generated asset cache, archive metrics, job, audit event を画面内で絞り込めるようにする
- worker status detail は `providerHealth.metadata` のうち `adapter`, `defaultModelProfileId`, `modelProfileIds`, `queueSize`, `queuedJobs`, `runningJobs`, `averageJobSeconds`, `defaultModel`, `models`, `statsStatus`, `modelsStatus` の短い状態値だけを整形して表示し、prompt / lyrics / letter body / radioName / secret は出さない
- `providerHealth.message`, `baseUrl`, `provider_job.externalRef`, audit `summary` は分類済みの短い表示に限り、秘密値や本文らしい key-value / credential URL は Web 側でも `[redacted]` に置き換える

## 9. 状態管理

| 種別 | 保存先 |
|---|---|
| Stations, Status, Queue, CurrentProgram, Letters, Health | Server + TanStack Query |
| 再生中 itemId, UI 一時状態, モーダル開閉 | Zustand |
| 最後に聴いた局、音量、表示タブ、 radioName | LocalStorage |

## 10. リアルタイム更新

- 初期表示は `GET /api/radio/status` と `GET /api/radio/queue` で取得する
- 以後は SSE で差分更新する
- 切断時は指数バックオフで再接続する
- `subtitle.updated` を受けたら現在の発話のみ差し替える

## 11. アクセシビリティ

- キーボードで周波数スキャンと再生操作を可能にする
- 主要ボタンにショートカットを付与する
- 字幕はスクリーンリーダー向けに `aria-live="polite"` を利用する
- 色だけで状態を表現せず、ラベルとアイコンも併用する

## 12. 実装メモ

- ラジオ画面の再生状態は Client Component に集約する
- `useEffectEvent` を用いて音声イベント購読処理を安定化する
- 過剰なグローバル状態は避け、Server State と UI State を分離する
- `/monitor` は必要に応じて管理トークンがある時だけ導線を表示する
- `/settings` の Import / Export helper は Vitest で、metadata 除外、schemaVersion mismatch、Import/Export 双方の secret 参照検証、未登録 fallback provider を確認する
- `/settings` / `/monitor` の provider health 表示 helper は Vitest で、metadata/message/object fallback に prompt / lyrics / letter body / radioName / secret が混ざっても露出しないことを確認する
- `/settings` の station 基本情報更新は API client test で、`X-Admin-Token`、JSON body、URL encode を確認する
- `/settings` の station programming policy 更新は API client test で、`X-Admin-Token`、JSON body、URL encode を確認する
- Playwright E2E では `/` の `Tune -> Play -> audio event`、`/letters` の `投稿 -> ローカル履歴 -> 公開採用履歴`、SSE の `subtitle.updated` と reconnect 時 `Last-Event-ID` を mock API / mock stream / audio stub で確認する
- E2E selector は role と label を基本にしつつ、接続状態、queue item、audio console、投稿 toast、ローカル履歴、採用履歴など揺れやすい要素だけ `data-testid` を補助利用する
