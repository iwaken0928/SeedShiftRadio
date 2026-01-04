# SeedShiftRadio I/F仕様書（Providers / Audio / Cache / DB / ログ）

- 版：0.1（ドラフト）
- 作成日：2026-01-04
- 対象：SeedShiftRadio（Windowsローカルアプリ / WPF、将来WinUI 3移行考慮）
- 参照：
  - 要件定義書 v0.3（プロバイダ差し替え、キャッシュ、ログ、レター等） fileciteturn0file0
  - アーキテクチャ方針設計書 v0.1（層分離、A.I.VOICE採用、SQLite/ファイルログ方針） fileciteturn0file1
  - データ構造設計書（ハイブリッド） v0.1（config/user.config/SQLite/JSONLログ） fileciteturn0file3
  - プログラム概要設計書 v0.1（主要I/F一覧、フロー） fileciteturn0file4
  - コンポーネント／クラス設計書 v0.1（責務・依存・公開API） fileciteturn0file5

---

## 1. 目的・スコープ

本書は、実装に着手できる粒度で、以下5領域の **インターフェース仕様**（責務、入力/出力、例外、スレッド/キャンセル、ログ/機密対応）を定義する。

1. Providers（LLM/TTS/Factory/Secret解決/疎通テスト）
2. Audio（再生、音源選択、ジングル供給）
3. Cache（script/audioキャッシュの読み書き、管理）
4. DB（SQLite：レター/履歴/生成資産メタのRepository）
5. ログ（JSONLイベント、ローテーション、機密マスキング）

---

## 2. 共通規約（全I/Fに適用）

### 2.1 非同期・キャンセル
- すべてのI/O系I/Fは `Task` / `Task<T>` を返し、`CancellationToken ct` を必須とする。
- `ct` キャンセル時の期待動作：
  - **可能な限り早く中断**（外部HTTP/プロセス起動/ファイルI/Oの中断）。
  - 例外：`OperationCanceledException` を投げる（握りつぶさない）。
- 「局切替（Tune）」や「Stop」で、上位（PlayoutCoordinator/BufferManager）から一括キャンセルされる前提。 fileciteturn0file5

### 2.2 エラー分類（推奨）
I/F実装は、上位がフォールバック判断できるよう、例外分類をそろえる。

- `TransientException`：再試行が有効（タイムアウト、HTTP 429/503、ローカル起動直後等）
- `PermanentException`：再試行しても無効（設定ミス、モデル/音声ID不正、ファイル欠損等）
- `ValidationException`：入力不正（必須項目欠落、長さ超過等）
- `OperationCanceledException`：キャンセル（仕様通り）

※MVPでは「例外型を最小限にし、`IsTransient` フラグ付きの `ProviderException`」でも可。

### 2.3 相関ID（CorrelationId）
- 生成→TTS→再生→ログまで追跡性を担保するため、`CorrelationId` を必ず引き回す。 fileciteturn0file5
- 推奨形式：ULID（時系列ソート容易）。UUIDでも可。

### 2.4 機密情報の扱い
- `apiKeyRef`（例：`env:OPENAI_API_KEY`）の **参照文字列** は扱ってよいが、**キー実体**は扱わない/ログ出力しない。 fileciteturn0file3
- LLMへの入力（プロンプト）とレター本文は、デフォルトで **ログ全文を出さない**（hash/長さ/要約のみ）。 fileciteturn0file3

---

## 3. Providers I/F（LLM/TTS/Factory）

### 3.1 Provider共通モデル

#### 3.1.1 ProviderCapabilities
Providerが対応する機能を上位に示す（将来拡張の余地を確保）。

- `SupportsStreaming`（LLM/TTS）
- `SupportsStructuredOutput`（LLM）
- `SupportsVoiceParameters`（TTS：speed/pitch等）
- `SupportsHealthCheck`

#### 3.1.2 ProviderFingerprint（キャッシュキー用）
キャッシュの衝突を避けるため、プロバイダ差分を表す「秘密を含まない」指紋文字列。

- LLM例：`type=openai_compatible;model=local-model;baseUrl=http://localhost:1234/v1`
- TTS例：`type=aivoice;preset=default;voiceId=speaker_1`

