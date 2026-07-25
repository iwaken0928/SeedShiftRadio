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
    GeneratedScript generate(ProviderRegistry.ResolvedProvider provider, ScriptGenerationContext context);
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
| station / program template 固有上書き | 可能。TTS では station の `defaultVoiceProfileId` と `VoiceProfileEntity.providerKey` を優先し、局ごとに Irodori / VOICEVOX / style preset を変えられる。割当可能なのは `GLOBAL` または同じ station の `STATION` profile だけ |
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

### 5.1 LLM adapter

LLM は `OLLAMA` と `OPENAI_COMPATIBLE` の 2 adapter を持つ。現行の `HttpScriptProvider` は既存 Provider 実装と同じ JDK `HttpClient` を使い、新しい Spring AI 依存は追加しない。いずれも `ScriptProvider.generate(ProviderRegistry.ResolvedProvider, ScriptGenerationContext)` の下へ閉じ込め、上位の `ScriptGenerationService` は Provider 固有 endpoint や response envelope を扱わない。Spring AI は将来の adapter 差し替え候補とする。

| adapter | 生成 endpoint | health endpoint | request の要点 | response 抽出元 |
|---|---|---|---|---|
| `OLLAMA` | `POST /api/chat` | `GET /api/tags` | `model`, `messages`, `stream=false`, strict JSON format | `message.content` |
| `OPENAI_COMPATIBLE` | `POST /v1/chat/completions` | `GET /v1/models` | `model`, `messages`, `stream=false`, JSON object response format | `choices[0].message.content` |

model 名には `providers.llm.providers.{providerKey}.defaultModelProfileId` を使う。LLM ではこの field を Music Generation の profile map 参照として解釈せず、実 Provider へ送る model 名そのものとして扱う。`adapter`, `baseUrl`, `healthPath`, `timeoutMs`, `capabilities`, `defaultModelProfileId` は必須とし、`OPENAI_COMPATIBLE` で認証が必要な場合だけ `apiKeyRef` を設定する。既定 timeout は 20 秒とする。

LLM へ渡す `messages` は system prompt と `ScriptGenerationContext.prompt`、局・personality、信頼しないレター source data から作る。レター本文は `untrustedLetter` として分離し、system instruction として扱わない。adapter は response body が strict structured JSON であることを確認し、`text` と `safetyFlags` 以外の field を拒否してから `GeneratedScript` へ正規化する。`text` は 1 文字以上 20000 文字以下、`safetyFlags` は 32 件以下、各 flag は 1 文字以上 128 文字以下とする。空応答、非 JSON、必須 field 欠落、未知 field、型不正、上限超過は `PROVIDER_BAD_RESPONSE` とする。

LLM の Provider 試行は 1 試行ごとに `provider_job` を作り、同じ論理生成要求では `correlationId` を継承する。回復可能な `PROVIDER_UNREACHABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_RESOURCE_EXHAUSTED` だけを次 Provider へ送る。`PROVIDER_REJECTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED` は別 Provider へ同じ prompt を送らない。外部 Provider で成功しなかった場合は `TemplateScriptProvider` の安全な定型台本へ縮退し、この fallback も `providerKey=template-script` の別 `provider_job` として記録する。

### 5.2 Irodori OpenAI TTS adapter

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
| `VoiceProfileEntity.speakerKey` | `voice`。Irodori server の `voices/` または `voices.json` の voice id |
| `VoiceProfileEntity.speed` | `speed` |
| `VoiceProfileEntity.providerOptions` の `responseFormat` | `response_format`。既定は `wav` |
| `VoiceProfileEntity.providerOptions` の `irodori` | `irodori` object。`num_steps`, CFG, chunking などの安全な allowlist のみ |

`response_format=wav` を標準かつ唯一の許可形式とし、既存 `/api/assets/audio/{assetId}.wav` 契約を崩さない。`AssetService` は `TtsRuntimeProfileResolver` で生成直前の station と既定 VoiceProfile を解決し、`ProviderRegistry.resolveChain(ProviderType.TTS, preferredProviderKey)` で `VoiceProfileEntity.providerKey` を先頭にする。`HttpTtsProvider` は `SpeechDirective.normalizedText`、検証済み `speed`、canonical voice id、allowlist 済み `providerOptions.irodori`、`providers.tts.providers.{key}.defaultModelProfileId` から `/v1/audio/speech` を呼び、provider job と audio asset を作成する。`mp3` などは asset manifest / content type の拡張時に許可する。

