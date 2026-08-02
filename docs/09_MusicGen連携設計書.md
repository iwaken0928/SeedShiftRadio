# AIローカルラジオアプリ Music Generation Provider 連携設計書

## 1. 目的

本書はローカル AI 音楽生成を `Server` から安全に利用するための非同期連携方式を定義する。従来の `MusicGen Worker` 固定ではなく、`Music Generation Provider` として `MUSICGEN_WORKER` と `ACE_STEP` を同じ Server 契約で扱う。

## 2. 採用方針

- 重い音楽生成は Java 本体へ直接組み込まず、外部 HTTP Provider または Worker として分離する
- `workers/musicgen` は互換用の FastAPI worker として残し、Server 内部と設計上の名称は `Music Generation Provider` に寄せる
- ACE-Step 1.5 は公式 REST API を直接呼び、LocalAI / ComfyUI 経由は v1 の対象外とする
- 日本語歌ものは即時補充ではなく、事前生成候補として JobRunr へ投入する
- Provider 障害、混雑、生成失敗時は `DEGRADED` として扱い、再生停止より縮退継続を優先する

## 3. Server 共通インターフェース

```java
public interface MusicGenerationProvider {
    SubmittedMusicJob submit(ResolvedMusicProvider provider, MusicGenerationRequest request);
    MusicJobStatus poll(ResolvedMusicProvider provider, String jobId);
    ModelCatalog listModels(ResolvedMusicProvider provider);
    RuntimeStats stats(ResolvedMusicProvider provider);
}
```

`MusicGenerationRequest` は以下を持つ。

| Field | 用途 |
|---|---|
| `purpose` | `radio`, `pre_generation`, `offline` などの利用目的 |
| `mode` | `JAPANESE_SONG`, `BGM`, `INSTRUMENTAL` など |
| `prompt` | 曲調、楽器、テンポ感などの caption。API 応答、SSE、標準ログへ全文を出さない |
| `lyrics` | 歌詞本文。日本語歌詞は台本/TTS とは別生成物として扱い、全文をログへ出さない |
| `lyricsLanguage` | `ja` を既定とし、ACE-Step では `vocal_language` へ写像する |
| `durationSeconds` | 生成目標秒数。profile の `maxDurationSeconds` 内へ正規化する。生成完了後は WAV frame から測定した実尺を QueueItem へ反映する |
| `bpm`, `keyScale`, `timeSignature` | 任意の音楽メタ |
| `seed` | 再現性が必要な場合の seed |
| `modelProfileId` | `ace-ja-fast` などの profile id |
| `outputFormat` | v1 の radio playback では `/api/assets/audio/{assetId}.wav` と `audio/wav` に合わせて `wav`, `wav32` のみ |

`MusicJobStatus` は `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` を Server 内部状態として返す。`provider_job` も `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` へ集約し、`DEGRADED` は追加しない。Provider chain は最終 Provider と論理ジョブの最終結果を一つの `provider_job` に残し、縮退中であることは Provider health と playout state で表す。`external_ref` に `providerTaskId`、`generated_asset` に `assetId`, `providerFingerprint`, `metadata.model`, `metadata.lmModel`, `metadata.seed`, `metadata.duration`, `metadata.promptHash`, `metadata.lyricsHash` を残す。prompt / lyrics 本文は保存メタ、SSE、標準ログへ含めない。

Server process の停止などで worker の完了確認前に `provider_job` が `RUNNING` のまま残った場合、共通の provider job 回収処理が `updated_at` の stale 閾値を超えた job を `FAILED / PROVIDER_INTERRUPTED` へ確定する。
回収時は worker へ同じ prompt / lyrics を自動再送しない。
`provider_job` に再投入用の本文を保存しない契約を維持し、必要な音楽は queue planning が cache、archive、local asset、placeholder による縮退、または新しい論理生成要求として補充する。

## 4. Provider 種別

### 4.1 `MUSICGEN_WORKER`

互換用 worker は以下の API を維持する。

- `POST /music/jobs`
- `GET /music/jobs/{jobId}`
- `GET /health`

`workers/musicgen` の現行実装は deterministic WAV を生成し、Server と Worker の非同期契約、cache-first、fallback のテストに使う。
worker のデータルートは `SEEDSHIFT_MUSICGEN_DATA_ROOT` で指定する。コンテナで永続化する場合は Podman volume または host path を `/data` へ mount して同変数を `/data` に設定し、未設定時は container-local の一時領域として扱う。

### 4.2 `ACE_STEP`

ACE-Step 1.5 は次の REST API を使う。

