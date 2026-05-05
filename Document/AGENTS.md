# AGENTS.md（Codex実装ガイド / リポジトリ配置用）

- 対象: AIローカルラジオアプリ（Windowsローカル / WPF、将来 WinUI 3 移行考慮）
- 目的: Codex（エージェント）に「実装の入口」「参照すべき設計書」「実装順序」「完了条件」を1ファイルに集約し、迷いなく実装できる状態にする。
- 想定読者: 実装担当エージェント（Codex）／レビュー担当（人間）

---

## 0. このファイルの使い方（エージェント向け）

1. **まず「1. 参照ドキュメントの優先順位」**を読み、正本（Source of Truth）を把握する。
2. 次に **「2. 実装スコープ（MVP）」** を満たす最小構成を作り、動作する縦切り（起動→局選択→再生開始→停止）を優先する。
3. 実装は **「8. 実装ワークパッケージ」** の順で進める（各パッケージは小さくコミット可能に分割）。
4. 迷ったら **「7. 実装上の規約（落とし穴回避）」** に従う。

---

## 1. 参照ドキュメントの優先順位（Source of Truth）

本リポジトリには以下の設計書がある前提。**矛盾がある場合は上ほど優先**し、下位は「補足」として扱う。

1. **要件定義書**: `AIローカルラジオアプリ 要件定義書.md`（v0.3）
2. **アーキテクチャ方針**: `AIローカルラジオアプリ_アーキテクチャ方針設計書.md`（v0.1）
3. **画面レイアウト（A案）**: `AIローカルラジオアプリ_画面レイアウト設計書_A案.md`（v0.1）
4. **画面詳細（VM/Binding）**: `AIローカルラジオアプリ_画面詳細設計書_VMバインディング.md`（v0.1）
5. **概要設計（プログラム）**: `AIローカルラジオアプリ_概要設計書_ドラフト.md`（v0.1）
6. **コンポーネント/クラス設計（責務/公開API）**: `AIローカルラジオアプリ_コンポーネントクラス設計書_責務依存公開API.md`（v0.1）
7. **I/F仕様（Providers/Audio/Cache/DB/ログ）**: `AIローカルラジオアプリ_IF仕様書_Providers_Audio_Cache_DB_ログ.md`（v0.1）
8. **設定スキーマ仕様（config.json）**: `AIローカルラジオアプリ_設定スキーマ仕様書_configjson.md`（v0.1）
9. **DB詳細（Repository API + マイグレーション運用）**: `AIローカルラジオアプリ_DB詳細設計書_Repository_マイグレーション.md`（v0.1）
10. **データ構造設計（ハイブリッド案）**: `AIローカルラジオアプリ_データ構造設計書_ハイブリッド案.md`（v0.1）

---

## 2. 実装スコープ（MVP）

### 2.1 MVPで満たすこと（必須）

- **離散チャンネル**方式の周波数（ダイヤル）操作と局切替
- **局ごとのパーソナリティ**（チャンネル単位で seed 保存）によるトーク生成
- **短尺セグメント方式 + 先読みバッファ**による「途切れにくい」連続再生
- **LLM/TTS差し替え**可能な設計・設定（MVPは OpenAI互換HTTP + A.I.VOICE）
- **キャッシュはオプション制御**（台本/音声、TTL/容量/削除ポリシー）
- **ログ/エラー時フォールバック**（止めない設計、無音回避）
- **ラジオレター機能**（投稿・管理・放送内反映・返信）

### 2.2 MVPで“やらない/後回し”の典型

- 高度な演出（ダッキング/クロスフェード/SE多用）
- 高度なモデレーションの完全実装（最低限の安全設計は実施）
- Foundry Local など将来 Provider の実装（差し替え余地のみ確保）

---

## 3. 目標リポジトリ構成（案）

> 実装言語/フレームワークは .NET / C# を前提（UIはWPF、将来 WinUI 3）。

```
/docs/                      # 設計書（上記ファイル群）
/src/
  App.Presentation.Wpf/     # View(XAML) + ViewModel（MVVM）
  App.Application/          # UseCase / Coordinator / DTO
  App.Domain/               # Entities / ValueObjects / Domain Services
  App.Infrastructure/       # Providers / Audio / Cache / Persistence / Logging / Config
/tests/
  App.Application.Tests/
  App.Domain.Tests/
  App.Infrastructure.Tests/
```

依存方向（原則）:
`Presentation → Application → Domain`。Infrastructure は Application に DI 注入で接続する（UIから直接参照しない）。

---

## 4. まず実装する「骨格」