> 重要：APIキーやトークン等、機密を指紋に含めない。

---

### 3.2 ILLMProvider（台本生成）

#### 3.2.1 責務
- `GenerateScriptContext` に基づき、読み上げ用台本テキストを生成する。 fileciteturn0file0
- Providerの差（OpenAI互換、将来Foundry Local等）を実装内に閉じ込める。 fileciteturn0file1

#### 3.2.2 入力：GenerateScriptContext（推奨フィールド）
- `CorrelationId`（必須）
- `StationId` / `StationName`
- `PersonalityProfile`（stylePrompt, topicBias, ngPolicy 等）
- `SegmentType`（TALK/LETTER…）
- `TargetDurationSec` または `TargetCharCount`
- `PromptTemplateId`（任意）
- `Inputs`（任意：レター本文/要約、直前セグメント要約、時刻等）
- `Locale`（任意）

#### 3.2.3 出力：台本テキスト
- 読み上げに適した文体、不要なメタ（システム指示の露出等）を含めない。 fileciteturn0file0
- 改行・句読点などはTTSの品質に影響するため、整形済みで返す（例：長すぎる連続記号を抑制）。

#### 3.2.4 例外
- タイムアウト/レート制限/一時障害：`TransientException`（上位はリトライ→フォールバック）
- 設定ミス（baseUrl不正/モデル不正）：`PermanentException`
- 入力不正：`ValidationException`

#### 3.2.5 公開API（C#案）
```csharp
public interface ILLMProvider
{
    Task<string> GenerateScriptAsync(GenerateScriptContext ctx, CancellationToken ct);
    ProviderCapabilities Capabilities { get; }
    string Fingerprint { get; } // secret-less
}
```

---

### 3.3 ITtsProvider（音声生成：A.I.VOICE含む）

#### 3.3.1 責務
- 台本テキストと `VoiceProfile` を受け取り、再生可能な音声資産（ファイル/バイト列）を生成して返す。 fileciteturn0file1
- **再生責務は持たない**（再生はAudio I/F側）。 fileciteturn0file1

#### 3.3.2 入力
- `text`：読み上げ文（必須）
- `voice`：`VoiceId`, `Rate`, `Pitch`, `Volume` 等（対応範囲はProviderCapabilitiesで表現）
- `CorrelationId`：ログ・追跡用（Contextに含めるか別引数）

#### 3.3.3 出力：AudioAsset
- `Format`（例：wav/mp3）
- `Path`（基本：ファイルパス。将来 `Bytes` を許容してもよい）
- `DurationSec?`（取得できる場合）

#### 3.3.4 例外
- 一時的失敗：`TransientException`
- パラメータ不正（voiceId存在しない等）：`PermanentException`
- 入力不正：`ValidationException`

#### 3.3.5 公開API（C#案）
```csharp
public interface ITtsProvider
{
    Task<AudioAsset> SynthesizeAsync(TtsRequest req, CancellationToken ct);
    ProviderCapabilities Capabilities { get; }
    string Fingerprint { get; } // secret-less
}

public sealed record TtsRequest(
    string CorrelationId,
    string Text,
    VoiceProfile Voice,
    string? Locale = null
);
```

---

### 3.4 IProviderFactory（設定→Provider生成）

#### 3.4.1 責務
- `ProviderConfig` から `ILLMProvider/ITtsProvider` を生成する。
- `apiKeyRef` を `ISecretResolver` で解決し、Provider実装へ渡す（ただしログ出力しない）。 fileciteturn0file3

#### 3.4.2 入力：ProviderConfig（概念）
- `type`（openai_compatible / foundry_local / aivoice 等）
- `baseUrl` / `model`
- `apiKeyRef`（`env:NAME` 等）
- `timeoutSeconds`
- `retry`（maxAttempts/backoffMs）
- Provider固有設定（A.I.VOICE preset 等）

#### 3.4.3 例外
- `apiKeyRef` 解決失敗：`PermanentException`（環境変数未設定等）
- 設定項目不足：`ValidationException`

