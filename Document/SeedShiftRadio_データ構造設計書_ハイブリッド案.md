# SeedShiftRadio データ構造設計書（DB＋設定＋ログ：ハイブリッド）

- 文書名：SeedShiftRadio データ構造設計書
- 対象：MVP（短尺セグメント＋先読み）を中心に、設定・永続化・ログのデータ構造を確定する
- 前提：主設定は **config.json**、軽量設定は **user.config**、レター等の運用データは **SQLite**、技術ログは **ファイル（ローテーション）**

---

## 1. 目的とスコープ

本書は、以下を具体化する。

1. 設定データ（主設定／軽量設定／機密参照）
2. 永続データ（SQLite：レター、スレッド、放送内取り上げ履歴、聴取/生成履歴）
3. ログ（ファイルログ：運用・障害調査）と、履歴（DB：UIで一覧・検索するデータ）の切り分け

---

## 2. データ分類（最上位方針）

| 区分 | 目的 | 形式 | 例 | エクスポート対象 |
|---|---|---|---|---|
| 主設定 | 共有/移植したいアプリ設定 | `config.json` | Station定義、周波数、seed、編成、LLM/TTS、音源ルール、キャッシュ | 対象 |
| 軽量設定 | 端末依存のUI状態 | `user.config`（WPF標準） | ウィンドウサイズ、最終局、音量、ペイン開閉 | 対象外 |
| 機密情報 | APIキー等（直書き禁止） | 参照方式 | `apiKeyRef=env:OPENAI_API_KEY` | 対象外 |
| 運用データ | レター/履歴など検索対象 | SQLite | レター、スレッド、放送履歴、聴取履歴 | 任意（バックアップ/移行） |
| 技術ログ | 障害調査・観測 | ローテーションファイル | 生成失敗、例外、リトライ、I/O | 対象外（必要なら持ち出し） |

> 備考：UI上の保存先区分は「主設定：config.json」「軽量設定：user.config」を明確に分離する（設計書方針）。

---

## 3. ディレクトリ・ファイル配置（推奨）

