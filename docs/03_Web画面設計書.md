# AIローカルラジオアプリ Web画面設計書

## 1. 目的

本書は MVP の Web UI を設計する。ラジオ画面を主役としつつ、レター、設定、監視の導線を整理する。
視覚的アイデンティティ、色、typography、spacing、shape、component state の正規仕様はリポジトリ直下の [DESIGN.md](../DESIGN.md) を参照し、本書は画面構成と挙動の正本として扱う。

## 2. 採用方針

- フレームワークは `Next.js App Router + TypeScript` とし、現行 security baseline は Next.js `15.5.20` 以上とする
- 初期描画は SSR を使い、再生状態や字幕は Client Component で更新する
- サーバー状態取得は `TanStack Query`、画面内の瞬間的なUI状態は `Zustand` を使う
- 音声再生は `HTMLAudioElement` を基本とし、必要時のみ `howler.js` を導入する

## 3. 画面一覧

| Route | 画面 | 主目的 |
|---|---|---|
| `/` | ラジオ画面 | 局選択、再生、字幕、キュー表示 |
| `/letters` | レター画面 | 投稿、一覧、状態確認 |
| `/settings` | 管理ダッシュボード | システム全体、Provider、queue、生成 job、局別コンテンツ保有量の把握 |
| `/settings/system` | システム設定 | Server 待受、保存先、管理認証、縮退配信、設定 JSON の入出力 |
| `/settings/providers` | AI・音声接続 | LLM / TTS / MusicGen の接続先、優先順、接続確認 |
| `/settings/playout` | 再生・生成設定 | queue 先読み、生成量、cache、全局共通の編成既定値 |
| `/settings/stations` | 局管理 | 局の作成、複製、基本情報、人格・音声、有効状態 |
| `/settings/programming` | 番組編成 | 局別ポリシー、ProgramTemplate、ProgramRule、Programming Preview |
| `/settings/content` | コンテンツ管理 | 局別の番組・台本・音声・曲 asset 台帳とオフエア事前生成 |
| `/monitor` | 監視画面 | Provider health, worker status detail, buffer, generated asset cache, running jobs, recent errors, audit events |
| `/monitor/logs` | 運用ログ | 永続化した生成失敗、Provider error、request/job ID、相関 ID の検索 |

## 4. レイアウト方針

- PC では 12 カラム grid を基準とし、主要領域と補助領域の 7:5 または 8:4、同格情報の 3 等分を画面の情報量に応じて使い分ける
- モバイルでは 1 カラム化し、`NowPlaying` と主要操作を最上部へ置く。header は brand/navigation と live status の 2 段を基本に圧縮し、主要操作を最初の viewport から追い出さない
- ラジオ画面は「操作」「現在」「次」を常に同時把握できる配置を優先する
- 公開画面は余白を広く 1 カード 1 目的、設定・監視画面は editor / health / preview / danger zone を分離した高密度 layout とする
- 主要な navigation、button、input の操作領域は高さ 44px 以上とし、横スクロールは JSON、ログ、表の明示的な scroll container に限定する

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
- Tune 後は SSE が切断・再接続中でも生成完了を見失わないよう、`RadioStatus` と Queue を 3 秒程度の REST polling でも再同期する
- `programBlockId` が未設定の間は `/api/radio/program`、再生可能 item がない間は `/api/radio/next-speech-directive` を呼ばず、準備中の 404 / 409 をブラウザーエラーとして連打しない
- Web の Tune は `resumePlayback=false` を送り、Server が音声出力前に `PLAYING` へ先行しないようにする
- 主操作の「音声を再生」は、クリックの user activation が失われる前に `HTMLAudioElement.play()` を開始し、Server の再生開始 API と並行して完了を確認する。どちらかが失敗した場合はブラウザー音声を停止し、Server も停止状態へ戻して理由を画面へ表示する
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

### 7.1 情報設計

`/settings` は管理画面のトップとして、編集項目ではなくシステム全体の状態を表示する。
`GET /api/management/dashboard` を 15 秒間隔で再取得し、放送状態、Provider health、READY buffer、実行中 job、直近 error、generated asset cache、局別コンテンツ保有量を一画面で把握できるようにする。
詳細な設定変更はカテゴリー別画面へ、局別データの確認と事前生成は `/settings/content` へ遷移させる。
編集画面は Server の保存境界と運用上の判断単位に合わせて分割し、別の責務の設定を同じ長大な画面へ混在させない。