#### 3.4.4 公開API（C#案）
```csharp
public interface IProviderFactory
{
    ILLMProvider CreateLlm(ProviderConfig cfg);
    ITtsProvider CreateTts(ProviderConfig cfg);
}
```

---

### 3.5 ISecretResolver（機密参照解決）

#### 3.5.1 責務
- `apiKeyRef` を解決して **秘密値（string）** を返す。
- MVP対応：`env:NAME` のみ。将来：WinCred/DPAPI/file等を拡張可能。 fileciteturn0file3

#### 3.5.2 公開API（C#案）
```csharp
public interface ISecretResolver
{
    // apiKeyRef: "env:OPENAI_API_KEY"
    string Resolve(string apiKeyRef);
}
```

---

### 3.6 Provider疎通テストI/F（SettingsUseCaseから利用）

#### 3.6.1 方針
- 設定画面から「接続テスト」を提供する想定。 fileciteturn0file2
- テストは副作用最小（短い固定文でTTS、軽いプロンプトでLLM）。

#### 3.6.2 公開API（案）
```csharp
public interface IProviderTester
{
    Task<ProviderTestResult> TestLlmAsync(ILLMProvider llm, CancellationToken ct);
    Task<ProviderTestResult> TestTtsAsync(ITtsProvider tts, CancellationToken ct);
}
```

---

## 4. Audio I/F（再生・音源・ジングル）

### 4.1 IAudioPlayer（再生）

#### 4.1.1 責務
- `AudioSource` を再生する（ファイル/ストリームを抽象化）。
- Stop/フェードアウトなどの停止制御（MVPでは即停止でも可、フェードは将来拡張）。 fileciteturn0file1
- 音量（マスター）制御（将来：BGM/Voice別に拡張可能）。 fileciteturn0file2

#### 4.1.2 入出力
- 入力：`AudioSource`（ファイルパス推奨）、`PlaybackOptions`（音量、フェード等）
- 出力：再生完了まで `Task` を待機（再生終了/キャンセル/停止で完了）

#### 4.1.3 スレッド・並行性
- 同時再生はMVPでは想定しない（単一再生）。将来ミキサ導入時に拡張。
- `PlayAsync` 呼び出し中に `StopAsync` が呼ばれた場合、`PlayAsync` は速やかに完了（またはキャンセル例外）する。

#### 4.1.4 公開API（C#案）
```csharp
public interface IAudioPlayer
{
    Task PlayAsync(AudioSource source, PlaybackOptions opt, CancellationToken ct);
    Task StopAsync(StopOptions opt, CancellationToken ct);

    Task SetMasterVolumeAsync(double volume, CancellationToken ct);
}

public sealed record AudioSource(
    string Kind,          // "File" | "Stream" (MVPはFile)
    string Path,          // file path when Kind=="File"
    string? ContentType = null,
    double? DurationSec = null
);

public sealed record PlaybackOptions(
    double? Volume = null,
    int FadeInMs = 0
);

public sealed record StopOptions(
    int FadeOutMs = 0
);
```

---

### 4.2 IMusicLibrary / ITrackSelector（音源ライブラリと選曲）

#### 4.2.1 責務
- `IMusicLibrary`：フォルダスキャン、拡張子フィルタ、トラック一覧提供
- `ITrackSelector`：Stationの `MusicRules` に基づき次曲を選ぶ（ランダム、重複回避など） fileciteturn0file0

#### 4.2.2 公開API（C#案）
```csharp
public interface IMusicLibrary
{
    Task<IReadOnlyList<MusicTrack>> ScanAsync(IReadOnlyList<string> folders, CancellationToken ct);
}

public interface ITrackSelector
{
    Task<MusicTrack> PickNextAsync(MusicRules rules, CancellationToken ct);
}

public sealed record MusicTrack(
    string Path,
    string? Title = null,
    string? Artist = null,
    double? DurationSec = null
);
```

---

### 4.3 IJingleProvider（ジングル/SE供給）

#### 4.3.1 責務
- フォールバックや演出用に、ジングル/案内音声（固定ファイル）を返す。 fileciteturn0file5
- Stationごとのジングル差し替え余地を残す（configでパス指定）。

