# AIローカルラジオアプリ Provider連携設計書

## 1. 目的

本書は `LLM`, `TTS`, `MusicGen` を統一原則で差し替え可能にするための抽象化と接続方式を定義する。

## 2. 共通方針

- 上位層は Provider 固有 API を直接呼ばない
- Provider ごとの差異は `Capability` と `Metadata` で吸収する
- HTTP を第一候補とし、必要時のみ CLI 連携を許容する
- タイムアウト、リトライ、エラー分類は共通化する

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

機密値は `env:` または `file:` 参照とする。

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

Health は `/api/health` と `/api/monitor/summary` に集約する。

## 11. テスト戦略

- Provider ごとの contract test を用意する
- mock provider を標準実装として持つ
- タイムアウト、異常応答、空応答、部分成功を再現できるようにする

## 12. 設計上の注意

- Provider 抽象で差異を隠し過ぎず、`capabilities` を上位から参照できるようにする
- 音声と音楽の生成物はファイル正本を Server が管理する
- 監視画面には生の provider error ではなく整形した分類を表示する
- Provider request metadata には `stationId`, `programTemplateId`, `programSlotId` を含め、監査とキャッシュに利用できるようにする