| カテゴリー | Route | 主な設定 |
|---|---|---|
| システム | `/settings/system` | `server`, `paths`, `security`, `features`, Import / Export |
| AI・音声接続 | `/settings/providers` | LLM, TTS, MusicGen の `providers` と Connection Test |
| 再生・生成 | `/settings/playout` | `playout`, `cache`, 全局共通の `programming` 既定値 |
| 局 | `/settings/stations` | Station の作成、複製、基本情報、人格、音声、有効状態 |
| 番組編成 | `/settings/programming` | StationProgrammingPolicy, ProgramTemplate, ProgramRule, Programming Preview |
| コンテンツ | `/settings/content` | StationContentInventory, PreGenerationRequest, 事前生成状況 |

各編集画面の先頭にはカテゴリー名、設定の目的、反映タイミングを日本語で記載する。
カテゴリー navigation には項目名だけでなく、利用者が「何を決めるページか」を判断できる 1 文の説明を常時表示する。
API / JSON の識別子は必要な箇所に残すが、操作名、入力ラベル、空状態、保存状態、注意文は日本語を正本とする。

### 7.2 操作ルール

- 変更は一括保存とする
- 危険な項目は `localhost 以外へ bind` などの注意表示を出す
- API キー自体は平文表示せず、参照先のみ表示する
- 番組テンプレート編集では `HARD` / `SOFT` の違いを明示し、dirty/reset/save、create/duplicate を持つ editor を提供する
- `Programming Preview` は保存済み `ProgramTemplate` / `StationProgrammingPolicy` に加えて、現在の `/settings/programming` で編集中の未保存 draft を request payload として含めて実行できる。draft preview は DB 保存や実行中 block への反映を行わない
- ProgramTemplate editor の `editorialPolicy` / `slotPolicy` は JSON object editor とし、`scope` と `stationId` の整合、`slotId` 一意性、`candidateSegmentTypes` 必須、`targetDurationMs` 下限を UI でも確認する
- ProgramTemplate 保存で `400` や `409` が返った時は create/edit draft を保持したまま、server message と field error を表示して修正継続できるようにする
- ProgramTemplate の create / update が `400` または `409` を返した場合、`/settings` は safe metadata ルールに沿って message / field error を表示し、draft は保持したまま修正や再試行を続けられるようにする
- station 基本情報編集では `PUT /api/stations/{id}` を使い、局 ID は読み取り専用、`version` 楽観ロック、dirty/reset/save、必須項目、周波数下限を UI でも確認する
- station 作成/複製では create mode を別に持ち、`POST /api/stations` を使う。create mode では局 ID を編集可能にし、保存後は新 station を選択状態へ切り替える
- station 複製は局基本情報だけを初期値として引き継ぎ、`id` と `frequencyMHz` は新規候補へ補正する。`STATION` scope template を指す `defaultProgramTemplateId` や policy/rules は自動複製せず、保存後に policy editor で調整する
- station create/duplicate draft に未保存変更があるまま `Close Draft`、`New Station`、`Duplicate Current`、station 切り替えを行う時は、破棄確認を出して accidental discard を防ぐ
- 局ごとの番組編成設定では `preGeneration`, `replay`, `composition` を 1 画面で編集できるようにし、再放送比率と番組構成比は slider と数値入力の両方を許容する
- 局ごとの番組編成 policy 編集では `GET/PUT /api/stations/{id}/programming` を使い、`version` 楽観ロック、dirty 表示、composition 合計 100%、enabled 時 rules 必須、Replay の LETTER 除外を UI でも確認する
- `Cache` セクションでは script / TTS / music の保持上限サイズ、保存日数、再利用範囲を個別に確認できるようにする
- `Import / Export` は `/api/settings` の `PUT` と同じ JSON 形に揃え、`updatedAt` や `configPath` など response metadata は保存対象にしない
- Import は即時保存せず draft に読み込み、内容確認後に `Save Settings` で既存の settings validation を通す
- Export 前にも `apiKeyRef` と `adminTokenRef` が `env:` / `file:` 参照であることを検証し、raw secret らしい draft は JSON download しない
- `Connection Test` の provider health 表示は metadata と message を Web 表示層で redaction し、prompt / lyrics / letter body / radioName / secret / raw response をそのまま描画しない
- `Voice Profiles` では `scope`, `stationId`, `engineType`, `providerKey`, `speakerKey`, `styleKey`, `speed`, `pitch`, `playbackMode` を表示し、局ごとに別の声を選べるようにする。Irodori-TTS の場合は承認済み `referenceVoiceRef` / `consentPolicyRef` の有無と style preset だけを表示する。参照音声の実ファイル path、個人名、音声本文、raw provider option は表示しない
- Irodori-TTS の参照音声を扱う UI は初期では管理者が配置した `voices/` の id 選択までに留め、任意 upload は同意・ライセンス台帳と file validation が実装されるまで追加しない
- 実行中の番組 block へ影響する変更は「次の番組から反映」と明示する
- `/settings/content` は局ごとに `programCount`, `preGeneratedProgramCount`, `scriptAssetCount`, `audioAssetCount`, `musicAssetCount`, `musicAssetBytes`, `generatedAssetBytes` を表形式で表示する
- 事前生成フォームは局、有効な任意の `ProgramTemplate` または自動選択、1 から 10 の番組数、台本・音声、曲の生成対象を指定できる
- 事前生成はライブの局切替や再生 queue を変更しないことを操作前に明示し、受付後は `QUEUED`, `RUNNING`, `MATERIALIZED`, `FAILED` を色だけでなく文字でも表示する
- `MATERIALIZED` は MusicGen 完了ではなく、番組データ作成と非同期 job 投入完了を表す。曲生成の進行・失敗は管理トップまたは `/monitor` で確認する