#### 4.3.2 公開API（C#案）
```csharp
public interface IJingleProvider
{
    AudioSource GetStationJingle(StationId stationId);
    AudioSource GetFallbackJingle();
    AudioSource GetShortAnnouncement(); // 無音回避
}
```

---

## 5. Cache I/F（Script/Audioキャッシュ）

> キャッシュ方針（有効/無効、TTL/容量/LRU等）はconfigで制御。 fileciteturn0file3

### 5.1 CacheKey / CacheHit / CacheMetadata

#### 5.1.1 CacheKey（衝突回避の必須要素）
- `Kind`：SCRIPT / AUDIO
- `StationId`
- `SegmentType`
- `PromptTemplateId?`
- `InputHash`：入力から算出したSHA-256等（プロンプト全文の保存は避ける） fileciteturn0file3
- `ProviderFingerprint`：秘密を含まない指紋（3.1.2）
- `Format`：audioの場合（wav等）

#### 5.1.2 CacheHit<T>
- `IsHit`：命中
- `Value`：値（script文字列、AudioAsset等）
- `Meta`：`CacheMetadata`
- `Path`：ファイルベースの場合

#### 5.1.3 CacheMetadata（推奨）
- `CreatedAt`
- `LastAccessedAt?`（LRUを行う場合）
- `SizeBytes`
- `TtlDays?`（当時の設定値）

---

### 5.2 ICacheStore（読み書き）

#### 5.2.1 責務
- Script/Audioのキャッシュ読み書き（ファイル保存が基本）。
- 書き込みは **原子的** に行う（temp→rename）。停電/強制終了でも壊れにくくする。
- Audioは `AudioAsset.Path` を返す（再生側がそのまま再生できる）。 fileciteturn0file1

#### 5.2.2 例外
- キャッシュI/O失敗は原則として「致命」ではない。上位は **キャッシュ無しで続行** できるよう、例外は握りつぶさずに上位でWARNログ→継続が望ましい（止めない設計）。 fileciteturn0file1

#### 5.2.3 公開API（C#案）
```csharp
public interface ICacheStore
{
    Task<CacheHit<string>> TryGetScriptAsync(CacheKey key, CancellationToken ct);
    Task PutScriptAsync(CacheKey key, string script, CacheMetadata meta, CancellationToken ct);

    Task<CacheHit<AudioAsset>> TryGetAudioAsync(CacheKey key, CancellationToken ct);
    Task PutAudioAsync(CacheKey key, AudioAsset asset, CacheMetadata meta, CancellationToken ct);
}

public sealed record CacheKey(
    string Kind,                 // "SCRIPT" | "AUDIO"
    string StationId,
    string SegmentType,
    string InputHash,
    string ProviderFingerprint,
    string? PromptTemplateId = null,
    string? Format = null
);

public sealed record CacheHit<T>(bool IsHit, T? Value, CacheMetadata? Meta, string? Path);

public sealed record CacheMetadata(
    DateTimeOffset CreatedAt,
    DateTimeOffset? LastAccessedAt,
    long SizeBytes,
    int? TtlDays
);
```

---

### 5.3 ICacheAdminService（統計・削除）

#### 5.3.1 責務
- 統計（サイズ、件数、種別別、ヒット率：可能なら）を返す。
- Evict（TTL/LRU/容量超過）を実行する。
- UI（設定画面）から「キャッシュ削除」などに接続。 fileciteturn0file2

#### 5.3.2 公開API（C#案）
```csharp
public interface ICacheAdminService
{
    Task<CacheStats> GetStatsAsync(CancellationToken ct);
    Task EvictAsync(EvictionPolicy policy, CancellationToken ct);
    Task ClearAllAsync(CancellationToken ct);
}

public sealed record CacheStats(
    long TotalSizeBytes,
    int TotalEntries,
    int ScriptEntries,
    int AudioEntries
);

public sealed record EvictionPolicy(
    int? TtlDays = null,
    long? MaxSizeBytes = null,
    string Mode = "LRU" // "LRU" | "TTL" | "SIZE"
);
```

---

## 6. DB I/F（SQLite：Repository）

> 方針：レター等の運用データはSQLite、技術ログはファイル（JSONL）。 fileciteturn0file3