- `POST /release_task`: `prompt`, `lyrics`, `vocal_language`, `thinking`, `model`, `lm_model_path`, `audio_duration`, `audio_format`, `bpm`, `key_scale`, `time_signature`, `seed` を送る
- `POST /query_result`: `task_id_list` で polling し、`status=1` を成功、`status=2` を失敗、`status=0` を実行中として扱う
- `/v1/audio?path=...`: 成功 result 内の file URL を Server が download し、asset pipeline に登録する
- `GET /health`, `GET /v1/models`, `GET /v1/stats`: settings / monitor から接続状態、model 一覧、queue size、平均処理時間を確認する

ACE-Step adapter の JDK `HttpClient` は `HTTP_1_1` を明示する。ACE-Step の Uvicorn HTTP/1.1 server に対して平文 HTTP の `h2c` upgrade を試みると、GET probe は成功しても JSON POST body が `Malformed JSON payload` になる場合がある。submit、poll、model load、model/stats取得、audio downloadの全経路で同じHTTP/1.1 client policyを適用する。

`status=2` の失敗理由は `result` だけでなく `progress_text` に格納される実装がある。adapter は両方を分類対象とし、CUDA / device の out-of-memory を `PROVIDER_RESOURCE_EXHAUSTED`、未知失敗を `PROVIDER_BAD_RESPONSE` とする。診断本文は Provider job、monitor、標準ログへ保存せず、安全な日本語メッセージだけを上位へ返す。

Ollama と ACE-Step が同一 GPU を共有する production では、`GpuExecutionCoordinator` が LLM / Music Generation / ACE-Step model load を直列化する。音楽生成前は Ollama `POST /api/generate` に `keep_alive=0` を指定して `GET /api/ps` から model が消えるまで待ち、LLM 実行前は ACE-Step `/v1/stats` の queued/running が 0 になるまで待つ。個々の Ollama `/api/chat` も `keep_alive=0` を維持する。

ACE-Step `v0.1.8` には SeedShiftRadio から使用できる汎用の全 model unload API がないため、単一 GPU 用の ACE-Step container は `ACESTEP_OFFLOAD_TO_CPU=true`, `ACESTEP_OFFLOAD_DIT_TO_CPU=true`, `ACESTEP_LM_OFFLOAD_TO_CPU=true` を前提とする。SeedShiftRadio の `requireAceStepCpuOffload` はこの前提を可視化する設定であり、ACE-Step container の環境変数自体は `releases/acestep` の配備設定で管理する。

production 接続では ACE-Step と SeedShiftRadio Server の両方へ同じ非空の `ACESTEP_API_KEY` を注入し、Provider 設定には `apiKeyRef=env:ACESTEP_API_KEY` を保存する。ACE-Step upstream `v0.1.8` は空文字を認証無効として扱わないため、空値による無認証運用を標準にしない。Server と ACE-Step を同一 host network で動かす場合は `baseUrl=http://127.0.0.1:8001` を使う。

`/health` が HTTP 200 でも生成 model がロード済みとは限らない。SeedShiftRadio は `models_initialized=true` を必須とし、`thinking=true` の profile では `llm_initialized=true` も必須として、それ以外を `DOWN` と判定する。`/v1/models` は現行 OpenAI 互換の `data: []` と旧来の `data.models: []` の両形式を扱う。ACE-Step 側は起動時に生成 model を初期化し、thinking profile を使う production では `ACESTEP_INIT_LLM=true` または同等の `--init-llm` 起動指定で LM も初期化する。ACE-Step container の healthcheck も HTTP status だけでなく `models_initialized=true`、必要なら `llm_initialized=true` を検査する。

HTTP `401/403` は `PROVIDER_AUTH_FAILED`、`408/504` と通信 timeout は `PROVIDER_TIMEOUT`、`429/503` は `PROVIDER_RESOURCE_EXHAUSTED`、その他 4xx と安全性ポリシーによる拒否は `PROVIDER_REJECTED`、接続不能は `PROVIDER_UNREACHABLE`、その他 5xx / JSON 不正 / 空応答 / 未知状態は `PROVIDER_BAD_RESPONSE` に分類する。Server 側の中断は `PROVIDER_INTERRUPTED` とする。外部 Provider または Worker が未知の error code を返した場合も `PROVIDER_BAD_RESPONSE` へ正規化する。分類は縮退判断に使い、Provider 応答本文を標準ログへそのまま残さない。

## 5. ACE-Step model profiles

`providers.musicGen.providers.{key}.modelProfiles` で上書きできる。未指定時は `ace-ja-fast` を使う。