### 4.1 起動フロー（最小）

1. config.json をロード（マイグレーション → スキーマ検証 → セマンティック検証 → 正規化）
2. UserSettings（user.config 相当）ロード（UI状態のみ）
3. 起動局決定（`startupStationId` が無ければ先頭局）
4. UI表示（Shell + Main）
5. Play を押したら Playout を開始（先読みしつつ連続再生）

### 4.2 最小ユースケース（Facade）

- `RadioUseCase`: Tune/Scan/PlayStop と現在状態取得（UI表示用）
- `SettingsUseCase`: config の Load/Save + Provider疎通テスト + Cache操作
- `LetterUseCase`: 投稿/一覧/詳細/状態更新/返信生成/放送記録

> 具体APIは「コンポーネント/クラス設計書」を正とする。

---

## 5. “差し替え可能”にするための実装ポイント

### 5.1 Provider（LLM/TTS）

- **ProviderFactory**（設定から実装選択）を用意し、UI/Application からはインターフェースのみを見る。
- A.I.VOICE 連携は「TTSは音声ファイルを生成するだけ」にし、**再生責務はAudioPlayerへ**閉じ込める。

### 5.2 止めない設計（可用性）

- LLM/TTSが失敗してもアプリ全体は停止しない。
- 無音回避を優先し、フォールバック（キャッシュ再利用、短い案内、BGM延長、別コーナー差し替え）を選べる構造にする。

---

## 6. 永続化の実装ガイド（config / DB / logs）

### 6.1 config.json（主設定）

- `configVersion` を持つ。
- ロード時に「マイグレーション→バリデーション→正規化」を必ず通す。
- APIキーは直書きせず `apiKeyRef` で解決する（ログ/画面露出禁止）。
- JSON Schema（`config.schema.json`）を同梱する。

実装コンポーネント（推奨）:
- `ConfigLoader`
- `IConfigMigration`（vX→vX+1 のチェーン）
- `ISchemaValidator`（JSON Schema）
- `ISemanticValidator`（重複/参照整合/形式チェック）
- `ConfigNormalizer`（パス/既定値）
- `ISecretResolver`（apiKeyRef→実値）

### 6.2 DB（レター等）

- SQLite を第一候補。
- Repository は `async` 提供（UIへI/O待ちを伝播しない）。
- マイグレーションは **`schema_migrations` + `PRAGMA user_version` の併用**で運用する。
- DB初期化/マイグレーション失敗時は **レター機能のみ縮退**し、ラジオ再生は継続する。

推奨ディレクトリ:
- `paths.dataDir` 配下（例 `./data/app.db`）

### 6.3 ログ（JSONL）

- JSON Lines（1行1イベント）を採用し、日次＋サイズでローテーション。
- `IAppLogger` をアプリ内共通I/Fとして用意し、イベント命名規約 `Category.Action.Result` を推奨。

---

## 7. 実装上の規約（落とし穴回避）

### 7.1 テスト方針（Codex向けの制約：単体テストのみ実行）

- エージェントは **「処理（ロジック）を実装している部分」だけ** を対象に **単体テスト（Unit Test）** を作成・実行する。
  - 対象例: Domain（Entity/ValueObject/DomainService）、Application（UseCaseの分岐・状態遷移・選定ロジック）、Configの検証/正規化、Queue/Buffer/Coordinatorの制御ロジック。
  - 非対象例: UI（XAML/Binding/Dispatcher挙動）、実デバイス/実プロセス（A.I.VOICE 実呼び出し）、実ネットワーク（LLM HTTP 実通信）、実ファイルI/Oの統合動作、SQLite実DBの統合テスト。
- Infrastructure（Providers/DB/Audio/Cache/Logging）は「薄いアダプタ」に留め、**テストは原則モック/フェイクで置き換え**て単体で検証する。
- 単体テストは **決定性（deterministic）** を満たすこと（時刻・乱数・外部状態は注入して固定化）。
- 実行するテストは **Unit Test プロジェクトのみ** とし、統合テストを追加する場合は明確に分離する（例: `*.IntegrationTests` を別プロジェクトにし、通常実行から除外）。

推奨実行コマンド例（Unitのみ）:
- `dotnet test ./tests/App.Domain.Tests/App.Domain.Tests.csproj`
- `dotnet test ./tests/App.Application.Tests/App.Application.Tests.csproj`

> 目的: エージェントがI/O環境差や外部依存で停止することを防ぎ、ロジックの品質を最小コストで担保する。