### 3.1 デフォルト配置
- **インストール形態**（推奨）：ユーザープロファイル配下（書き込み権限確保）
- 既定ルート：`%LocalAppData%\SeedShiftRadio\`（例）
  - `config\config.json`
  - `data\app.db`
  - `cache\...`
  - `logs\app-YYYYMMDD.log`

### 3.2 パス解決ルール
1. `config.json` のパスが指定されている場合：**configファイルのディレクトリ**を基準に相対解決
2. 指定がない場合：既定ルート（LocalAppData）に自動生成
3. `paths.*` が設定されている場合：その値を最優先

---

## 4. 主設定（config.json）データ構造

### 4.1 バージョニング
- `configVersion`（int）を必須とし、起動時に検証する
- 将来は `configVersion` に応じてマイグレーション（互換変換）を実施できる設計とする

### 4.2 主要セクション（推奨）
- `app`：言語、起動時初期局等（共有してよい範囲）
- `paths`：data/cache/logs 等の保存先（相対/絶対）
- `playout`：短尺セグメント秒数、先読み数、フェード等
- `cache`：有効/無効、TTL、容量、削除ポリシー
- `providers`：LLM/TTS のデフォルト設定
- `templates`：番組編成テンプレ／プロンプトテンプレ（ID参照）
- `stations`：Station定義（離散周波数、seed、PersonalityProfile 等）

### 4.3 設定上書き（Override）ルール
優先順位（高→低）
1. `stations[].overrides`
2. グローバル `providers / playout / cache`
3. アプリ内デフォルト

### 4.4 機密参照（apiKeyRef）
- **禁止**：`apiKey` をconfigに直書き
- **許可**：`apiKeyRef` による参照のみ
- MVPの対応範囲：
  - `env:NAME`（環境変数）
- 将来拡張候補（設計余地のみ）
  - `wincred:...`（Windows資格情報）
  - `file:...`（ローカルファイル）
  - `dpapi:...`（暗号化文字列）

### 4.5 JSON構造（骨格例）
```json
{
  "configVersion": 1,
  "app": { "locale": "ja-JP", "startupStationId": "CH_0765" },
  "paths": { "dataDir": "./data", "cacheDir": "./cache", "logDir": "./logs" },
  "playout": { "segmentTargetSeconds": 45, "bufferSegments": 2, "fadeOutMsOnTune": 300 },
  "cache": {
    "enabled": true, "ttlDays": 30, "maxSizeMB": 2048, "evictPolicy": "LRU",
    "script": { "enabled": true }, "audio": { "enabled": true, "format": "wav" }
  },
  "providers": {
    "llm": { "type": "openai_compatible", "baseUrl": "http://localhost:1234/v1", "model": "local-model",
             "apiKeyRef": "env:OPENAI_API_KEY", "timeoutSeconds": 30,
             "retry": { "maxAttempts": 2, "backoffMs": 500 } },
    "tts": { "type": "aivoice", "aivoice": { "preset": "default" } }
  },
  "templates": { "scheduleTemplates": [], "promptTemplates": [] },
  "stations": []
}
```

### 4.6 Station定義（必須項目）
- `id`（一意）
- `frequencyMHz`（一意、離散値）
- `name / description / genre`
- `seed`（必須）
- `personalityProfile`（生成結果、必須）
- `personalityGenVersion`（推奨）
- `schedule`（テンプレID参照 または インライン定義）
- `musicRules`（音源フォルダ、シャッフル、重複回避など）
- `letterPolicy`（取り上げルール、読み上げ方針 等）

---

## 5. 軽量設定（user.config）データ構造（推奨キー）

WPF標準の `ApplicationSettingsBase` を使用する想定。

| Key | 型 | 例 | 用途 |
|---|---:|---|---|
| `LastStationId` | string | `CH_0765` | 次回起動の復元 |
| `MasterVolume` | double | 0.8 | 全体音量 |
| `BgmVolume` | double | 0.6 | BGM音量 |
| `VoiceVolume` | double | 0.9 | ボイス音量 |
| `WindowLeft/Top/Width/Height` | double | - | 画面状態 |
| `RightPaneSelectedTab` | string | `Captions` | 右ペインの状態 |
| `IsCaptionVisible` | bool | true | 字幕表示 |
| `ConfigPath` | string | - | config.jsonの任意配置を許す場合のみ |

ポリシー：
- 端末依存・UI状態に限定する（Station定義やProvider設定をここへ置かない）

---

## 6. ログ（ファイル）設計：技術ログ

### 6.1 目的
- 障害調査（生成失敗、I/O、Provider疎通、例外）
- 運用観測（キャッシュヒット率、リトライ回数、フォールバック発生）

### 6.2 形式
- 推奨：**JSON Lines（1行1イベント）**
  - 機械解析しやすく、UI（将来のログ画面）への取り込みも容易

### 6.3 ログイベント共通フィールド
| Field | 型 | 説明 |
|---|---|---|
| `ts` | string (ISO8601) | イベント時刻 |
| `level` | string | `DEBUG/INFO/WARN/ERROR` |
| `event` | string | イベント名（固定の識別子） |
| `message` | string | 人間可読メッセージ |
| `correlationId` | string | 1セグメント/1生成処理の相関ID |
| `stationId` | string? | 対象局 |
| `segmentType` | string? | `TALK/MUSIC/JINGLE/LETTER` 等 |
| `provider` | object? | providerType, model, voiceId 等（**キーは出さない**） |
| `error` | object? | 例外名、スタック（必要に応じ短縮） |

### 6.4 機密情報マスキング
- `apiKeyRef` は記録可（ただし画面表示/ログ出力は最小限）
- **APIキー実体**、生の個人情報、過度に長いプロンプト全文の記録は禁止
- レター本文をログに出す場合は、既定は **出さない**（必要なら `hash` / `length` のみ）

### 6.5 ローテーションと保持
- ローテーション：日次＋サイズ（例：1ファイル 20MB）
- 保持期間：14日（設定で変更可）
- UI操作：設定画面から「ログローテーション（保持）」の設定および「ログフォルダを開く」提供

### 6.6 ログ例（JSONL）
```json
{"ts":"2026-01-04T16:10:12+09:00","level":"INFO","event":"GenerateScript.Ok",
 "message":"script generated","correlationId":"01J...","stationId":"CH_0765","segmentType":"TALK",
 "provider":{"type":"openai_compatible","model":"local-model"},"metrics":{"ms":842}}