| Profile | Model | LM | 用途 |
|---|---|---|---|
| `ace-ja-fast` | `acestep-v15-turbo` | `acestep-5Hz-lm-0.6B` | 既定。短尺/通常ラジオ向け |
| `ace-ja-balanced` | `acestep-v15-turbo` または `acestep-v15-sft` | `acestep-5Hz-lm-1.7B` | 事前生成向け |
| `ace-ja-xl-fast` | `acestep-v15-xl-turbo` | `acestep-5Hz-lm-1.7B` | 20GB 前後 VRAM 以上の高品質枠 |
| `ace-ja-xl-quality` | `acestep-v15-xl-sft` | `acestep-5Hz-lm-4B` | 24GB 以上またはオフライン生成向け |

### 5.1 ACE-Step model load

`modelProfiles` は SeedShiftRadio 側の名前付き設定であり、ACE-Step 側に同名 profile を登録するものではない。通常生成では選択 profile を `/release_task` の `model`, `lm_model_path`, `thinking` へ展開する。管理者が ACE-Step のロード済み model を切り替える場合だけ、SeedShiftRadio 管理 API `POST /api/settings/providers/music-gen/{providerKey}/model-loads` から ACE-Step `POST /v1/init` を呼ぶ。

`/v1/init` には profile の `model`、`slot`、`thinking` を写像した `init_llm`、thinking profile の `lmModel` を写像した `lm_model_path` を送る。Web 初期実装では `slot=1` とし、ACE-Step `/v1/models` で検出できた model を持つ保存済み profile だけを選択できる。設定保存、接続確認、Server 起動時には自動実行しない。モデルロード中の request timeout は `features.jobExecution.manual.modelLoadTimeoutSeconds` を使い、共有 GPU 実行権の取得と Ollama unload が完了してから `/v1/init` を呼ぶ。完了後は接続確認を再実行し、`models_initialized`, `llm_initialized`, loaded/selected model の一致を確認する。

各 profile は `thinking=true`, `lyricsLanguage=ja`, `lyricsTransliterationMode=native`, `outputFormat=wav` を既定とする。互換/品質対策として `lyricsTransliterationMode` は `native | kana | romaji` を持つが、日本語歌詞の既定は日本語本文をそのまま送る `native` とする。

## 6. 日本語歌詞生成

日本語歌詞は LLM 台本生成や TTS text と別の生成物として扱う。

```mermaid
flowchart LR
    Brief["SongBrief"] --> Draft["LyricsDraft"]
    Draft --> Safe["SafeLyrics"]
    Safe --> Request["MusicGenerationRequest"]
    Request --> Provider["MusicGenerationProvider"]
```

- `lyrics` は日本語本文と `[Verse]`, `[Chorus]`, `[Bridge]`, `[Outro]` などの構造タグを含める
- 90秒以上の歌ものは `Verse -> Chorus -> Verse 2 -> Chorus -> Bridge -> Outro` を基本形とし、短尺へ全構成を詰め込まない
- prompt には全体尺、アウトロ開始期限、終止和音、末尾6秒の自然な fade、語句や持続音を途中で切らない条件を含める
- レター本文、ユーザー投稿、既存アーティスト名、著作権曲名は歌詞へ直接混ぜない
- 必要な場合は要約/抽象化した安全なテーマだけを使う
- 歌詞生成後にボーカル性別、楽器、テンポ感、曲調、言語の軽量整合チェックを行い、caption と lyrics の矛盾を避ける
- `promptHash` と `lyricsHash` は保存するが、本文は API response、SSE、標準ログへ返さない

## 7. キャッシュ方針

cache key は少なくとも以下を正規化して含める。

`provider / model / lm / language / promptHash / lyricsHash / bpm / keyScale / timeSignature / seed / duration / outputFormat / reuseScope`

同一キーがあれば再生成より再利用を優先する。cache hit 時も現在の `queue_item` と `provider_job` を追跡できるよう、Server は同じ `storage_path` を指す新しい `generated_asset` レコードを作成して返す。

## 8. フォールバック

1. キャッシュ済み AI 音楽
2. `broadcast_archive` にある同局音楽
3. インスト BGM / ローカル音源
4. 短い DJ 案内またはジングル
5. 通常 TTS セグメントへの置換