- **UseCase呼び出しは全て `CancellationToken` を受ける**（画面離脱／局切替／アプリ終了でキャンセル可能にする）。
- ViewModel の `ObservableCollection` 更新は **UIスレッド限定**（Dispatcherでmarshal）。
- 入力フォーム（レター投稿、設定編集）は **`INotifyDataErrorInfo` でフィールド単位エラー**。
- 例外（通信失敗、DBロック等）は **非ブロッキング通知（トースト等）** が基本。
- Tune（周波数変更）は「スライダー値の変化」ではなく **確定タイミング（Commit/デバウンス）で実行**。

---

## 8. 実装ワークパッケージ（Codex向け ToDo 分割）

> 原則: 各パッケージは「コンパイルが通る」「最低限のテストが通る」単位で小さく完了させる。

### WP0: リポジトリ骨格（最優先）
- ソリューション/プロジェクト分割（Presentation/Application/Domain/Infrastructure + tests）
- DI（Composition Root）を用意し、UseCase が解決できることを確認
- 最小起動（Window表示）まで

**完了条件**
- アプリが起動し、Shell と Main が表示される（ダミー表示で可）

### WP1: config.json ローダ（MVPの最初の縦切り）
- configVersion 読み取り、MigrationRunner（空で可）
- JSON Schema 検証（最小スキーマでも可）
- セマンティック検証（重複チェック等は最低限）
- 正規化（相対→絶対、既定値）
- SettingsUseCase から Load/Save できる

**完了条件**
- 設定画面で Load/Save が動き、ValidationIssues が表示できる

### WP2: Logger（JSONL）
- `IAppLogger` + `JsonlFileLogger`（ローテーションは最小で可）
- correlationId を流せる

**完了条件**
- 主要操作（起動/設定ロード/再生開始/停止）でログが出る

### WP3: Provider（ダミー → 実装へ）
- `ILLMProvider`（まずはダミー：固定テキスト生成）
- `ITtsProvider`（まずはダミー：無音WAV生成 or 既存WAVコピー）
- ProviderFactory（providerType で切替）
- SettingsUseCase で疎通テスト

**完了条件**
- 1セグメント分の音声ファイルが生成できる（ダミーで可）

### WP4: Audio 再生（最小）
- `IAudioPlayer.PlayAsync(AudioSource)` を用意
- WAVファイル再生（最小）
- Stop/Cancel を実装

**完了条件**
- 生成した音声ファイルを再生でき、停止できる

### WP5: Playout（短尺セグメント + 先読み）
- PlayoutQueue / PlayoutEngine（逐次消費）
- BufferManager（Nセグメント先読み）
- PlayoutCoordinator（局切替時のキャンセル/停止/初期化/再開）

**完了条件**
- Play で連続再生が始まり、Tune/Scan で局切替してもアプリが止まらない

### WP6: レター（DB + 画面）
- SQLite 初期化 + マイグレーション（user_version + schema_migrations）
- `ILetterRepository` 実装（CRUD）
- LetterUseCase 実装
- レター画面（一覧/詳細/投稿/状態更新）
- DB障害時は `DbUnavailable` へ縮退

**完了条件**
- レター投稿→一覧反映→状態更新ができる（返信生成はダミーでも可）

### WP7: 返信生成・放送内反映（レターコーナー）
- `IReplyGenerator`（LLM利用）
- 放送内取り上げ履歴（airings）
- Playout の SegmentType に「レター」導入

**完了条件**
- 採用レターを優先選定し、放送内で読み上げ/コメントできる（最小）

---

## 9. 受け入れチェック（MVP）

- [ ] config.json を読み込み、起動局が決まり、メイン画面に表示される
- [ ] Play → 連続再生（短尺セグメント）できる
- [ ] Tune/Scan で局切替しても無音・フリーズにならない（キャンセル/先読みが機能）
- [ ] 設定画面で Provider 疎通テストができる（最低限）
- [ ] キャッシュON/OFFができ、ログに記録される
- [ ] レター投稿/一覧/状態更新ができる
- [ ] DB障害時にレター機能のみ縮退し、ラジオ再生は継続する
- [ ] ログが JSONL で出力され、主要イベントに correlationId が付与できる

---

## 10. 未決事項（実装で詰まったらここを更新）

- Audio 再生ライブラリ（NAudio等）の採否とライセンス
- A.I.VOICE 連携方式（CLI/COM/HTTP等の具体）
- 音源スキャン仕様（拡張子/メタデータ/重複回避の基準）
- キャッシュ削除方式（LRU/TTL/サイズ優先など）
- レター読み上げ方針（全文/要約/抜粋）の既定値