`VoiceProfileEntity.scope` は `GLOBAL` / `STATION` に限定する。`GLOBAL` では `stationId=null`、`STATION` では `stationId` を必須とし、station の `defaultVoiceProfileId` へ他局の profile を割り当てる要求は拒否する。

`VoiceProfileEntity.referenceVoiceRef` は Provider 側 voice id または安全な相対参照だけを受け付ける。絶対 path、URL、`..` による親 directory traversal は拒否し、参照を指定する場合は `consentPolicyRef` を必須とする。`providerOptions` は Provider ごとの安全な allowlist に限定し、参照音声の実 path、個人名、秘密値を含めない。これらは API response、SSE、標準ログにも露出させない。

### 5.3 VOICEVOX TTS adapter

VOICEVOX は Irodori 失敗時にも使える安定 fallback として、Java Server から HTTP で呼び出す。

request mapping:

| SeedShiftRadio | VOICEVOX |
|---|---|
| `SpeechDirective.normalizedText` | `/audio_query?text=...` の `text` |
| `voiceHint=VOICEVOX:<speakerId>[:style]` | `/audio_query` と `/synthesis` の `speaker` |
| 既定 provider | `providers.tts.defaultProvider` / `fallbackProviders` の順に解決 |

現行実装では `/audio_query` の JSON をそのまま `/synthesis` へ渡し、戻った WAV を `generated_asset` として保存する。audio asset metadata には `normalizedTextHash`, `providerKey`, `adapter`, `speakerKey`, `styleKey`, `voiceHintHash`, `pronunciationHintCount`, `pauseHintCount` のような短い値だけを残し、raw `voiceHint`、本文、prompt、letter body、raw provider response、秘密値は入れない。Irodori の参照音声と同意参照は `referenceVoiceHash`, `consentPolicyHash` として保存する。

重要な制約:

- Irodori-TTS-Server は `stream_format=sse` で chunk-level SSE を提供する。OpenAI SDK の通常の streaming response は完成音声を逐次転送するだけなので、両者を区別する
- 現行 SeedShiftRadio adapter は `stream_format=sse` を採用せず、完成 WAV を Server 管理 asset として保存する。health metadata では upstream 能力を `upstreamChunkSseAvailable`、adapter の採用状態を `adapterStreamingEnabled=false` として分離する
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
| Music Generation | submit/poll は provider timeout、完了待ちは 180 秒 | 即時再試行なし。許可された error code に限る fallback provider、または上位の queue planning による新規論理要求で回復する |

再試行時は `correlationId` を継承する。

Provider chain の fallback を許可する error code は `PROVIDER_UNREACHABLE`, `PROVIDER_TIMEOUT`, `PROVIDER_BAD_RESPONSE`, `PROVIDER_RESOURCE_EXHAUSTED` と、TTS 固有の `VOICE_REF_NOT_FOUND`, `VOICE_CONSENT_REQUIRED` に限定する。
`PROVIDER_REJECTED`, `PROVIDER_AUTH_FAILED`, `PROVIDER_INTERRUPTED` では同じ要求を別 Provider へ自動送信しない。
ただし Provider chain の fallback を行わない場合でも、上位の playout は安全な cache、archive、local asset、placeholder による縮退継続を選べる。

### 6.1 stale `RUNNING` の回収

Server process の停止や中断で `provider_job.status=RUNNING` のまま残った job は、`ProviderJobRecoveryJob` が `updated_at` を基準に stale 判定する。
回収処理は `seedshift.radio.provider-job.recovery.enabled` で有効化し、`seedshift.radio.provider-job.recovery.stale-after=15m`, `seedshift.radio.provider-job.recovery.fixed-delay=60s`, `seedshift.radio.provider-job.recovery.batch-size=100` を既定として個別に変更できる。
`stale-after` は正の Duration を必須とし、`batch-size` は実行時に 1 から 1000 の範囲へ補正する。
1 回の実行では batch 上限までを処理し、残件は次回周期へ持ち越す。

stale job は条件付き更新で `FAILED`、`error_code=PROVIDER_INTERRUPTED`、`ended_at=回収時刻` へ確定し、通常の失敗と同じ `provider.job.failed` を safe metadata だけで配信する。
`PROVIDER_INTERRUPTED` は Provider chain の fallback 対象外であり、`provider_job` には request 本文も再投入に必要な完全な payload も保存しないため、回収処理は同じ要求を自動再送しない。
再生に必要な生成物は、上位の queue warmup / refill / planning が新しい論理要求として補充し、古い job の `correlationId` を再利用しない。
これにより、process 復旧後の回収は生成要求の二重送信を避けながら、再生経路の通常の縮退・補充へ収束する。

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
- `adapter`: LLM は `OLLAMA` または `OPENAI_COMPATIBLE`、Music Generation は `MUSICGEN_WORKER` または `ACE_STEP`
- model name / model profile
- timeout
- provider fingerprint
- credential ref / `apiKeyRef`
- cache policy
- preGenerationMode
- TTS adapter (`VOICEVOX`, `IRODORI_OPENAI_TTS` など)