### 7.3 初期実装範囲

- `/settings` を管理ダッシュボードとし、編集機能は `system`, `providers`, `playout`, `stations`, `programming`, `content` のカテゴリーへ分割する
- `PUT /api/settings` の契約は分割後も共通とし、`system`, `providers`, `playout` は取得済み設定全体を draft として保持しつつ、現在のカテゴリーに属する項目だけを表示して一括保存する
- `stations` と `programming` は PostgreSQL を正本とする既存の Station / Programming API を使い、`/api/settings` の保存操作とは分離する
- `providers` は `defaultProvider`, `fallbackProviders` に加え、既存 endpoint の `baseUrl`, `healthPath`, `timeoutMs`, `capabilities`, `adapter`, model / profile を編集できるようにする
- LLM は `adapter` を `OLLAMA` / `OPENAI_COMPATIBLE` から選び、`defaultModelProfileId` を実際のモデル名として入力する。接続確認で取得した model 一覧は入力候補として表示し、指定モデルが Provider に存在しない場合は `DEGRADED` と修正理由を表示する
- production の Server は host network で動作するため、Ollama / ACE-Step が同一サーバー上にある場合は `127.0.0.1:11434` / `127.0.0.1:8001` を入力できる補助を出す。画面を開いた端末から見た URL ではなく、SeedShiftRadio Server から見た接続先であることを明示する
- URL、adapter、model / profile の保存前検証は内部 JSON path をそのまま表示せず、対象 Provider と修正例を日本語で示す
- TTS provider は `VOICEVOX` と `IRODORI_OPENAI_TTS` の default/fallback 切替を扱えるようにする。Irodori の `apiKeyRef` は `env:` / `file:` 参照のみ表示・編集し、bearer token の実値は扱わない
- `Test Connections` は未保存 draft ではなく、保存済み設定に対して実行する
- LLM の接続確認は health/model inventory の確認であり、実モデルのコールドスタート時間や構造化台本生成の成功を保証しない。`timeoutMs` が実生成時間より短い場合は、接続成功でも生成時に `PROVIDER_TIMEOUT` になり得ることを画面と運用ログで判断できるようにする
- 管理 session がない場合は Settings / Monitor 導線を非表示にし、`/settings`、`/monitor` を直接開いた時は `/admin/login` へ遷移する
- `/admin/login` は Web 管理用パスワードだけを一時入力として受け取り、成功時に server-side で署名した `HttpOnly` session Cookie を確立する。管理 API 用トークンとは別資格情報とし、入力値を browser storage や標準ログへ残さない
- `/admin/login` の同一 origin 判定は、Next.js 内部 URL ではなく利用者から見える protocol と host を基準にする。host は reverse proxy が設定した `X-Forwarded-Host` の先頭要素を優先し、なければ `Host`、protocol は `X-Forwarded-Proto` の先頭要素を優先し、なければ request URL を使う。`Origin` の欠落、不正 URL、protocol / host 不一致は拒否する
- 管理 session Cookie の `Secure` 属性は同じ公開 protocol 判定に従い、HTTPS 利用者経路では有効、loopback を含む HTTP 利用者経路では無効にする。login と logout で同じ属性を使う
- 認証済み session の確認は `GET /api/auth/session` を使い、返された CSRF token は client memory だけで扱う。状態変更を伴う `/api-proxy` request と `POST /api/auth/logout` は `X-CSRF-Token` を付ける
- `Import / Export` は Web 側で JSON download / file import として実装し、専用 API は増やさず既存の `GET /api/settings` と `PUT /api/settings` を使う
- Import 時は `schemaVersion` の一致を確認し、`version` は現在の保存済み設定へ合わせる。`apiKeyRef` と `adminTokenRef` は `env:` / `file:` 参照だけ受け付ける
- `Stations` は概要表示に加えて station 基本情報の新規作成/複製/編集保存と station programming policy の編集保存を実装する。`Program Templates` は create/duplicate/edit/slot 編集まで扱い、`Programming Preview` は保存済み policy に加えて未保存 policy/template draft を含めた preview も実行できる
- `Voice Profiles` の作成/編集 UI は後続実装対象とする。Irodori 取り込みの第一段では seed / DB migration と既存 station の `defaultVoiceProfileId` 差し替えで、チャンネルごとに別 voice id / style preset を割り当てられる状態を優先する
- `/letters` の管理 inbox、`/settings`、`/monitor` は公開 UI と分離し、server-side session を確立した利用者だけが表示・操作できるようにする。`NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN` と legacy browser token 導線は廃止し、公開 build、Cookie、browser storage へ管理 API 用トークンを含めない
- `/settings/content` も同じ管理 session と CSRF 契約を使い、browser は `X-Admin-Token` を生成しない。`POST /api/management/stations/{stationId}/pre-generations` は同一 origin BFF 経由だけで送る