### 6.1 DB基盤I/F

#### 6.1.1 IDbConnectionFactory
- SQLite接続を生成する（接続文字列、WALモード、busy_timeout等を集約）。
- スレッドセーフ：呼び出しごとに新規接続を返す（推奨）。

```csharp
public interface IDbConnectionFactory
{
    // 例：Microsoft.Data.Sqlite.SqliteConnection
    DbConnection CreateConnection();
}
```

#### 6.1.2 IMigrationRunner
- 初期DDL適用、schemaVersion管理（MVPは「起動時にDDLを実行」でも可）。
- DDLはデータ構造設計書のv1に準拠。 fileciteturn0file3

```csharp
public interface IMigrationRunner
{
    Task InitializeAsync(CancellationToken ct);
}
```

---

### 6.2 ILetterRepository（必須：MUST）

#### 6.2.1 責務
- `letters / letter_threads / letter_airings` のCRUD（データ構造設計書準拠）。 fileciteturn0file3
- フィルタ（station/status/query）、ソート、ページング（必要なら）を提供。

#### 6.2.2 公開API（C#案：設計書準拠）
```csharp
public interface ILetterRepository
{
    Task InsertAsync(Letter letter, CancellationToken ct);
    Task<IReadOnlyList<Letter>> QueryAsync(LetterQuery query, CancellationToken ct);
    Task<Letter?> FindAsync(string id, CancellationToken ct);

    Task UpdateStatusAsync(string id, string status, CancellationToken ct);
    Task AddThreadAsync(LetterThreadMessage msg, CancellationToken ct);
    Task AddAiringAsync(LetterAiring airing, CancellationToken ct);
}

public sealed record LetterQuery(
    string? StationId = null,
    string? Status = null,    // UNREAD/PENDING/ADOPTED/REPLIED
    string? TextQuery = null, // subject/body 部分一致（MVP）
    int? Limit = null,
    int? Offset = null
);
```

#### 6.2.3 トランザクション境界（推奨）
- 「返信生成」などで `letters.status` 更新＋ `letter_threads` 追加を同時に行う場合、Repository側（またはUseCase側）でトランザクションを張る。
- MVPでは UseCase側で `BeginTransaction` を張り、Repositoryメソッドを複数呼ぶ設計でも可。

---

### 6.3 履歴系Repository（任意：SHOULD）

#### 6.3.1 IPlayHistoryRepository
```csharp
public interface IPlayHistoryRepository
{
    Task InsertAsync(PlayHistoryItem item, CancellationToken ct);
    Task<IReadOnlyList<PlayHistoryItem>> QueryAsync(PlayHistoryQuery query, CancellationToken ct);
}
```

#### 6.3.2 IGeneratedAssetRepository（キャッシュ索引兼用）
データ構造設計書の `generated_assets` を利用し、キャッシュ管理（LRU/TTL）と整合する。 fileciteturn0file3

```csharp
public interface IGeneratedAssetRepository
{
    Task UpsertAsync(GeneratedAssetMeta meta, CancellationToken ct);
    Task<GeneratedAssetMeta?> FindByHashAsync(string inputHash, string providerFingerprint, CancellationToken ct);
    Task TouchAsync(string id, DateTimeOffset accessedAt, CancellationToken ct);
    Task DeleteAsync(string id, CancellationToken ct);
}
```

---

## 7. ログ I/F（JSON Lines + ローテーション）

### 7.1 ログ方針（要点）
- 形式：JSON Lines（1行1イベント）。機械解析と将来のログ画面取り込みを両立。 fileciteturn0file3
- ローテーション：日次＋サイズ、保持期間（例：14日）。 fileciteturn0file3
- レター本文やプロンプト全文は既定で出さない（hash/length）。 fileciteturn0file3

---

### 7.2 LogEventスキーマ（JSONL）

#### 7.2.1 共通フィールド（推奨）
- `ts`：ISO8601
- `level`：DEBUG/INFO/WARN/ERROR
- `event`：固定識別子（例：`GenerateScript.Ok`）
- `message`：人間可読
- `correlationId`：相関ID
- `stationId` / `segmentType`
- `provider`：type/model/voiceId 等（秘密を含まない）
- `metrics`：`ms`、`bytes`、`retryCount` 等
- `error`：例外種別、短縮スタック（ERRORのみ）