```

---

## 7. DB（SQLite）設計：運用データ＋履歴（UIで検索する）

### 7.1 DB概要
- ファイル：`data/app.db`
- 方針：**レター機能（MUST）**と、履歴/生成ログ（SHOULD）を段階的に追加できるスキーマ
- 文字列：UTF-8
- 時刻：ISO8601文字列（`TEXT`）で統一（`YYYY-MM-DDTHH:mm:ss.fffZ` またはローカル +09:00）

### 7.2 ER（概念）
- `letters` 1 --- N `letter_threads`
- `letters` 1 --- N `letter_airings`（放送での取り上げ履歴）
- `play_history`（聴取履歴：局/セグメント/曲）
- `generated_assets`（台本/音声資産メタ：キャッシュ索引兼用）

### 7.3 テーブル定義（必須）

#### 7.3.1 letters（レター本体）
| Column | Type | Null | Key | 説明 |
|---|---|---:|---|---|
| `id` | TEXT | NO | PK | ULID/UUID |
| `station_id` | TEXT | YES | IX | 宛先局。共通はNULL |
| `subject` | TEXT | YES |  | 件名 |
| `body` | TEXT | NO |  | 本文 |
| `radio_name` | TEXT | YES |  | ラジオネーム |
| `status` | TEXT | NO | IX | `UNREAD/PENDING/ADOPTED/REPLIED` |
| `created_at` | TEXT | NO |  | 作成 |
| `updated_at` | TEXT | NO |  | 更新 |
| `archived` | INTEGER | NO |  | 0/1（任意） |

制約：
- `status` は列挙値のみ許可（CHECK）
- 状態遷移はアプリ層で制御（DBは最小制約）

インデックス：
- `IX_letters_station_status_created`（`station_id, status, created_at`）

#### 7.3.2 letter_threads（スレッド：返信/追伸）
| Column | Type | Null | Key | 説明 |
|---|---|---:|---|---|
| `id` | TEXT | NO | PK | ULID/UUID |
| `letter_id` | TEXT | NO | FK,IX | 親レター |
| `author_type` | TEXT | NO |  | `USER/AI` |
| `message` | TEXT | NO |  | 本文 |
| `created_at` | TEXT | NO |  | 作成 |

制約：
- `FK(letter_id) REFERENCES letters(id) ON DELETE CASCADE`

#### 7.3.3 letter_airings（放送内取り上げ履歴）
| Column | Type | Null | Key | 説明 |
|---|---|---:|---|---|
| `id` | TEXT | NO | PK | ULID/UUID |
| `letter_id` | TEXT | NO | FK,IX | 対象レター |
| `station_id` | TEXT | NO | IX | 放送した局 |
| `aired_at` | TEXT | NO |  | 取り上げ時刻 |
| `segment_id` | TEXT | YES |  | 該当セグメント（任意） |
| `note` | TEXT | YES |  | 手動メモ（任意） |

用途：
- 「放送での取り上げ履歴」表示の根拠データ
- `letters` の `status=ADOPTED` と併用し、複数回放送も表現可能

---

## 8. DB（推奨：履歴/生成ログ）

### 8.1 play_history（聴取履歴）
| Column | Type | Null | Key | 説明 |
|---|---|---:|---|---|
| `id` | TEXT | NO | PK | |
| `station_id` | TEXT | NO | IX | |
| `segment_type` | TEXT | NO | IX | TALK/MUSIC/JINGLE/LETTER |
| `title` | TEXT | YES |  | 曲名/コーナー名等 |
| `started_at` | TEXT | NO | IX | |
| `ended_at` | TEXT | YES |  | |
| `correlation_id` | TEXT | YES |  | ファイルログ相関（任意） |

### 8.2 generated_assets（生成資産メタ＋キャッシュ索引）
| Column | Type | Null | Key | 説明 |
|---|---|---:|---|---|
| `id` | TEXT | NO | PK | |
| `kind` | TEXT | NO | IX | `SCRIPT/AUDIO` |
| `station_id` | TEXT | NO | IX | |
| `segment_type` | TEXT | NO |  | |
| `prompt_template_id` | TEXT | YES |  | |
| `input_hash` | TEXT | NO | IX | キャッシュキー（SHA-256等） |
| `provider_fingerprint` | TEXT | NO |  | model/voice等（キー無し） |
| `path` | TEXT | NO |  | 実ファイルパス |
| `duration_sec` | REAL | YES |  | 音声のみ |
| `created_at` | TEXT | NO | IX | |
| `last_accessed_at` | TEXT | YES | IX | LRU用（任意） |

LRU/TTL削除：
- TTLは `created_at` を基準
- LRUは `last_accessed_at` を基準（更新コストを嫌う場合は省略可）

---

## 9. SQLite DDL（初期マイグレーション v1）

```sql
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS letters (
  id TEXT PRIMARY KEY,
  station_id TEXT NULL,
  subject TEXT NULL,
  body TEXT NOT NULL,
  radio_name TEXT NULL,
  status TEXT NOT NULL CHECK (status IN ('UNREAD','PENDING','ADOPTED','REPLIED')),
  archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0,1)),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS IX_letters_station_status_created
  ON letters (station_id, status, created_at);