## 8. 監視画面

- Provider 状態
- バッファ残量
- READY queue の合計 duration
- 現在番組 block と template version
- generated asset cache の保存量、期限切れ候補、種別別 hit rate
- archive pool 件数と archive replay rate
- musicGen worker の queue size, queued/running jobs, average job seconds, adapter, default model / profile
- 進行中ジョブ
- 成功・失敗を含む直近のジョブ実行結果
- 直近 10 件の監査イベント

監視画面は MVP では簡易版とし、全文ログ参照ではなくサマリ表示を原則とする。`provider_job` の running / recent result / failed 一覧と SSE 履歴由来の audit events を併記し、詳細な全文監査ログではなく要約を出す。
- `/monitor` の `PLAYING` は Server の再生状態であり、ブラウザー音声の再生有無そのものではない。監視画面にはラジオ画面への明示導線を置き、ブラウザー音声の開始・停止操作と混同させない
- summary は定期 refresh し、provider status, generated asset cache, archive metrics, job, audit event を画面内で絞り込めるようにする
- worker status detail は `providerHealth.metadata` のうち `adapter`, `defaultModelProfileId`, `modelProfileIds`, `selectedModel`, `selectedModelAvailable`, `queueSize`, `queuedJobs`, `runningJobs`, `averageJobSeconds`, `defaultModel`, `models`, `statsStatus`, `modelsStatus` の短い状態値だけを整形して表示し、prompt / lyrics / letter body / radioName / secret は出さない
- Irodori-TTS の provider health では `adapter`, `model`, `responseFormat`, `chunkingEnabled`, `maxConcurrentSynthesis`, `voiceRefStatus`, `streamingSupported` など短い状態値だけを表示し、参照音声 path や個人名は redaction する。upstream の chunk-level SSE 対応と現行 SeedShiftRadio adapter の有効化状態は分けて表示する
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
- SSE 切断中もラジオ画面は 3 秒程度、監視画面は 5 から 10 秒程度の REST polling を fallback として継続する
- `subtitle.updated` を受けたら現在の発話のみ差し替える

