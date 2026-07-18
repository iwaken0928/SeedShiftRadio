# AIローカルラジオアプリ Provider連携設計書

## 1. 目的

本書は `LLM`, `TTS`, `Music Generation Provider` を統一原則で差し替え可能にするための抽象化と接続方式を定義する。Music generation は `MUSICGEN_WORKER` と `ACE_STEP` を provider 種別として扱う。TTS は `VOICEVOX` に加え、Irodori-TTS-Server の OpenAI Text-to-Speech API 互換 endpoint を `IRODORI_OPENAI_TTS` adapter として扱えるようにする。

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

Provider 実行失敗は adapter 固有の例外文字列を上位へ流さず、次の共通 error code へ正規化する。

- 共通: `PROVIDER_UNREACHABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_REJECTED`, `PROVIDER_RESOURCE_EXHAUSTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED`
- TTS 固有: `VOICE_REF_NOT_FOUND`, `VOICE_CONSENT_REQUIRED`

外部 Provider または Worker が未知の error code を返した場合は `PROVIDER_BAD_RESPONSE` へ正規化する。
`provider_job` は論理生成ジョブの結果を `FAILED` または `SUCCEEDED` として記録し、fallback 中であることを表す `DEGRADED` は `provider_job.status` に追加しない。
現行 runtime は TTS の Provider 試行を個別 `provider_job` として同じ `correlationId` で追跡し、Music Generation の Provider chain は最終 Provider と論理ジョブの最終結果を一つの `provider_job` に残す。

## 4. Provider 解決

| 項目 | 方針 |
|---|---|
| 既定 Provider | `config.json` で指定 |
| station / program template 固有上書き | 可能。TTS では station の `defaultVoiceProfileId` と `VoiceProfile.providerKey` を優先し、局ごとに Irodori / VOICEVOX / style preset を変えられる |
| fallback Provider | 種別ごとに 1 件以上設定可能 |
| 接続テスト | `/api/settings/test-connections` から実行 |
- 設定更新 | `/api/settings` の `version`/`schemaVersion` で楽観ロックし、`features` で placeholder 制御を入れる |

## 5. 接続方式

| 種別 | 方式 | 備考 |
|---|---|---|
| LLM | HTTP | `Ollama` や OpenAI 互換 API を想定 |
| TTS | HTTP | `VOICEVOX` を安定候補、`Irodori-TTS-Server` を高品質 server-side TTS 候補 |
| Music Generation | HTTP 非同期 | `ACE_STEP` REST API と互換用 `FastAPI Worker` を第一候補 |
| 例外 | CLI | モデル都合で HTTP 化が難しい時のみ許容 |

CLI 方式は worker ラッパーで吸収し、Java 本体から直接プロセス制御しない。

### 5.1 Irodori OpenAI TTS adapter

Irodori-TTS は Java Server から Python CLI を直接起動せず、`Aratako/Irodori-TTS-Server` を別プロセスまたは別 container として起動し、HTTP で呼び出す。

対象:

- model: `Aratako/Irodori-TTS-500M-v3`
- server: `Irodori-TTS-Server`
- adapter: `IRODORI_OPENAI_TTS`
- health: `GET /health`
- model list: `GET /v1/models`
- synthesis: `POST /v1/audio/speech`

request mapping:

| SeedShiftRadio | Irodori-TTS-Server |
|---|---|
| `TtsRequest.normalizedText` | `input` |
| `ResolvedProvider.modelName` または既定値 | `model`, 通常は `irodori-tts` |
| `VoiceProfile.speakerKey` | `voice`。Irodori server の `voices/` または `voices.json` の voice id |
| `VoiceProfile.speed` | `speed` |
| `VoiceProfile.providerOptions.responseFormat` | `response_format`。既定は `wav` |
| `VoiceProfile.providerOptions.irodori` | `irodori` object。`num_steps`, CFG, chunking などの安全な allowlist のみ |

初期実装では `response_format=wav` を標準にし、既存 `/api/assets/audio/{assetId}.wav` 契約を崩さない。現行 `HttpTtsProvider` は `SpeechDirective.normalizedText`、`voiceHint=IRODORI_TTS:<voiceId>[:style]`、`providers.tts.providers.{key}.defaultModelProfileId` から `/v1/audio/speech` を呼び、provider job と audio asset を作成する。`VoiceProfile.speed`、`providerOptions`、参照音声同意の runtime 反映は `P0-02c` の拡張で閉じる。`mp3` などは asset manifest / content type の拡張時に許可する。

### 5.2 VOICEVOX TTS adapter

VOICEVOX は Irodori 失敗時にも使える安定 fallback として、Java Server から HTTP で呼び出す。

request mapping:

