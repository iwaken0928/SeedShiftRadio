# SeedShiftRadio 設定スキーマ仕様書（config.json：スキーマ／バリデーション／マイグレーション）

- 版: 0.1（ドラフト）
- 作成日: 2026-01-04
- 対象: SeedShiftRadio（Windowsローカル / WPF、将来WinUI 3移行考慮）
- 参照:
  - 要件定義書 v0.3（設定は基本設定ファイル、`configVersion`、`apiKeyRef`参照方式、キャッシュ制御） fileciteturn0file0
  - アーキテクチャ方針設計書 v0.1（主設定: config.json、軽量設定: user.config、差し替え設計） fileciteturn0file1
  - データ構造設計書（ハイブリッド）v0.1（config骨格、パス解決、機密参照、SQLite/ログの切り分け） fileciteturn0file3

---

## 1. 目的・スコープ

本書は、主設定ファイル **config.json** について、以下を実装可能な粒度で定義する。

- **スキーマ**（JSON Schema）: 設定ファイルの形式的な構造・型・必須/任意・列挙値・制約
- **バリデーション**: 起動時/設定画面保存時/インポート時の検証手順とエラー報告仕様
- **マイグレーション方針**: `configVersion` に基づく互換変換、後方互換、破壊的変更の扱い

> 位置づけ: config.json は「共有/移植したい設定（Station定義、プロバイダ、キャッシュ等）」を保持し、端末依存UI状態は user.config（WPF標準）に保持する。 fileciteturn0file1turn0file3

---

## 2. config.json の基本方針

### 2.1 文字コード・フォーマット
- 文字コード: UTF-8（BOMなし推奨）
- 改行: CRLF/ LF はどちらでも可（内部では LF へ正規化してもよい）
- コメント: **非対応**（JSON準拠）。コメントが必要なら `notes` 等のフィールドを設ける。

### 2.2 ファイル配置・パス解決
- 既定配置は `%LocalAppData%\SeedShiftRadio\config\config.json` を推奨。 fileciteturn0file3
- `paths.*` が相対パスの場合は **config.json のディレクトリ**を基準に解決する。 fileciteturn0file3

### 2.3 機密情報
- APIキー等の機密値を config.json に直書きしない。
- `apiKeyRef` により参照する（MVP: `env:NAME`）。 fileciteturn0file0turn0file3

---

## 3. バージョニング（configVersion）

### 3.1 原則
- `configVersion`（int）は **必須**。
- アプリは **最新バージョン（Latest）** のスキーマを1つ持ち、起動時に
  1) 読み込み → 2) マイグレーション（必要なら） → 3) 検証 → 4) 正規化 → 5) 実行時設定として確定
  の順で処理する。

### 3.2 バージョンの意味
- `configVersion` はスキーマ互換性の単位（**構造や意味が変わる**場合に上げる）。
- 「デフォルト値の追加」「任意フィールドの追加」のみで後方互換を維持できるなら、**同一 version のまま**でも良いが、
  - スキーマファイル配布と実装の同期が難しくなるため、運用上は **スキーマ変更が入る場合は基本的に version を上げる**。

### 3.3 後方互換 / 前方互換
- 後方互換（旧→新）: **マイグレーションで吸収**する。
- 前方互換（新→旧）: 原則保証しない（旧アプリでは読み込めない可能性がある）。
- ただし、未知フィールドを読み捨てないために、DTOには `JsonExtensionData` 相当を用意し、**可能な範囲でラウンドトリップ保存**できる設計を推奨する。

---

## 4. スキーマ仕様（v1）

本書のスキーマ（v1）は、データ構造設計書の骨格を正とする。 fileciteturn0file3

### 4.1 トップレベル

| フィールド | 型 | 必須 | 既定 | 説明 |
|---|---|---:|---|---|
| `configVersion` | int | Yes | - | スキーマバージョン（v1=1） |
| `app` | object | No | `{}` | 共有して良い全般設定（言語/起動時局など） |
| `paths` | object | No | `{}` | data/cache/logs 等の保存先 |
| `playout` | object | No | `{}` | セグメント長、先読み数、チューン時フェード等 |
| `cache` | object | No | `{}` | 有効/無効、TTL、容量、削除方式等 |
| `providers` | object | No | `{}` | LLM/TTS のデフォルト設定 |
| `templates` | object | No | `{}` | 編成テンプレ、プロンプトテンプレ（ID参照） |
| `stations` | array | Yes | `[]` | Station定義（離散周波数） |