歌もの生成は queue の即時補充をブロックしない。再生予定時刻までに `SUCCEEDED` でなければ上記順で縮退し、`provider_job` と SSE `provider.job.failed` に分類済み理由だけを残す。
現行 runtime では `GenerateMusicJob` の async failure 時、対象 `MUSIC_AI` item をそのまま使って `paths.musicLibrary` 配下の `.wav` を優先的に `MUSIC_LOCAL` `READY` へ差し替え、候補が無い場合は placeholder 音声付き `JINGLE` `READY` に降ろす。どちらも `queue_item.assetId` と `content_origin` を更新して無音停止を避ける。
既定の `tmpl-night-regular` は `OPENING(TALK) -> MUSIC_BREAK(MUSIC_AI) -> ENDING(TALK)` とし、通常経路でもトークの後に Music Generation Provider を使った曲を配置する。中央の曲は120秒を目標とし、100秒までにアウトロへ入り、末尾6秒で終止・fade する生成指示を使う。
`OPENING` / `ENDING` に効果音用の `JINGLE` または将来の短尺 MusicGen cue を割り当てる場合、生成目標は15秒とする。`MUSIC_BREAK` の曲尺にはこの上限を適用しない。
`paths.musicLibrary` が空の skeleton 環境で `MUSIC_LOCAL_PLACEHOLDER` を作る場合は、短い通知音ではなく和音、ベース、旋律、リズムを持つ音楽用 WAV とする。これは実モデル生成物ではなく、Provider またはローカル曲へ到達できない時の再生継続用 asset である。

Provider chain の fallback を許可する error code は `PROVIDER_UNREACHABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_RESOURCE_EXHAUSTED` に限定する。
`PROVIDER_REJECTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED` では同じ prompt / lyrics を別 Provider へ自動送信しない。
この場合も安全な cache、archive、local asset、placeholder による playout fallback は継続し、session は原因 error code を `degraded_reason` に保持した `DEGRADED` とする。

## 9. 監視項目

- Provider health: `UP / DEGRADED / DOWN`
- ACE-Step queue size, queued/running jobs, average job seconds
- model 一覧と既定 model
- cache hit rate
- 失敗率と分類済み失敗理由
- provider response time と timeout count
- stale `RUNNING` の回収件数 `seedshift.provider.jobs.recovered`
- provider job 回収処理の例外終了回数 `seedshift.provider.jobs.recovery.failures`

設定/監視 UI は接続テスト、model profile 選択、queue stats、直近失敗理由を表示する。prompt / lyrics / API key / radioName / letter body は表示しない。
回収済み job は専用の公開 DTO を増やさず、`/api/monitor/summary` の `runningJobs` から外れ、`recentErrors` に `PROVIDER_INTERRUPTED` として現れる。
定期処理の `runOnce` 結果は `checkedAt`, `cutoff`, `scannedCount`, `recoveredCount` を内部の test / 運用確認に使う。

## 10. テスト方針

- ACE-Step request mapping で `lyricsLanguage=ja` が `vocal_language=ja` になり、`thinking=true` と profile model が送られること
- ACE-Step model load で保存済み profile が `/v1/init` の `model`, `slot`, `init_llm`, `lm_model_path` へ写像され、未知 profile、ACE-Step 以外の adapter、不正 slot が送信前に拒否されること
- `/health` が HTTP 200 でも `models_initialized=false`、または thinking profile で `llm_initialized=false` の場合は `DOWN` となり、`/v1/models` の OpenAI 互換配列を解析できること
- `/query_result` の result JSON string / array / object を parse し、audio URL、seed、model、metas、失敗状態を取り出し、`progress_text` の CUDA OOM を `PROVIDER_RESOURCE_EXHAUSTED` に分類できること
- `429`, timeout, provider down で `DEGRADED` へ進み、playout が止まらないこと
- Worker が返す既知 error code は共通分類を維持し、未知 error code は `PROVIDER_BAD_RESPONSE` へ正規化すること
- `PROVIDER_REJECTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED` では Provider chain の fallback を行わず、同じ prompt / lyrics を再送しないこと
- stale 閾値を超えた `RUNNING` job は `FAILED / PROVIDER_INTERRUPTED` へ一度だけ条件付き更新され、worker への再送なしで `provider.job.failed` と monitor summary に反映されること
- config validation で未知 profile、不正 duration、秘密値直書き、`wav` / `wav32` 以外の outputFormat を検出すること
- prompt / lyrics / API key / radioName / letter body が通常ログ、SSE、API response に生で出ないこと
- fake ACE-Step HTTP server で `release_task -> query_result -> audio download` の成功/失敗/混雑と、POST request に `Upgrade: h2c` が含まれないことを再現すること
- FastAPI worker contract test で `/health`, `POST /music/jobs`, `GET /music/jobs/{jobId}` の `RUNNING/SUCCEEDED/FAILED` 応答に safe metadata だけが含まれ、prompt / lyrics 本文が response に出ないことを固定すること

## 11. ライセンスと運用注意

- モデル本体ライセンス
- 学習済み重みの再配布条件
- 生成物の利用条件
- サンプル音源の扱い

上記は実装前に [11_運用・監視・セキュリティ・テスト設計書.md](./11_運用・監視・セキュリティ・テスト設計書.md) のライセンス台帳へ記録する。