機密値は `env:` または `file:` 参照とする。`apiKeyRef` は参照名だけを保存し、実値は API response、SSE、標準ログへ出さない。参照先が未設定、空、読み取り不能の場合、接続テストと実行経路は `PROVIDER_AUTH_FAILED` として扱い、health endpoint が匿名で成功しても `UP` にしない。
LLM の `defaultModelProfileId` は model 名として使い、`modelProfiles` の存在を要求しない。LLM endpoint の `timeoutMs` を省略する場合は 20000 ms を既定とする。
LLM の接続確認では `OLLAMA` は `GET /api/tags`、`OPENAI_COMPATIBLE` は `GET /v1/models` から model 一覧を取得し、`providerHealth.metadata.models`, `selectedModel`, `selectedModelAvailable` に短い値だけを返す。接続自体が成功しても指定 model が一覧に存在しない場合は `DEGRADED` とし、台本生成前に設定不備を発見できるようにする。model 一覧取得だけが失敗した場合は `modelsStatus=UNAVAILABLE` とし、health endpoint の結果まで直ちに `DOWN` へ落とさない。

Server は実行経路を `provider_job` と `generated_asset` に残し、`queue_item.assetId` から再生資産へ辿れるようにする。worker 未接続の段階では placeholder provider 経路で同じ永続化契約を先に満たしてよい。`config.json.cache` の reuse scope は cache hit 判定と eviction の設計基盤になり、script、TTS、MusicGen が cache-first 再利用へ接続済みである。MusicGen では station `preGeneration.preferCacheReuse=false` の場合に reusable asset が存在しても worker submit を優先する。

### 8.1 Cache-first 実行

1. Provider identity、reuse scope と正規化入力から `cacheKey` を計算し、`generated_asset.cache_key` を reuse scope に従って検索する
2. hit した場合は Provider 呼び出しを省略し、`queue_item.content_origin=CACHE_REUSED` を記録する
3. miss した場合のみ Provider を呼び出す
4. 完了 asset は `byte_size`, `reuse_scope`, `expires_at`, `archive_eligible` を付けて保存する
5. `archive_eligible=true` かつ安全条件を満たすものは `broadcast_archive` へ昇格可能にする

現行実装は script、TTS、MusicGen で `generated_asset.cache_key` と Provider/request の正規化入力を使って再利用候補を探す。script と TTS の orchestration は cache hit でも現在の queue item と correlationId を持つ論理 `provider_job` を作り、`external_ref=cache-hit:{sourceAssetId}`、clone asset の `sourceAssetId` と現在の `providerJobId` で追跡する。TTS audio metadata は `scriptAssetId` と `scriptProviderJobId` も持ち、成功した script job から最終 audio asset までを辿れる。`LETTER` 由来は script / TTS とも通常 cache を再利用しない。

### 8.2 Music generation profile

`providers.musicGen.providers.{providerKey}` は通常の endpoint field に加えて以下を持つ。

| Field | 用途 |
|---|---|
| `adapter` | `MUSICGEN_WORKER` または `ACE_STEP` |
| `apiKeyRef` | `env:` / `file:` 形式の秘密値参照。ACE-Step では `env:ACESTEP_API_KEY` を標準とする |
| `defaultModelProfileId` | 未指定 request の profile id |
| `modelProfiles` | profile id から model 設定への map |

ACE-Step profile は `model`, `lmModel`, `thinking`, `lyricsLanguage`, `lyricsTransliterationMode`, `outputFormat`, `maxDurationSeconds` を持つ。既定は `ace-ja-fast` で、`vocal_language=ja`, `thinking=true`, `outputFormat=wav` を送る。prompt / lyrics 本文は `promptHash` / `lyricsHash` に変換してから保存・監視に渡す。

### 8.3 ACE-Step runtime probes