### 4.2 app

| フィールド | 型 | 必須 | 既定 | 説明 |
|---|---|---:|---|---|
| `locale` | string | No | `ja-JP` | 表示/生成の既定ロケール |
| `startupStationId` | string | No | null | 起動時に選択する局ID（存在しない場合は先頭局） |

### 4.3 paths

| フィールド | 型 | 必須 | 既定 | 説明 |
|---|---|---:|---|---|
| `dataDir` | string | No | `./data` | SQLite等のデータ保存先 |
| `cacheDir` | string | No | `./cache` | キャッシュ保存先 |
| `logDir` | string | No | `./logs` | ログ保存先 |

### 4.4 playout

| フィールド | 型 | 必須 | 既定 | 制約 | 説明 |
|---|---|---:|---|---|---|
| `segmentTargetSeconds` | int | No | 45 | 10〜300 | 目標セグメント長（短尺セグメント方式） fileciteturn0file0 |
| `bufferSegments` | int | No | 2 | 0〜10 | 先読みバッファ数 fileciteturn0file0 |
| `fadeOutMsOnTune` | int | No | 300 | 0〜5000 | 局切替時フェードアウト |

### 4.5 cache

| フィールド | 型 | 必須 | 既定 | 制約 | 説明 |
|---|---|---:|---|---|---|
| `enabled` | bool | No | true | - | キャッシュ全体のON/OFF fileciteturn0file0 |
| `ttlDays` | int | No | 30 | 1〜3650 | TTL（日） |
| `maxSizeMB` | int | No | 2048 | 0〜1048576 | 最大容量（0=無制限） |
| `evictPolicy` | string | No | `LRU` | `LRU`/`TTL`/`SIZE` | 削除方式 |
| `script.enabled` | bool | No | true | - | 台本キャッシュ |
| `audio.enabled` | bool | No | true | - | 音声キャッシュ |
| `audio.format` | string | No | `wav` | `wav`/`mp3` | 音声フォーマット（MVPはwav推奨） |

### 4.6 providers（デフォルト）

#### 4.6.1 providers.llm

| フィールド | 型 | 必須 | 既定 | 説明 |
|---|---|---:|---|---|
| `type` | string | Yes | - | `openai_compatible`（MVP） |
| `baseUrl` | string | Yes | - | OpenAI互換APIのベースURL |
| `model` | string | Yes | - | モデル名 |
| `apiKeyRef` | string/null | No | null | 参照方式（例: `env:OPENAI_API_KEY`） fileciteturn0file0turn0file3 |
| `timeoutSeconds` | int | No | 30 | タイムアウト |
| `retry.maxAttempts` | int | No | 2 | リトライ回数 |
| `retry.backoffMs` | int | No | 500 | リトライ間隔 |

#### 4.6.2 providers.tts

| フィールド | 型 | 必須 | 既定 | 説明 |
|---|---|---:|---|---|
| `type` | string | Yes | - | `aivoice`（MVP） fileciteturn0file1 |
| `aivoice.preset` | string | No | `default` | A.I.VOICE側のプリセット |

> 注: 将来、HTTP型TTSなどが増える場合は `oneOf`（discriminator: `type`）で拡張する。

### 4.7 templates（最小）

| フィールド | 型 | 必須 | 既定 | 説明 |
|---|---|---:|---|---|
| `scheduleTemplates` | array | No | `[]` | 編成テンプレ定義（ID参照） |
| `promptTemplates` | array | No | `[]` | プロンプトテンプレ定義（ID参照） |

### 4.8 stations（必須）

#### 4.8.1 Station必須要素（要件準拠）
Stationは「離散周波数」「seed保存」「PersonalityProfile保存」を満たす必要がある。 fileciteturn0file0turn0file1

