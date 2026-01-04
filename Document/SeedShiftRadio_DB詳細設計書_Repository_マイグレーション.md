# SeedShiftRadio DB詳細設計書（Repository API＋マイグレーション運用）

- 版: 0.1（ドラフト）
- 作成日: 2026-01-04
- 対象: SeedShiftRadio（Windowsローカル / WPF、将来WinUI 3移行考慮）
- スコープ: SQLite（運用データ・履歴）に関する **詳細設計**（Repository API / SQL / トランザクション / マイグレーション運用）

## 参照
- SeedShiftRadio アーキテクチャ方針設計書（SQLite採用、層分離、止めない設計）
- SeedShiftRadio データ構造設計書（DB＋設定＋ログ：ハイブリッド）
- SeedShiftRadio I/F仕様書（DB：ConnectionFactory/MigrationRunner/Repository I/F）
- SeedShiftRadio コンポーネント／クラス設計書（責務・依存・公開API）
- SeedShiftRadio 設定スキーマ仕様書（paths.dataDir など）

---

## 1. 目的・設計原則

### 1.1 目的
- レター機能（投稿/管理/放送取り上げ）を **確実に永続化**し、UIから検索・運用可能にする。
- 先読み/再生体験を損なわないよう、DBアクセスは「必要最小・軽量」を原則とする（同期ブロック禁止、適切なインデックス）。
- バージョン更新に耐えるよう、**マイグレーション運用**を標準化する。

### 1.2 原則（アーキテクチャ整合）
- UIはDBに直接アクセスしない。Application→Repository（Interface）経由で利用する。
- DBは「運用データ（検索対象）」に限定し、技術ログはファイル（JSONL）に切り分ける。
- DB障害時は、可能な限りアプリ全体を停止させず、機能縮退（例：レター画面無効化）を許容する。

---

## 2. DB採用と保存先

### 2.1 DB種別
- SQLite（ファイルベース）を採用。

### 2.2 保存先（パス解決）
- 既定: `%LocalAppData%\SeedShiftRadio\data\app.db`
- `config.json` の `paths.dataDir` が指定される場合はそれを優先し、相対パスは **config.json のディレクトリ基準**で解決する。

### 2.3 文字コード・時刻
- SQLiteのTEXTはUTF-8で扱う（C#側は `string`）。
- 時刻は ISO8601（オフセット付き推奨 `YYYY-MM-DDTHH:mm:ss.fff+09:00`）を `TEXT` で統一する。

---

## 3. 接続・PRAGMA・同時実行制御

### 3.1 接続方針
- **呼び出しごとに新規接続**（Connection per operation）を原則とする。
- 1操作内で複数Repositoryを跨ぐ場合は、上位（UseCase）でトランザクション境界を定義する（後述）。

### 3.2 推奨PRAGMA（接続確立後に毎回適用）
- `PRAGMA foreign_keys = ON;`
- `PRAGMA journal_mode = WAL;`（書き込み耐性と並行読取改善）
- `PRAGMA synchronous = NORMAL;`（ローカル用途の性能/耐障害バランス）
- `PRAGMA busy_timeout = 5000;`（ロック競合の一時待ち）
- `PRAGMA temp_store = MEMORY;`（任意）
- `PRAGMA cache_size = -20000;`（任意：負値はKB指定。端末リソースに合わせて調整）

> 備考: `journal_mode=WAL` は DBファイルと `-wal/-shm` の3ファイル運用になるため、バックアップ時の手順（8章）で吸収する。

### 3.3 スレッドセーフ/並行性
- SQLiteは「同時書き込み」が制限される。  
  - 書き込みは短トランザクションで終える（UIスレッドで待たない）。
  - `busy_timeout` により短時間の競合は吸収し、超過時はTransientとして扱う。
- Repositoryは全て `async` で提供し、I/O待ちをUIへ伝播しない。

---

## 4. スキーマ（v1）

> v1の正は「データ構造設計書（ハイブリッド）」の DDL とする。本書は **Repository実装に必要な運用上の補足**を中心に定義する。

### 4.1 必須テーブル（MUST）
- `letters`
- `letter_threads`
- `letter_airings`