## 11. アクセシビリティ

- キーボードで周波数スキャンと再生操作を可能にする
- 主要ボタンにショートカットを付与する
- 字幕はスクリーンリーダー向けに `aria-live="polite"` を利用する
- 色だけで状態を表現せず、ラベルとアイコンも併用する
- 現在ページの navigation には `aria-current="page"` を付け、focus-visible を背景上で識別できる 2px 以上の ring で示す
- `prefers-reduced-motion: reduce` では移動 animation と反復 pulse を停止し、monitor の定期更新でカード全体を再 animation しない
- `Card` は静止を既定とし、公開画面の主役カードだけ 200ms の entrance を opt-in する。reduced motion では移動を除いた 160ms の opacity feedback に置き換える
- navigation、button、選択行の press feedback は 120ms、focus / color feedback は 160ms を基準とし、フォームは border と focus ring 以外を transition しない

## 12. 実装メモ

- ラジオ画面の再生状態は Client Component に集約する
- LocalStorage の永続 UI 状態は hydration 完了後に復元し、SSR とブラウザーの初回描画へ保存値やランダムな `clientId` を混在させない
- `useEffectEvent` を用いて音声イベント購読処理を安定化する
- 過剰なグローバル状態は避け、Server State と UI State を分離する
- Settings / Monitor 導線は `GET /api/auth/session` が認証済みを返す時だけ表示し、同じ導線から session logout を実行できるようにする
- 管理認証 E2E は `/api/auth/login` の同一 origin 成功、cross-origin の `403` 拒否、`GET /api/auth/session` の認証成立を独立ケースで確認する。失敗診断には status と判定段階だけを残し、password、session Cookie、CSRF token、管理 API 用トークンを出さない
- `/settings` の Import / Export helper は Vitest で、metadata 除外、schemaVersion mismatch、Import/Export 双方の secret 参照検証、未登録 fallback provider を確認する
- `/settings` / `/monitor` の provider health 表示 helper は Vitest で、metadata/message/object fallback に prompt / lyrics / letter body / radioName / secret が混ざっても露出しないことを確認する
- `/settings` の station 基本情報更新は API client test で、CSRF header、JSON body、URL encode を確認し、browser が `X-Admin-Token` を生成しないことを固定する
- `/settings` の station programming policy 更新は API client test で、CSRF header、JSON body、URL encode を確認し、管理 token 注入は BFF test で固定する
- 管理ダッシュボードは Vitest で byte 表示と管理 API proxy 分類を確認し、`/settings/content` の事前生成 request は API client test で CSRF header、URL encode、JSON body を固定する
- `/monitor/logs` は `GET /api/monitor/logs` と `GET /api/monitor/summary` を 5 秒間隔で再取得し、Provider job SSE 受信時にも再取得する。待機・実行中・成功・失敗を日本語で表示し、level、category、error code、Provider、source/request ID、correlation ID で絞り込む。prompt、本文、秘密値、生の Provider 応答は表示しない
- Playwright E2E では `/` の `Tune -> Play -> audio event`、`/letters` の `投稿 -> ローカル履歴 -> 公開採用履歴`、SSE の `subtitle.updated` と reconnect 時 `Last-Event-ID`、`/settings` の管理カテゴリー遷移、`/settings/content` の台帳表示と事前生成 request を mock API / mock stream / audio stub で確認する
- E2E selector は role と label を基本にしつつ、接続状態、queue item、audio console、投稿 toast、ローカル履歴、採用履歴など揺れやすい要素だけ `data-testid` を補助利用する
- Playwright では 390px viewport の header 高さ、44px 以上の navigation target、横 overflow、`aria-current`、reduced motion 時の opacity-only entrance を確認する