| フィールド | 型 | 必須 | 説明 |
|---|---|---:|---|
| `id` | string | Yes | 一意な局ID |
| `frequencyMHz` | number | Yes | 周波数（離散値、**一意**） |
| `name` | string | Yes | 局名 |
| `description` | string | No | 説明 |
| `genre` | string | No | ジャンル |
| `seed` | int | Yes | パーソナリティ生成シード |
| `personalityProfile` | object | Yes | 生成結果（保存） |
| `personalityGenVersion` | int | No | 生成ロジックの版（推奨） fileciteturn0file0 |
| `scheduleTemplateId` | string | No | 編成テンプレ参照 |
| `schedule` | object | No | インライン編成（テンプレ無しの場合） |
| `musicRules` | object | No | 音源フォルダ等 |
| `letterPolicy` | object | No | レター取り上げルール |

#### 4.8.2 personalityProfile（推奨フィールド）
| フィールド | 型 | 必須 | 説明 |
|---|---|---:|---|
| `displayName` | string | No | 表示名 |
| `stylePrompt` | string | Yes | 口調・禁則等（LLMへ反映） |
| `topicBias` | array | No | 話題バイアス |
| `ngPolicy` | array | No | 禁止領域（例: `medical_diagnosis` 等） |
| `voiceProfile` | object | No | TTS用（voiceId/速度/ピッチ等） |

#### 4.8.3 musicRules（MVP）
| フィールド | 型 | 必須 | 説明 |
|---|---|---:|---|
| `folders` | array | Yes | 音源フォルダ（複数可） |
| `mode` | string | No | `random` 等 |

#### 4.8.4 letterPolicy（MVP）
| フィールド | 型 | 必須 | 説明 |
|---|---|---:|---|
| `pickRule` | string | No | `PICKED_FIRST` 等（採用優先） |
| `readMode` | string | No | `SUMMARY_THEN_ANSWER` 等 |

---

## 5. バリデーション仕様

### 5.1 バリデーションの層（必須）
1. **構文/型**: JSONとしてパース可能（例外時は即ERROR）
2. **スキーマ検証**: JSON Schemaに適合（ERROR/WARNを生成）
3. **意味検証（セマンティック）**: クロスフィールド制約、整合性、重複、依存関係
4. **正規化**: 既定値補完、相対パス→絶対パス、トリム、並び順調整（任意）
5. **実行時検証（オプション）**: 外部依存の存在確認（フォルダ存在、環境変数存在、Provider疎通など）
   - 実行時検証は基本 **WARN** とし、アプリ停止は避ける（「止めない設計」）。 fileciteturn0file1

### 5.2 セマンティックルール（v1：最低限）
- Station:
  - `stations[].id` は **一意**
  - `stations[].frequencyMHz` は **一意**
  - `frequencyMHz` は正の数（0より大）
  - `seed` は 0以上（推奨はint範囲）
- 参照整合:
  - `app.startupStationId` がある場合、stations内に存在（存在しない場合はWARN→自動fallback）
  - `scheduleTemplateId` がある場合、templates.scheduleTemplates内に存在（未実装ならWARN）
- cache:
  - `cache.enabled=true` のとき `paths.cacheDir` が空/未設定なら既定値へ補完
- providers:
  - `providers.llm.type=openai_compatible` のとき `baseUrl/model` 必須
  - `apiKeyRef` は `^(env):[A-Z0-9_]+$` に適合（不適合はERROR）
  - `apiKeyRef` が env の場合、環境変数未設定は **WARN**（設定画面の「接続テスト」でERRORにしてよい）

### 5.3 エラー報告フォーマット（推奨）
- `code`: 固定コード（例: `SCHEMA_REQUIRED`, `DUPLICATE_STATION_ID`）
- `path`: JSON Pointer 形式（例: `/stations/0/id`）
- `severity`: `ERROR` / `WARN`
- `message`: UI表示用（将来は i18n key でも可）

---

## 6. マイグレーション方針

### 6.1 実行タイミング
- 起動時（configロード時）
- 設定画面でファイル指定した直後（ロード）
- インポート（ファイル取り込み）時