### 4.2 任意テーブル（SHOULD）
- `play_history`
- `generated_assets`

### 4.3 論理制約
- `letters.status` は `UNREAD/PENDING/ADOPTED/REPLIED` のみ（CHECK）。
- `letter_threads.author_type` は `USER/AI` のみ（CHECK）。
- `letter_threads.letter_id` / `letter_airings.letter_id` は `letters.id` に対するFK、`ON DELETE CASCADE`。

### 4.4 インデックス方針
- レター一覧（局×状態×日付）でのフィルタが最頻出のため、複合インデックスを必須とする。
- スレッドは `letter_id, created_at` で時系列取得できるようにする。
- 放送履歴は `letter_id, aired_at` / `station_id, aired_at` を用意し、局別の履歴表示に備える。

---

## 5. マイグレーション設計（schemaVersion運用）

### 5.1 目的
- DBファイルの「スキーマ状態」を一意に判定し、アプリ更新で安全に進化させる。
- 失敗時の影響を局所化し、復旧（バックアップ/再試行）を可能にする。

### 5.2 スキーマバージョンの表現
以下2つを併用する（推奨）:
1. **管理テーブル**: `schema_migrations`
2. **SQLite user_version**: `PRAGMA user_version`

- `schema_migrations` は「どのマイグレーションを適用したか」を追跡する（監査/デバッグ用）。
- `user_version` は「現在スキーマが何版か」を高速に取得する（起動判定用）。

#### 5.2.1 schema_migrations テーブル
```sql
CREATE TABLE IF NOT EXISTS schema_migrations (
  version INTEGER PRIMARY KEY,         -- 1,2,3...
  name TEXT NOT NULL,                  -- "0001_init"
  applied_at TEXT NOT NULL             -- ISO8601
);
```

### 5.3 マイグレーション資材の形式
- **SQLスクリプト**（推奨）をアセンブリ埋め込み（Embedded Resource）として配布する。
- 命名規約（例）:
  - `Migrations/0001_init.sql`
  - `Migrations/0002_add_archived_index.sql`
  - `Migrations/0003_add_fts_letters.sql`（将来）

### 5.4 適用アルゴリズム（起動時）
1. DBファイルの存在を確認（無ければ新規作成）。
2. 接続し、PRAGMA適用。
3. `schema_migrations` を作成（存在しない場合）。
4. `PRAGMA user_version` を読み取り、現在版 `current` を得る（0なら未初期化扱い）。
5. `current+1 .. latest` までのSQLを **順に** 適用する。
6. 各マイグレーションは「1本のトランザクション」で実行する。  
   - 成功時: `schema_migrations` にINSERTし、`PRAGMA user_version = <version>` を更新する。  
   - 失敗時: ロールバックし、DBをその時点の版のまま保持する。

### 5.5 失敗時の挙動（運用）
- 原則：DB初期化/マイグレーションに失敗した場合、**レター機能を縮退**させる。
  - UIは「レター機能が利用できない（DB初期化失敗）」を非ブロッキング通知で表示。
  - ラジオ再生（メイン機能）は継続可能とする。
- ただし、アプリ要件上「レターが必須」のフェーズでは、設定により「起動を止める（Fail-fast）」も選べるようにする（`config.json` に `db.failFastOnInitError` を将来追加可能）。

### 5.6 データ破壊を伴う変更
- 原則、MVPの範囲では破壊的変更を避ける。
- やむを得ず破壊的変更が必要な場合:
  - 事前にDBバックアップを作成し（8章）、  
  - 新DBへ移行（新ファイル作成→コピー）する方式を採用する（in-place ALTER を最小化）。

---

## 6. Repository設計（API・責務・トランザクション）

### 6.1 レイヤ責務
- Application（UseCase）は「業務操作（投稿、返信生成、採用、放送記録）」を定義する。
- Repositoryは「DB CRUD」と「クエリ最適化（インデックス前提）」に責務を限定する。
- DomainはDB構造に依存しない（Record/Entity変換はInfrastructureで行う）。

### 6.2 基盤I/F

#### 6.2.1 IDbConnectionFactory（必須）
```csharp
public interface IDbConnectionFactory
{
    DbConnection CreateConnection();
}
```