| SeedShiftRadio | VOICEVOX |
|---|---|
| `SpeechDirective.normalizedText` | `/audio_query?text=...` の `text` |
| `voiceHint=VOICEVOX:<speakerId>[:style]` | `/audio_query` と `/synthesis` の `speaker` |
| 既定 provider | `providers.tts.defaultProvider` / `fallbackProviders` の順に解決 |

現行実装では `/audio_query` の JSON をそのまま `/synthesis` へ渡し、戻った WAV を `generated_asset` として保存する。audio asset metadata には `normalizedTextHash`, `providerKey`, `adapter`, `speakerKey`, `styleKey`, `voiceHint`, `pronunciationHintCount`, `pauseHintCount` のような短い値だけを残し、本文、prompt、letter body、raw provider response、秘密値は入れない。

重要な制約:

- Irodori-TTS-Server は `stream_format=sse` で chunk-level SSE を提供する。OpenAI SDK の通常の streaming response は完成音声を逐次転送するだけなので、両者を区別する
- 現行 SeedShiftRadio adapter は `stream_format=sse` を使わず、完成 WAV を Server 管理 asset として保存する。chunk-level SSE の採用可否は GitLab `P0-02b` で判断する
- 既定の最大同時 synthesis は 1 件で、混雑や model load timeout は 503 として返りうる
- `voice: "none"` や無参照発話は可能だが、ラジオパーソナリティ用途では声質の再現性が落ちるため、承認済み reference voice を持つ `VoiceProfile` を優先する
- VoiceDesign v3 は未公開のため、caption-conditioned voice design は v2 VoiceDesign checkpoint を別 provider profile として将来追加する

fallback 方針:

1. Irodori で `PROVIDER_RESOURCE_EXHAUSTED`, `PROVIDER_TIMEOUT`, `PROVIDER_UNREACHABLE`, `PROVIDER_BAD_RESPONSE` が発生した場合、同一台本で `providers.tts.fallbackProviders` の次候補へ切り替える
2. Irodori の `VOICE_CONSENT_REQUIRED`, `VOICE_REF_NOT_FOUND` は同一 Provider で再試行せず、別 `VoiceProfile` または VOICEVOX fallback へ切り替える
3. fallback で生成した audio asset は `provider_fingerprint` と `voiceHint` を明示し、Irodori cache と混同しない
4. すべての TTS が失敗した場合は、`features.streaming.placeholderEnabled=true` なら placeholder WAV へ縮退し、無効なら provider error を返して上位の degraded 扱いにする

## 6. タイムアウト/リトライ

| Provider | Timeout | Retry |
|---|---|---|
| LLM | 20 秒 | 1 回 |
| TTS | 15 秒。Irodori は初回 model load / CPU fallback を考慮し provider ごとに 60 から 300 秒へ延長可能 | 1 回。Irodori の 503 / queue timeout は同一 provider 再試行より fallback provider を優先 |
| Music Generation | submit/poll は provider timeout、完了待ちは 180 秒 | 即時再試行なし。fallback provider またはジョブ再投入のみ |

再試行時は `correlationId` を継承する。

Provider chain の fallback を許可する error code は `PROVIDER_UNREACHABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_RESOURCE_EXHAUSTED` と、TTS 固有の `VOICE_REF_NOT_FOUND`, `VOICE_CONSENT_REQUIRED` に限定する。
`PROVIDER_REJECTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED` では同じ要求を別 Provider へ自動送信しない。
ただし Provider chain の fallback を行わない場合でも、上位の playout は安全な cache、archive、local asset、placeholder による縮退継続を選べる。

## 7. エラー分類

| Code | 意味 |
|---|---|
| `PROVIDER_UNREACHABLE` | 接続不可 |
| `PROVIDER_TIMEOUT` | 応答遅延 |
| `PROVIDER_BAD_RESPONSE` | 形式不正 |
| `PROVIDER_REJECTED` | 入力拒否 |
| `PROVIDER_RESOURCE_EXHAUSTED` | GPU/メモリ不足、queue 混雑、HTTP 429/503 |
| `PROVIDER_AUTH_FAILED` | API key 不正、秘密値参照解決失敗 |
| `PROVIDER_INTERRUPTED` | Server 側の中断、キャンセル、thread interrupt |
| `VOICE_REF_NOT_FOUND` | 参照音声 voice id または file が provider 側に存在しない |
| `VOICE_CONSENT_REQUIRED` | 参照音声の同意・利用条件が未確認 |

安全性ポリシーによる拒否は `PROVIDER_REJECTED` に分類し、専用の safety error code は増やさない。
一般の HTTP 4xx も `PROVIDER_REJECTED` とするが、HTTP 401/403 は `PROVIDER_AUTH_FAILED`、408/504 は `PROVIDER_TIMEOUT`、429/503 は `PROVIDER_RESOURCE_EXHAUSTED` を優先する。
接続不能は `PROVIDER_UNREACHABLE`、JSON・音声・状態値の形式不正、空応答、未知の外部 error code は `PROVIDER_BAD_RESPONSE` とする。