### 6.2 マイグレーションの契約
- 入力: **旧versionの生JSON**
- 出力: **新versionの生JSON**（またはDTO）
- 原則:
  - **意味を変えない**（同一意図の表現を新形式へ移す）
  - 旧フィールドが欠けている場合は **既定値注入**
  - 削除されたフィールドは `deprecated.*` へ退避するのではなく、基本は破棄（ただしラウンドトリップ要件がある場合は拡張データに保持）

### 6.3 永続化（自動書き換え）ポリシー
- **読み込み時は自動マイグレーション**して内部では最新として扱う。
- ファイルの自動書き換えは、ユーザー選択（設定画面の「更新して保存」）を推奨。
  - ただし、`configVersion` が古すぎる場合（例: 3世代以上）や重大な互換変換がある場合は、起動時にバックアップを作成して自動保存してもよい。
- バックアップ: `config.json.bak-YYYYMMDD-HHMMSS`

### 6.4 マイグレーション実装方式（推奨）
- `IConfigMigration` を versionごとに用意し、チェーン適用する。

```csharp
public interface IConfigMigration
{
    int FromVersion { get; }
    int ToVersion { get; }
    JsonNode Migrate(JsonNode root);
}
```

- 例: v1→v2, v2→v3 … を順に適用し Latest へ到達させる。
- 各移行ステップごとに「最低限の構文/必須項目」をチェックし、最終段でスキーマ検証・セマンティック検証を行う。

### 6.5 変更の分類（versionを上げる基準）
- **Patch（version据え置き）**:
  - 任意フィールドの追加（旧を壊さない）
  - 既定値の追加
- **Minor（version+1推奨）**:
  - フィールド名の変更（旧→新へ移行が必要）
  - 型の変更（string→object 等）
  - 列挙値の追加（実行時意味が増える）
- **Major（version+1必須＋移行の注意喚起）**:
  - 既存フィールドの削除
  - 意味が変わる（例: 単位が秒→ミリ秒等）

---

## 7. 実装指針（C# / .NET）

### 7.1 推奨ライブラリ構成
- JSONパース/DTO: `System.Text.Json`
- JSON Schema検証: いずれか
  - JsonSchema.Net（Draft 2020-12対応）
  - NJsonSchema（Draft 7中心、運用要件により選定）
- DTOは source-generator を用いて起動性能を確保（任意）。

### 7.2 ロード手順（擬似コード）
```csharp
var raw = File.ReadAllText(path, Encoding.UTF8);
var node = JsonNode.Parse(raw) ?? throw new ConfigException("empty");

var version = (int?)node["configVersion"] ?? throw new ConfigException("missing configVersion");

// 1) migrate
node = migrationRunner.MigrateToLatest(node, version);

// 2) schema validate
var schemaErrors = schemaValidator.Validate(node);
if (schemaErrors.Any(e => e.Severity == Error)) throw new ConfigValidationException(schemaErrors);

// 3) semantic validate
var semantic = semanticValidator.Validate(node);
if (semantic.Any(e => e.Severity == Error)) throw new ConfigValidationException(semantic);

// 4) normalize & map
var dto = node.Deserialize<AppConfigDto>(jsonOptions);
return configNormalizer.Normalize(dto, configDir);
```

### 7.3 セマンティック検証の実装ポイント
- 「重複チェック」はhash-setで実装し、JSON Pointerを付けて返す。
- 「参照整合」は missing を WARN とし、UIで補正案を提示する（例: startupStationIdの選択し直し）。
- パス存在チェックは OS依存のため、ロード時はWARNに留め、設定画面の「接続テスト/スキャン」で確定する。

---

## 8. 付録: JSON Schema（v1：概要）

本仕様に対応する JSON Schema を `config.schema.json` として同梱する（Draft 2020-12）。

- `$schema`: https://json-schema.org/draft/2020-12/schema
- `$id`: `https://example.local/ai-local-radio/config.schema.json`（実運用ではプロジェクト固有のIDを設定）

※フルスキーマは別ファイル（同梱）を参照。