CREATE TABLE IF NOT EXISTS letter_threads (
  id TEXT PRIMARY KEY,
  letter_id TEXT NOT NULL,
  author_type TEXT NOT NULL CHECK (author_type IN ('USER','AI')),
  message TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (letter_id) REFERENCES letters(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS IX_letter_threads_letter_created
  ON letter_threads (letter_id, created_at);

CREATE TABLE IF NOT EXISTS letter_airings (
  id TEXT PRIMARY KEY,
  letter_id TEXT NOT NULL,
  station_id TEXT NOT NULL,
  aired_at TEXT NOT NULL,
  segment_id TEXT NULL,
  note TEXT NULL,
  FOREIGN KEY (letter_id) REFERENCES letters(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS IX_letter_airings_letter_aired
  ON letter_airings (letter_id, aired_at);

CREATE INDEX IF NOT EXISTS IX_letter_airings_station_aired
  ON letter_airings (station_id, aired_at);

-- Optional tables (enabled when history feature is turned on)
CREATE TABLE IF NOT EXISTS play_history (
  id TEXT PRIMARY KEY,
  station_id TEXT NOT NULL,
  segment_type TEXT NOT NULL,
  title TEXT NULL,
  started_at TEXT NOT NULL,
  ended_at TEXT NULL,
  correlation_id TEXT NULL
);

CREATE INDEX IF NOT EXISTS IX_play_history_station_started
  ON play_history (station_id, started_at);

CREATE TABLE IF NOT EXISTS generated_assets (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('SCRIPT','AUDIO')),
  station_id TEXT NOT NULL,
  segment_type TEXT NOT NULL,
  prompt_template_id TEXT NULL,
  input_hash TEXT NOT NULL,
  provider_fingerprint TEXT NOT NULL,
  path TEXT NOT NULL,
  duration_sec REAL NULL,
  created_at TEXT NOT NULL,
  last_accessed_at TEXT NULL
);

CREATE INDEX IF NOT EXISTS IX_generated_assets_hash
  ON generated_assets (input_hash);

CREATE INDEX IF NOT EXISTS IX_generated_assets_station_created
  ON generated_assets (station_id, created_at);
```

---

## 10. 状態・列挙型（アプリ共通定義）

### 10.1 LetterStatus
- `UNREAD`：投稿直後
- `PENDING`：保留（運用で一時停止）
- `ADOPTED`：採用（放送内で取り上げ対象）
- `REPLIED`：返信済（放送またはテキスト返信後）

### 10.2 SegmentType（例）
- `TALK` / `MUSIC` / `JINGLE` / `LETTER` / `ANNOUNCEMENT`（任意）

---

## 11. 運用ポリシー（保持・削除・復元）

### 11.1 ログ（ファイル）
- 既定：14日保持
- UI：ログクリア（全削除）/ フォルダを開く

### 11.2 DB（SQLite）
- 既定：無期限（ローカル用途）
- オプション：履歴（play_history / generated_assets）に保持上限（例：90日 or 10,000件）

### 11.3 キャッシュ（ファイル＋DBメタ）
- TTL/容量/LRUに従い削除
- 削除時：
  - 物理ファイル削除
  - `generated_assets` の該当行削除（整合）

---

## 12. 実装メモ（C#モデル境界）

- Config（主設定）：`AppConfig` 等のDTO → バリデーション → Domainへマッピング
- user.config：`UserSettings`（ApplicationSettingsBase）
- DB：Repository（UseCaseから呼ぶ）。UIは直接SQLiteに触れない（WinUI 3移行を見据えた層分離）

---

## 13. 未決事項（本書では「余地」を確保）
- 機密情報保管方式を env 以外に拡張するか（WinCred / DPAPI 等）
- ログ画面（将来）を「ファイルログ表示」にするか「DB取り込み」にするか
- 生成ログのDB保持粒度（MVPは play_history + generated_assets で十分。詳細はファイルログへ）