## 8. 設定注入

- 接続 URL
- `adapter`: `MUSICGEN_WORKER` または `ACE_STEP`
- model name / model profile
- timeout
- provider fingerprint
- credential ref / `apiKeyRef`
- cache policy
- preGenerationMode
- TTS adapter (`VOICEVOX`, `IRODORI_OPENAI_TTS` など)

機密値は `env:` または `file:` 参照とする。`apiKeyRef` は参照名だけを保存し、実値は API response、SSE、標準ログへ出さない。

Server は実行経路を `provider_job` と `generated_asset` に残し、`queue_item.assetId` から再生資産へ辿れるようにする。worker 未接続の段階では placeholder provider 経路で同じ永続化契約を先に満たしてよい。`config.json.cache` の reuse scope は cache hit 判定と eviction の設計基盤になるが、現行実装では MusicGen の cache-first 再利用までが先行しており、station `preGeneration.preferCacheReuse=false` の場合は reusable asset が存在しても worker submit を優先する。retention/eviction の定期処理は未実装である。

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

### 8.4 TTS provider profile

`providers.tts.providers.{providerKey}` は通常 endpoint に加え、必要に応じて `adapter`, `apiKeyRef`, `defaultModelProfileId` を持つ。TTS 固有の細かい request option は provider endpoint ではなく `VoiceProfile.providerOptions` に寄せ、station/persona ごとの差し替えをしやすくする。

Irodori の設定例:

```json
{
  "providers": {
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
    }
  }
}
```

`apiKeyRef` は Irodori-TTS-Server の optional bearer token を使う場合だけ設定する。無認証 localhost 運用では空でもよいが、外部 bind は禁止に近い扱いとし、必要な場合は firewall と token を必須にする。

## 9. 推奨 OSS と使い分け

| 領域 | 第一候補 | 代替 |
|---|---|---|
| LLM | Spring AI + Ollama | OpenAI 互換 API, llama.cpp サービス |
| TTS | VOICEVOX / Irodori-TTS-Server | AivisSpeech, Style-Bert-VITS2 |
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
- `metadata`: Irodori では adapter、model id、response format、chunking enabled、concurrency limit、queue timeout、voiceRef status、upstream chunk SSE 能力、現行 adapter の streaming 有効状態など。参照音声の実ファイル path、個人名、本文、秘密値は含めない

`metadata` と `message` は診断用の短い状態値に限定する。Web は provider 契約違反の payload が混ざった場合も、secret / prompt / lyrics / letter body / radioName / raw response らしい key や値を redaction し、worker status detail は許可済み metadata key の短い値だけを表示する。

上記 Payload は `/api/monitor/summary` と `/api/health` で `providerHealth` map として返す。key は `llm`, `tts`, `musicGen`、value は各 Provider 種別の `ProviderHealthPayload` とし、SSE `provider.health.changed` イベントでも同じ map 構造を送る。
`status` は `UP` で既定 Provider が正常、`DEGRADED` で既定 Provider は失敗したが fallback Provider が利用可能、`DOWN` で Provider chain 内に利用可能な Provider がない状態とする。
Provider health が `DOWN` でも cache、local asset、placeholder により放送を継続できる間、playout は `ERROR` ではなく `DEGRADED` を維持する。
`/api/health`, `/api/monitor/summary`, SSE `provider.health.changed` は同じ意味の status を返す。SSE の `Last-Event-ID` で再接続すると最新状態を受け取れる。

Health は `/api/health` と `/api/monitor/summary` に集約する。

## 11. テスト戦略

- Provider ごとの contract test を用意する
- mock provider を標準実装として持つ
- ACE-Step fake HTTP server で `release_task -> query_result -> audio download` の成功/失敗/混雑を再現する
- Irodori fake HTTP server で `/health`, `/v1/models`, `/v1/audio/speech` の成功、503 queue timeout、401 auth failed、voice not found、bad audio bytes を再現する
- タイムアウト、異常応答、空応答、部分成功を再現できるようにする
- prompt / lyrics / API key / radioName / letter body が通常ログ、SSE、API response に出ないことを確認する

## 12. 設計上の注意

- Provider 抽象で差異を隠し過ぎず、`capabilities` を上位から参照できるようにする
- 音声と音楽の生成物はファイル正本を Server が管理する
- `provider_job.external_ref` は worker 側 `jobId` や Provider 側 request id を保持し、未接続時は placeholder 実装の識別子を入れてよい
- 監視画面には生の provider error ではなく整形した分類を表示する
- Provider request metadata には `stationId`, `programTemplateId`, `programSlotId` を含め、監査とキャッシュに利用できるようにする
- Provider result metadata には `contentHash`, `byteSize`, `duration`, `archiveEligible` を含め、cleanup と replay promotion に利用できるようにする
