# AIローカルラジオアプリ Provider連携設計書

## 1. 目的

本書は `LLM`, `TTS`, `Music Generation Provider` を統一原則で差し替え可能にするための抽象化と接続方式を定義する。Music generation は `MUSICGEN_WORKER` と `ACE_STEP` を provider 種別として扱う。

## 2. 共通方針

- 上位層は Provider 固有 API を直接呼ばない
- Provider ごとの差異は `Capability` と `Metadata` で吸収する
- HTTP を第一候補とし、必要時のみ CLI 連携を許容する
- タイムアウト、リトライ、エラー分類は共通化する
- Provider 呼び出し前に cache hit と archive replay 可否を評価し、不要な生成を避ける

## 3. 推奨インターフェース

```java
public interface ScriptProvider {
    ScriptResult generateScript(ScriptRequest request);
}

public interface TtsProvider {
    TtsResult synthesize(TtsRequest request);
}

public interface MusicGenerationProvider {
    SubmittedMusicJob submit(ResolvedMusicProvider provider, MusicGenerationRequest request);
    MusicJobStatus poll(ResolvedMusicProvider provider, String jobId);
    ModelCatalog listModels(ResolvedMusicProvider provider);
    RuntimeStats stats(ResolvedMusicProvider provider);
}
```

上位サービスは `ProviderRegistry` から具象を解決する。

## 4. Provider 解決

| 項目 | 方針 |
|---|---|
| 既定 Provider | `config.json` で指定 |
| station / program template 固有上書き | 可能 |
| fallback Provider | 種別ごとに 1 件以上設定可能 |
| 接続テスト | `/api/settings/test-connections` から実行 |
- 設定更新 | `/api/settings` の `version`/`schemaVersion` で楽観ロックし、`features` で placeholder 制御を入れる |

## 5. 接続方式

| 種別 | 方式 | 備考 |
|---|---|---|
| LLM | HTTP | `Ollama` や OpenAI 互換 API を想定 |
| TTS | HTTP | `VOICEVOX` を第一候補 |
| Music Generation | HTTP 非同期 | `ACE_STEP` REST API と互換用 `FastAPI Worker` を第一候補 |
| 例外 | CLI | モデル都合で HTTP 化が難しい時のみ許容 |

CLI 方式は worker ラッパーで吸収し、Java 本体から直接プロセス制御しない。

## 6. タイムアウト/リトライ

| Provider | Timeout | Retry |
|---|---|---|
| LLM | 20 秒 | 1 回 |
| TTS | 15 秒 | 1 回 |
| Music Generation | submit/poll は provider timeout、完了待ちは 180 秒 | 即時再試行なし。fallback provider またはジョブ再投入のみ |

再試行時は `correlationId` を継承する。

## 7. エラー分類

| Code | 意味 |
|---|---|
| `PROVIDER_UNREACHABLE` | 接続不可 |
| `PROVIDER_TIMEOUT` | 応答遅延 |
| `PROVIDER_BAD_RESPONSE` | 形式不正 |
| `PROVIDER_REJECTED` | 入力拒否 |
| `PROVIDER_RESOURCE_EXHAUSTED` | GPU/メモリ不足、queue 混雑、HTTP 429/503 |
| `PROVIDER_AUTH_FAILED` | API key 不正、秘密値参照解決失敗 |

## 8. 設定注入

- 接続 URL
- `adapter`: `MUSICGEN_WORKER` または `ACE_STEP`
- model name / model profile
- timeout
- provider fingerprint
- credential ref / `apiKeyRef`
- cache policy
- preGenerationMode

機密値は `env:` または `file:` 参照とする。`apiKeyRef` は参照名だけを保存し、実値は API response、SSE、標準ログへ出さない。

Server は実行経路を `provider_job` と `generated_asset` に残し、`queue_item.assetId` から再生資産へ辿れるようにする。worker 未接続の段階では placeholder provider 経路で同じ永続化契約を先に満たしてよい。`config.json.cache` の reuse scope は cache hit 判定と eviction の設計基盤になるが、現行実装では MusicGen の cache-first 再利用までが先行しており、retention/eviction の定期処理は未実装である。

### 8.1 Cache-first 実行