---

### 7.3 IAppLogger（アプリ内ロガー）

#### 7.3.1 責務
- LogEventを受け取り、JSONLファイルへ追記する。
- 機密マスクを行う（少なくとも `apiKey` 文字列やトークン様文字列を出さない設計）。
- 呼び出し側の負荷を下げる（内部バッファリング/非同期書き込みは任意）。

#### 7.3.2 公開API（C#案：設計書準拠）
```csharp
public interface IAppLogger
{
    void Debug(LogEvent evt);
    void Info(LogEvent evt);
    void Warn(LogEvent evt);
    void Error(LogEvent evt, Exception ex);
}

public sealed record LogEvent(
    string Event,
    string Message,
    string? CorrelationId = null,
    string? StationId = null,
    string? SegmentType = null,
    object? Provider = null,
    object? Metrics = null,
    object? Extra = null
);
```

---

### 7.4 イベント命名規約（推奨）
- `Category.Action.Result`（例：`GenerateScript.Ok` / `GenerateScript.Fail`）
- 主要カテゴリ（例）：
  - Provider：`GenerateScript.*`, `Synthesize.*`, `ProviderTest.*`
  - Cache：`Cache.ScriptHit`, `Cache.AudioMiss`, `Cache.Evict.*`
  - DB：`Db.Letter.Insert.*`, `Db.Letter.Query.*`
  - Audio：`Audio.Play.*`, `Audio.Stop.*`
  - Playout：`Playout.SwitchStation.*`, `Buffer.Fill.*`

---

## 8. 代表I/Fの呼び出し関係（整合チェック）

- `SegmentPipeline`（生成）  
  1) `IScriptGenerator` → 内部で `ILLMProvider`  
  2) `ITtsSynthesizer` → 内部で `ITtsProvider`  
  3) `ICacheStore`（script/audio）参照・格納  
  4) `IAppLogger`（相関ID付き）  
  （全体像：コンポーネント設計書準拠） fileciteturn0file5

- `PlayoutEngine`（再生）  
  - `IAudioPlayer.PlayAsync` に `AudioSource` を渡し、終了まで待機しつつ、履歴（任意）とログを記録。 fileciteturn0file5

- `LetterUseCase`（レター）  
  - `ILetterRepository` を通じてCRUD。放送内取り上げは `letter_airings` に記録。 fileciteturn0file3

---

## 付録A：JSONLログ例

```json
{"ts":"2026-01-04T16:10:12+09:00","level":"INFO","event":"GenerateScript.Ok","message":"script generated","correlationId":"01J...","stationId":"CH_0765","segmentType":"TALK","provider":{"type":"openai_compatible","model":"local-model"},"metrics":{"ms":842}}
{"ts":"2026-01-04T16:10:13+09:00","level":"INFO","event":"Cache.ScriptHit","message":"script cache hit","correlationId":"01J...","stationId":"CH_0765","segmentType":"TALK","metrics":{"bytes":1320}}
{"ts":"2026-01-04T16:10:15+09:00","level":"WARN","event":"Synthesize.Fail","message":"tts transient error","correlationId":"01J...","stationId":"CH_0765","segmentType":"TALK","provider":{"type":"aivoice","preset":"default","voiceId":"speaker_1"},"error":{"type":"TransientException","detail":"timeout"}}
```

---

## 付録B：DBスキーマ参照

SQLiteのテーブル/DDLは「データ構造設計書（ハイブリッド）」の `v1` を正とする。 fileciteturn0file3

---

## 付録C：未決事項（I/Fとして余地を残す）

- LLM/TTSのストリーミング対応（`IAsyncEnumerable` など）をいつ導入するか（Phase 3想定）。 fileciteturn0file0
- キャッシュのメタ管理をDB（generated_assets）必須にするか、ファイルメタのみで完結させるか。 fileciteturn0file3
- ログをUIに取り込む方式（ファイル直読み vs DB取り込み）をどの時点で決めるか。 fileciteturn0file3