**責務**
- 接続文字列（Data Source=...）、PRAGMA適用方針、busy_timeout等を集約する。
- 呼び出し側は `using var con = factory.CreateConnection(); await con.OpenAsync(ct);` を徹底する。

#### 6.2.2 IMigrationRunner（必須）
```csharp
public interface IMigrationRunner
{
    Task InitializeAsync(CancellationToken ct);
    int LatestVersion { get; }
}
```

**責務**
- 5章のアルゴリズムに従い、起動時にDBを最新へ更新する。
- 例外は上位（アプリ起動シーケンス）へ返し、縮退/Fail-fastを判断させる。

#### 6.2.3 IUnitOfWork（推奨）
複数操作を同一トランザクションで束ねるための薄い抽象。

```csharp
public interface IUnitOfWork : IAsyncDisposable
{
    DbConnection Connection { get; }
    DbTransaction Transaction { get; }
    Task CommitAsync(CancellationToken ct);
}
```

- 実装は `BeginTransaction()` を包むのみで良い。
- Repositoryは `DbTransaction? tx = null` を受けられる設計にすると、UoWとの整合が取りやすい。

---

## 6.3 Repository API（MVP：必須）

### 6.3.1 ILetterRepository（MUST）
#### 目的
- レター本体・スレッド・放送履歴を扱う中核Repository。

#### 公開API（案）
```csharp
public interface ILetterRepository
{
    Task InsertAsync(LetterRecord letter, DbTransaction? tx, CancellationToken ct);

    Task<IReadOnlyList<LetterSummaryRecord>> QueryAsync(LetterQuery q, CancellationToken ct);
    Task<LetterDetailRecord?> GetDetailAsync(string letterId, CancellationToken ct);

    Task UpdateStatusAsync(string letterId, string status, DbTransaction? tx, CancellationToken ct);
    Task UpdateArchivedAsync(string letterId, bool archived, DbTransaction? tx, CancellationToken ct);

    Task AddThreadAsync(LetterThreadRecord thread, DbTransaction? tx, CancellationToken ct);
    Task AddAiringAsync(LetterAiringRecord airing, DbTransaction? tx, CancellationToken ct);

    Task DeleteAsync(string letterId, DbTransaction? tx, CancellationToken ct); // CASCADE
}
```

> `DbTransaction? tx` を受ける形にしておくと、返信生成や採用処理で「状態更新＋スレッド追加＋更新日時更新」を1トランザクションで保証できる。

#### Queryモデル
```csharp
public sealed record LetterQuery(
    string? StationId = null,
    string? Status = null,          // UNREAD/PENDING/ADOPTED/REPLIED
    bool? Archived = null,          // null: all
    string? TextQuery = null,       // subject/body 部分一致（MVP）
    int Limit = 50,
    int Offset = 0,
    string Sort = "UPDATED_DESC"    // UPDATED_DESC / CREATED_DESC
);
```

#### 代表SQL（実装方針）
- 一覧（Summary）:
  - `letters` のみを対象にし、スレッド/放送履歴は別取得（N+1回避のため、Detail表示時のみ取得）。
  - `TextQuery` は `LIKE` を利用（後述のエスケープ必須）。
- 詳細（Detail）:
  - `letters` 1件 + `letter_threads` 全件 + `letter_airings` 全件をまとめて返す。
  - 取得順:
    - threads: `ORDER BY created_at ASC`
    - airings: `ORDER BY aired_at DESC`

#### LIKE検索の安全性
- `TextQuery` は `%` と `_` のエスケープを実施し、パラメータ化してSQLインジェクションを防ぐ。
- ESCAPE句を使用する（例：`LIKE @q ESCAPE '\'`）。

#### 更新ポリシー
- `letters.updated_at` は、状態更新・スレッド追加・アーカイブ変更・放送記録追加で更新する（運用上の一覧ソートに寄与）。

---

## 6.4 Repository API（任意：履歴/キャッシュ索引）