ACE-Step は `/health`, `/v1/models`, `/v1/stats` を監視に使える。`/v1/models` は model 一覧と既定 model、`/v1/stats` は queue size、queued/running jobs、平均処理時間を返す前提とする。`/health` と `/v1/models` が匿名で成功する実装でも、保護対象の `/v1/stats`, `/release_task`, `/query_result`, `/v1/audio` に同じ Bearer token が必要なため、Server container へ `ACESTEP_API_KEY` を必ず注入する。監視 UI は生成本文ではなく、provider key、adapter、profile id、分類済み失敗理由だけを表示する。

### 8.4 TTS provider profile

`providers.tts.providers.{providerKey}` は通常 endpoint に加え、必要に応じて `adapter`, `apiKeyRef`, `defaultModelProfileId` を持つ。TTS 固有の細かい request option は provider endpoint ではなく `VoiceProfileEntity.providerOptions` に寄せ、station/persona ごとの差し替えをしやすくする。永続化済み option は `TtsRuntimeProfileResolver` の scope・同意・速度検証後に `HttpTtsProvider` へ渡し、allowlist 済み field だけを runtime request へ反映する。

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

### 8.5 管理画面からのオフエア事前生成

`POST /api/management/stations/{stationId}/pre-generations` は `PreGenerationJobCoordinator` を介して `PreGenerationJob` を JobRunr へ投入する。
background job server が無効な test / local 実行では、既存 queue job と同様に transaction commit 後のインライン実行へ切り替える。

`PreGenerationJob` は `playout_session.purpose=PRE_GENERATION` の session に対して、`ProgrammingService.resolvePreGenerationPlan` で現在の局ルールまたは指定した有効 template を解決し、`program_block`, `program_block_slot`, `queue_item` を materialize する。
script / TTS は既存 `AssetService` / `SpeechAssetGenerationService`、`MUSIC_AI` は既存 `GenerateMusicJob` を使い、別の Provider 契約を増やさない。
事前生成 MusicGen の成功・失敗はオフエア queue item と `provider_job` だけを更新し、`radio.status.changed`, `queue.updated`, `program.changed` のライブ SSE を発行しない。

`PreGenerationRequestStatus.MATERIALIZED` は番組データと queue item の作成、および MusicGen job 投入が完了した状態である。
全 MusicGen job の成功待ちではないため、運用者は `/api/management/dashboard` の局別 asset 集計と `/api/monitor/summary` の `runningJobs` / `recentErrors` を併用する。
request / response / log には prompt、lyrics、台本本文、レター本文、radioName、秘密値を含めない。

## 9. 推奨 OSS と使い分け

| 領域 | 第一候補 | 代替 |
|---|---|---|
| LLM | JDK `HttpClient` + Ollama | OpenAI 互換 API, Spring AI, llama.cpp サービス |
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
- `metadata`: LLM では adapter、model 名、Provider key などの短い診断値だけを許可し、prompt、レター本文、raw response、`apiKeyRef` の解決値は含めない

`metadata` と `message` は診断用の短い状態値に限定する。Web は provider 契約違反の payload が混ざった場合も、secret / prompt / lyrics / letter body / radioName / raw response らしい key や値を redaction し、worker status detail は許可済み metadata key の短い値だけを表示する。

上記 Payload は `/api/monitor/summary` と `/api/health` で `providerHealth` map として返す。key は `llm`, `tts`, `musicGen`、value は各 Provider 種別の `ProviderHealthPayload` とし、SSE `provider.health.changed` イベントでも同じ map 構造を送る。
`status` は `UP` で既定 Provider が正常、`DEGRADED` で既定 Provider は失敗したが fallback Provider が利用可能、`DOWN` で Provider chain 内に利用可能な Provider がない状態とする。
Provider health が `DOWN` でも cache、local asset、placeholder により放送を継続できる間、playout は `ERROR` ではなく `DEGRADED` を維持する。
`/api/health`, `/api/monitor/summary`, SSE `provider.health.changed` は同じ意味の status を返す。SSE の `Last-Event-ID` で再接続すると最新状態を受け取れる。

Health は `/api/health` と `/api/monitor/summary` に集約する。

## 11. テスト戦略

- Provider ごとの contract test を用意する
- mock provider を標準実装として持つ
- Ollama fake HTTP server で `/api/tags`, `/api/chat` の request mapping、strict JSON response、空応答、malformed response、timeout、429/503 を再現する
- OpenAI 互換 fake HTTP server で `/v1/models`, `/v1/chat/completions` の request mapping、Bearer 認証、401/403、空 choices、malformed response を再現する
- LLM fallback test では失敗試行と成功試行が別 `provider_job` になり、同じ `correlationId` を持つこと、全滅時に `TemplateScriptProvider` へ縮退することを確認する
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