1. `contentHash` を計算し `generated_asset` を reuse scope に従って検索する
2. hit した場合は Provider 呼び出しを省略し、`queue_item.content_origin=CACHE_REUSED` を記録する
3. miss した場合のみ Provider を呼び出す
4. 完了 asset は `byte_size`, `reuse_scope`, `expires_at`, `archive_eligible` を付けて保存する
5. `archive_eligible=true` かつ安全条件を満たすものは `broadcast_archive` へ昇格可能にする

現行実装で cache-first が使われているのは MusicGen のみで、`generated_asset.cache_key` と provider/request の正規化入力を使って再利用候補を探す。`script` と `TTS` の cache-first も設計上は同じ契約だが、実処理と eviction job はまだ未接続である。

### 8.2 Music generation profile

`providers.musicGen.providers.{providerKey}` は通常の endpoint field に加えて以下を持つ。

| Field | 用途 |
|---|---|
| `adapter` | `MUSICGEN_WORKER` または `ACE_STEP` |
| `apiKeyRef` | `env:` / `file:` 形式の秘密値参照。空なら無認証 |
| `defaultModelProfileId` | 未指定 request の profile id |
| `modelProfiles` | profile id から model 設定への map |

ACE-Step profile は `model`, `lmModel`, `thinking`, `lyricsLanguage`, `lyricsTransliterationMode`, `outputFormat`, `maxDurationSeconds` を持つ。既定は `ace-ja-fast` で、`vocal_language=ja`, `thinking=true`, `outputFormat=wav` を送る。prompt / lyrics 本文は `promptHash` / `lyricsHash` に変換してから保存・監視に渡す。

### 8.3 ACE-Step runtime probes

ACE-Step は `/health`, `/v1/models`, `/v1/stats` を監視に使える。`/v1/models` は model 一覧と既定 model、`/v1/stats` は queue size、queued/running jobs、平均処理時間を返す前提とする。監視 UI は生成本文ではなく、provider key、adapter、profile id、分類済み失敗理由だけを表示する。

## 9. 推奨 OSS と使い分け

| 領域 | 第一候補 | 代替 |
|---|---|---|
| LLM | Spring AI + Ollama | OpenAI 互換 API, llama.cpp サービス |
| TTS | VOICEVOX | AivisSpeech, Style-Bert-VITS2 |
| Music Generation | ACE-Step 1.5 REST API + FastAPI Worker | AudioCraft / MusicGen 系 |
| API文書 | springdoc-openapi | 手書き OpenAPI |
| 非同期ジョブ | JobRunr | Quartz |

## 10. ヘルスチェック

Provider ごとに以下を持つ。

- `status`: UP / DEGRADED / DOWN
- `lastCheckedAt`
- `responseTimeMs`
- `message`
- `capabilities`
- `metadata`: ACE-Step では adapter、profile id、queue stats、model 一覧など。prompt / lyrics / 秘密値は含めない

上記 Payload は `/api/monitor/summary` と `/api/health` で `providerHealth` map として返す。key は `llm`, `tts`, `musicGen`、value は各 Provider 種別の `ProviderHealthPayload` とし、SSE `provider.health.changed` イベントでも同じ map 構造を送る。
`status` は `UP` で正常、`DEGRADED` で代替 Provider へ切り替え中、`DOWN` で fallback に突入するシグナルとして解釈される。SSE の `Last-Event-ID` で再接続すると最新状態を受け取れる。

Health は `/api/health` と `/api/monitor/summary` に集約する。

## 11. テスト戦略

- Provider ごとの contract test を用意する
- mock provider を標準実装として持つ
- ACE-Step fake HTTP server で `release_task -> query_result -> audio download` の成功/失敗/混雑を再現する
- タイムアウト、異常応答、空応答、部分成功を再現できるようにする
- prompt / lyrics / API key / radioName / letter body が通常ログ、SSE、API response に出ないことを確認する

## 12. 設計上の注意

- Provider 抽象で差異を隠し過ぎず、`capabilities` を上位から参照できるようにする
- 音声と音楽の生成物はファイル正本を Server が管理する
- `provider_job.external_ref` は worker 側 `jobId` や Provider 側 request id を保持し、未接続時は placeholder 実装の識別子を入れてよい
- 監視画面には生の provider error ではなく整形した分類を表示する
- Provider request metadata には `stationId`, `programTemplateId`, `programSlotId` を含め、監査とキャッシュに利用できるようにする
- Provider result metadata には `contentHash`, `byteSize`, `duration`, `archiveEligible` を含め、cleanup と replay promotion に利用できるようにする
