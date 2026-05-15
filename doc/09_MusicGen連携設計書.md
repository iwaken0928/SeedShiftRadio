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
| `durationSeconds` | 生成目標秒数。profile の `maxDurationSeconds` 内へ正規化する |
| `bpm`, `keyScale`, `timeSignature` | 任意の音楽メタ |
| `seed` | 再現性が必要な場合の seed |
| `modelProfileId` | `ace-ja-fast` などの profile id |
| `outputFormat` | v1 の radio playback では `/api/assets/audio/{assetId}.wav` と `audio/wav` に合わせて `wav`, `wav32` のみ |

`MusicJobStatus` は `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` を Server 内部状態として返す。`provider_job` では `queued/running/succeeded/failed/canceled/degraded` 相当へ集約し、`external_ref` に `providerTaskId`、`generated_asset` に `assetId`, `providerFingerprint`, `metadata.model`, `metadata.lmModel`, `metadata.seed`, `metadata.duration`, `metadata.promptHash`, `metadata.lyricsHash` を残す。prompt / lyrics 本文は保存メタ、SSE、標準ログへ含めない。

## 4. Provider 種別

### 4.1 `MUSICGEN_WORKER`

互換用 worker は以下の API を維持する。

- `POST /music/jobs`
- `GET /music/jobs/{jobId}`
- `GET /health`

`workers/musicgen` の現行実装は deterministic WAV を生成し、Server と Worker の非同期契約、cache-first、fallback のテストに使う。
worker のデータルートは `SEEDSHIFT_MUSICGEN_DATA_ROOT` で指定し、pipeline の `deploy-musicgen` では `/data` を使う。`MUSICGEN_DATA_VOLUME` が GitLab CI/CD Variables に設定されている場合だけ Podman volume または host path を `/data` へ mount し、未設定時は container-local の一時領域として扱う。

### 4.2 `ACE_STEP`

ACE-Step 1.5 は次の REST API を使う。

- `POST /release_task`: `prompt`, `lyrics`, `vocal_language`, `thinking`, `model`, `lm_model_path`, `audio_duration`, `audio_format`, `bpm`, `key_scale`, `time_signature`, `seed` を送る
- `POST /query_result`: `task_id_list` で polling し、`status=1` を成功、`status=2` を失敗、`status=0` を実行中として扱う
- `/v1/audio?path=...`: 成功 result 内の file URL を Server が download し、asset pipeline に登録する
- `GET /health`, `GET /v1/models`, `GET /v1/stats`: settings / monitor から接続状態、model 一覧、queue size、平均処理時間を確認する

HTTP `401/403` は `PROVIDER_AUTH_FAILED`、`429/503` は `PROVIDER_RESOURCE_EXHAUSTED`、timeout は `PROVIDER_TIMEOUT`、その他 5xx / JSON 不正は `PROVIDER_BAD_RESPONSE` に分類する。分類は縮退判断に使い、Provider 応答本文を標準ログへそのまま残さない。

## 5. ACE-Step model profiles

`providers.musicGen.providers.{key}.modelProfiles` で上書きできる。未指定時は `ace-ja-fast` を使う。

| Profile | Model | LM | 用途 |
|---|---|---|---|
| `ace-ja-fast` | `acestep-v15-turbo` | `acestep-5Hz-lm-0.6B` | 既定。短尺/通常ラジオ向け |
| `ace-ja-balanced` | `acestep-v15-turbo` または `acestep-v15-sft` | `acestep-5Hz-lm-1.7B` | 事前生成向け |
| `ace-ja-xl-fast` | `acestep-v15-xl-turbo` | `acestep-5Hz-lm-1.7B` | 20GB 前後 VRAM 以上の高品質枠 |
| `ace-ja-xl-quality` | `acestep-v15-xl-sft` | `acestep-5Hz-lm-4B` | 24GB 以上またはオフライン生成向け |

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

## 9. 監視項目

- Provider health: `UP / DEGRADED / DOWN`
- ACE-Step queue size, queued/running jobs, average job seconds
- model 一覧と既定 model
- cache hit rate
- 失敗率と分類済み失敗理由
- provider response time と timeout count

設定/監視 UI は接続テスト、model profile 選択、queue stats、直近失敗理由を表示する。prompt / lyrics / API key / radioName / letter body は表示しない。

## 10. テスト方針

- ACE-Step request mapping で `lyricsLanguage=ja` が `vocal_language=ja` になり、`thinking=true` と profile model が送られること
- `/query_result` の result JSON string / array / object を parse し、audio URL、seed、model、metas、失敗状態を取り出せること
- `429`, timeout, provider down で `DEGRADED` へ進み、playout が止まらないこと
- config validation で未知 profile、不正 duration、秘密値直書き、`wav` / `wav32` 以外の outputFormat を検出すること
- prompt / lyrics / API key / radioName / letter body が通常ログ、SSE、API response に生で出ないこと
- fake ACE-Step HTTP server で `release_task -> query_result -> audio download` の成功/失敗/混雑を再現すること
- FastAPI worker contract test で `/health`, `POST /music/jobs`, `GET /music/jobs/{jobId}` の `RUNNING/SUCCEEDED/FAILED` 応答に safe metadata だけが含まれ、prompt / lyrics 本文が response に出ないことを固定すること

## 11. ライセンスと運用注意

- モデル本体ライセンス
- 学習済み重みの再配布条件
- 生成物の利用条件
- サンプル音源の扱い

上記は実装前に [11_運用・監視・セキュリティ・テスト設計書.md](./11_運用・監視・セキュリティ・テスト設計書.md) のライセンス台帳へ記録する。
