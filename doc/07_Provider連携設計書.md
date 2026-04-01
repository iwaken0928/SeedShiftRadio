# AIローカルラジオアプリ Provider連携設計書

## 1. 目的

本書は `LLM`, `TTS`, `MusicGen` を統一原則で差し替え可能にするための抽象化と接続方式を定義する。

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

public interface MusicProvider {
    MusicJobResult generateMusic(MusicRequest request);
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
| MusicGen | HTTP 非同期 | `FastAPI Worker` を第一候補 |
| 例外 | CLI | モデル都合で HTTP 化が難しい時のみ許容 |

CLI 方式は worker ラッパーで吸収し、Java 本体から直接プロセス制御しない。

## 6. タイムアウト/リトライ

| Provider | Timeout | Retry |
|---|---|---|
| LLM | 20 秒 | 1 回 |
| TTS | 15 秒 | 1 回 |
| MusicGen | 180 秒 | 即時再試行なし。ジョブ再投入のみ |

再試行時は `correlationId` を継承する。

## 7. エラー分類

| Code | 意味 |
|---|---|
| `PROVIDER_UNREACHABLE` | 接続不可 |
| `PROVIDER_TIMEOUT` | 応答遅延 |
| `PROVIDER_BAD_RESPONSE` | 形式不正 |
| `PROVIDER_REJECTED` | 入力拒否 |
| `PROVIDER_RESOURCE_EXHAUSTED` | GPU/メモリ不足 |

## 8. 設定注入

- 接続 URL
- model name
- timeout
- provider fingerprint
- credential ref
- cache policy
- preGenerationMode

機密値は `env:` または `file:` 参照とする。

Server は実行経路を `provider_job` と `generated_asset` に残し、`queue_item.assetId` から再生資産へ辿れるようにする。worker 未接続の段階では placeholder provider 経路で同じ永続化契約を先に満たしてよい。`config.json.cache` の reuse scope は cache hit 判定と eviction の設計基盤になるが、現行実装では MusicGen の cache-first 再利用までが先行しており、retention/eviction の定期処理は未実装である。

### 8.1 Cache-first 実行

1. `contentHash` を計算し `generated_asset` を reuse scope に従って検索する
2. hit した場合は Provider 呼び出しを省略し、`queue_item.content_origin=CACHE_REUSED` を記録する
3. miss した場合のみ Provider を呼び出す
4. 完了 asset は `byte_size`, `reuse_scope`, `expires_at`, `archive_eligible` を付けて保存する
5. `archive_eligible=true` かつ安全条件を満たすものは `broadcast_archive` へ昇格可能にする

現行実装で cache-first が使われているのは MusicGen のみで、`generated_asset.cache_key` と provider/request の正規化入力を使って再利用候補を探す。`script` と `TTS` の cache-first も設計上は同じ契約だが、実処理と eviction job はまだ未接続である。

## 9. 推奨 OSS と使い分け

| 領域 | 第一候補 | 代替 |
|---|---|---|
| LLM | Spring AI + Ollama | OpenAI 互換 API, llama.cpp サービス |
| TTS | VOICEVOX | AivisSpeech, Style-Bert-VITS2 |
| MusicGen | FastAPI Worker + ACE-Step 系 | AudioCraft / MusicGen 系 |
| API文書 | springdoc-openapi | 手書き OpenAPI |
| 非同期ジョブ | JobRunr | Quartz |

## 10. ヘルスチェック

Provider ごとに以下を持つ。

- `status`: UP / DEGRADED / DOWN
- `lastCheckedAt`
- `responseTimeMs`
- `message`
- `capabilities`

上記 Payload は `/api/monitor/summary` と `/api/health` で JSON 配列として返され、SSE `provider.health.changed` イベントでも同じ構造を送る。
`status` は `UP` で正常、`DEGRADED` で代替 Provider へ切り替え中、`DOWN` で fallback に突入するシグナルとして解釈される。SSE の `Last-Event-ID` で再接続すると最新状態を受け取れる。

Health は `/api/health` と `/api/monitor/summary` に集約する。

## 11. テスト戦略

- Provider ごとの contract test を用意する
- mock provider を標準実装として持つ
- タイムアウト、異常応答、空応答、部分成功を再現できるようにする

## 12. 設計上の注意

- Provider 抽象で差異を隠し過ぎず、`capabilities` を上位から参照できるようにする
- 音声と音楽の生成物はファイル正本を Server が管理する
- `provider_job.external_ref` は worker 側 `jobId` や Provider 側 request id を保持し、未接続時は placeholder 実装の識別子を入れてよい
- 監視画面には生の provider error ではなく整形した分類を表示する
- Provider request metadata には `stationId`, `programTemplateId`, `programSlotId` を含め、監査とキャッシュに利用できるようにする
- Provider result metadata には `contentHash`, `byteSize`, `duration`, `archiveEligible` を含め、cleanup と replay promotion に利用できるようにする