### 6.4.1 IPlayHistoryRepository（SHOULD）
```csharp
public interface IPlayHistoryRepository
{
    Task InsertAsync(PlayHistoryRecord item, DbTransaction? tx, CancellationToken ct);
    Task<IReadOnlyList<PlayHistoryRecord>> QueryAsync(PlayHistoryQuery q, CancellationToken ct);
    Task PruneAsync(HistoryPrunePolicy policy, CancellationToken ct); // 保持上限
}
```

- 再生完了時に `Insert`（correlationId を保持）。
- 保持上限（例：90日 or 10,000件）で `Prune`。

### 6.4.2 IGeneratedAssetRepository（SHOULD）
```csharp
public interface IGeneratedAssetRepository
{
    Task UpsertAsync(GeneratedAssetRecord rec, DbTransaction? tx, CancellationToken ct);
    Task<GeneratedAssetRecord?> FindByKeyAsync(string inputHash, string providerFingerprint, CancellationToken ct);

    Task TouchAsync(string id, string accessedAtIso, DbTransaction? tx, CancellationToken ct); // LRU
    Task DeleteAsync(string id, DbTransaction? tx, CancellationToken ct);

    Task<IReadOnlyList<GeneratedAssetRecord>> ListEvictionCandidatesAsync(EvictionQuery q, CancellationToken ct);
}
```

- FileCacheと整合を取る場合、物理ファイル削除→DB行削除を同一トランザクションにするのではなく、  
  「最終的整合（失敗時に再試行）」を許容する（キャッシュは致命ではないため）。

---

## 6.5 トランザクション境界（代表ユースケース）

### 6.5.1 レター投稿（Submit）
- Tx不要（単一INSERT）でも良いが、将来拡張を見据えTxでも可。
- 手順（Tx任意）:
  - INSERT letters（status=UNREAD）

### 6.5.2 返信生成（GenerateReply）
- **Tx必須**（整合性を保証したい）
- 手順:
  1) 返信テキスト生成（LLM）はTx外（時間が読めないため）
  2) Tx開始
  3) INSERT letter_threads（author_type=AI）
  4) UPDATE letters（status=REPLIED または PENDING→REPLIED、updated_at更新）
  5) Tx commit

### 6.5.3 採用（ChangeStatus: ADOPTED）
- Tx不要（UPDATEのみ）でも良いが、運用ログを残すならTxで束ねる。
- 例: スレッドに「採用メモ」を追加する場合はTxでUPDATE+INSERT。

### 6.5.4 放送取り上げ記録（MarkAired）
- Tx推奨
- 手順:
  - INSERT letter_airings
  - UPDATE letters（status=ADOPTEDの維持 or REPLIEDへ遷移など、方針に応じて）
  - updated_at更新

### 6.5.5 レター削除（Delete）
- `ON DELETE CASCADE` により threads/airings を自動削除。
- Tx推奨（関連する外部ファイルが無い前提なら単純）。

---

## 7. 例外・リトライ・ログ

### 7.1 例外分類（推奨）
- **Transient**（再試行可能）
  - `SQLITE_BUSY` / `SQLITE_LOCKED`（ロック競合）
  - 一時的I/O（ネットワークではないが、AV等の排他で起こり得る）
- **Permanent**
  - スキーマ不整合（マイグレーション未適用）
  - 制約違反（CHECK/FK）
- **Canceled**
  - `OperationCanceledException`

### 7.2 リトライ方針
- Repository内部でのリトライは基本しない（上位で統一制御する）。
- 例外: `SQLITE_BUSY` のみ短い固定回数（例：2回、100ms/300ms）で吸収しても良い。

### 7.3 ログ（JSONL）
- 重要イベント（推奨）:
  - `Db.Init.Ok / Db.Init.Fail`
  - `Db.Migrate.Start / Db.Migrate.Ok / Db.Migrate.Fail`
  - `Db.Letter.Insert.*`
  - `Db.Letter.Query.*`（件数/時間）
  - `Db.Letter.UpdateStatus.*`
- 個人情報/レター本文の全文はログに出さない（hash/lengthのみ）。

---

## 8. 運用（バックアップ／修復／保守）

### 8.1 バックアップ
#### 8.1.1 推奨方式（WAL対応）
- SQLiteの **オンラインバックアップAPI** を使用する（推奨）。  
  - .NETでは `Microsoft.Data.Sqlite` が提供するバックアップ機能（または `VACUUM INTO` 相当）を利用できる場合、それを優先する。
- 代替（簡易）:
  1) `PRAGMA wal_checkpoint(FULL);`
  2) DBファイル + `-wal` + `-shm` を同一タイミングでコピー
  3) コピー先で `PRAGMA wal_checkpoint(FULL);` を実行して整合を確定

#### 8.1.2 バックアップ命名
- `app.db.bak-YYYYMMDD-HHMMSS`

### 8.2 修復/健全性チェック
- 起動時（任意）: `PRAGMA integrity_check;` を軽量に実行し、異常時はWARN→レター機能縮退。
- ユーザー操作（設定画面のメンテナンス）で「DB健全性チェック」を提供可能（将来）。

### 8.3 保守（肥大化対策）
- `play_history` / `generated_assets` を有効化した場合:
  - 保持上限（期間 or 件数）で定期的に `DELETE` を行う。
  - 大量削除後は任意で `VACUUM`（重いので手動実行推奨）。

---

## 9. 実装メモ（C#）

### 9.1 推奨スタック（MVP）
- `Microsoft.Data.Sqlite`（ADO.NET）
- SQL実行は
  - (A) ADO.NET直書き（最小依存）
  - (B) Dapper（軽量・可読性）  
  のいずれか。EF CoreはMVPでは過剰になりがちなので、採用するならRepositoryの背後に隠蔽する。

### 9.2 MigrationRunner（擬似コード）
```csharp
public async Task InitializeAsync(CancellationToken ct)
{
    await using var con = _factory.CreateConnection();
    await con.OpenAsync(ct);

    await ApplyPragmasAsync(con, ct);
    await EnsureSchemaMigrationsTableAsync(con, ct);

    var current = await GetUserVersionAsync(con, ct);   // PRAGMA user_version
    var latest = LatestVersion;

    for (var v = current + 1; v <= latest; v++)
    {
        var sql = _scripts[v]; // Embedded Resource load
        await using var tx = await con.BeginTransactionAsync(ct);

        try
        {
            await ExecuteSqlAsync(con, tx, sql, ct);
            await InsertMigrationRowAsync(con, tx, v, _names[v], _clock.NowIso(), ct);
            await SetUserVersionAsync(con, tx, v, ct);
            await tx.CommitAsync(ct);
        }
        catch
        {
            await tx.RollbackAsync(ct);
            throw;
        }
    }
}
```

### 9.3 LetterRepository（一覧クエリ例：SQLイメージ）
```sql
SELECT
  id, station_id, subject, radio_name, status, archived,
  created_at, updated_at
FROM letters
WHERE
  (@stationId IS NULL OR station_id = @stationId)
  AND (@status    IS NULL OR status = @status)
  AND (@archived  IS NULL OR archived = @archived)
  AND (
    @textQuery IS NULL
    OR subject LIKE @textQuery ESCAPE '\'
    OR body    LIKE @textQuery ESCAPE '\'
  )
ORDER BY
  CASE WHEN @sort = 'UPDATED_DESC' THEN updated_at END DESC,
  CASE WHEN @sort = 'CREATED_DESC' THEN created_at END DESC
LIMIT @limit OFFSET @offset;
```

---

## 10. 将来拡張（設計余地）

### 10.1 FTS5（全文検索）
- `TextQuery` の品質向上が必要になった段階で、SQLite FTS5 の導入を検討する。
- 導入時はマイグレーションで `letters_fts` を追加し、INSERT/UPDATE/DELETE をトリガで同期する方式が一般的。
- MVPでは `LIKE` のままで良い（件数が少ない想定）。

### 10.2 暗号化/機密
- レター本文の暗号化はMVP対象外（ローカル用途）。
- 必要になった場合は、アプリ層で暗号化（DPAPI等）してDBへ格納する（検索性とのトレードオフあり）。

---

## 付録A. マイグレーションSQL（例：0001_init.sql）
（データ構造設計書のDDLを正とするため、ここでは例示に留める）
```sql
PRAGMA foreign_keys = ON;

-- tables...
-- indexes...
```

