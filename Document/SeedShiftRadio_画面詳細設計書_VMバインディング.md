# SeedShiftRadio 画面詳細設計書（状態設計／ViewModelプロパティ・Command／バインディング規約）

- 版：0.1（ドラフト）
- 作成日：2026-01-04
- 対象：SeedShiftRadio（Windowsローカル / WPF、将来WinUI 3移行考慮）
- 対象画面：メイン（ラジオ/A案）、レター、設定
- 参照：要件定義書 v0.3／アーキテクチャ方針設計書 v0.1／画面レイアウト設計書（A案） v0.1／データ構造設計書（ハイブリッド） v0.1／プログラム概要設計書 v0.1／コンポーネント／クラス設計書 v0.1／I/F仕様書 v0.1／設定スキーマ仕様書 v0.1／DB詳細設計書 v0.1

---

## 1. 目的・スコープ

本書は、既存の画面レイアウト（A案）とアーキテクチャ方針（MVVM + 層分離）を前提に、**画面ごとの状態（State）**、**ViewModelのプロパティ／Command**、および **WPFバインディング規約** を実装可能な粒度で確定する。

- UI（Presentation）は **UseCase呼び出しと状態表示** に責務を限定し、生成／再生／DB／I/Oは持たない。
- 例外や外部障害は「止めない設計」に従い、UIは **非ブロッキング通知** を基本とする（詳細はログへ）。

---

## 2. UIアーキテクチャ前提（MVVM・依存ルール）

### 2.1 層と依存
- View（XAML） ↔ ViewModel（状態・Command）
- ViewModel → Application（UseCase）へ依存（DIで注入）
- ViewModelは Infrastructure（DB/Provider/Audio/Cache 等）へ直接依存しない（UseCaseの背後に隠蔽）

### 2.2 画面構成（A案）
- Shell（共通フレーム）＋ ContentHost（選択画面）＋ 常時プレイヤーという枠組みを採用する。
- メイン画面（ラジオ）は「左：局一覧／中央：NowPlaying + 周波数ダイヤル／右：字幕・キュー・レター（タブ）」の3分割を基準とする。

---

## 3. バインディング規約（共通）

> 以降のプロパティ／Command命名は **WPF/WinUI差し替えを意識して UIに依存しない** 形に統一する。

### 3.1 ViewModel共通基盤（推奨）
- `ViewModelBase`（共通）
  - `bool IsBusy`
  - `string? BusyMessage`
  - `ObservableCollection<UiNotificationVm> Notifications`
  - `void NotifyPropertyChanged(string name)`
- `AsyncCommand`（再入禁止・例外統一）
  - `IAsyncRelayCommand` 相当（CommunityToolkit.Mvvm など）を前提にしてよい
  - 例外は `INotificationService` へ委譲し、UIをブロックしない

#### 例：Commandの規約
- Publicプロパティ名：`*Command` で終える（例：`SaveCommand`）
- 原則 `Async`（I/Oを伴う）は **必ず非同期** とし、UIスレッド待ちを発生させない。
- `CanExecute` は `IsBusy` と「選択中アイテムの有無」等で制御する（無効状態をUIで表現）。

### 3.2 プロパティ命名・型の規約
- 真偽：`IsPlaying` / `HasUnsavedChanges` のように `Is/Has/Can` プレフィクスを使用
- 文字列表示専用：`FooDisplay`（例：`FrequencyDisplay`）
- コレクション：`ObservableCollection<T>` を内部保持し、公開は `ReadOnlyObservableCollection<T>` を推奨
- 選択：`Selected*` を使用（例：`SelectedLetter`）
- 列挙：`Enum` を使用（UIに表示する文言は Converter/DisplayName 属性で対応）

### 3.3 バインディング（WPF）の基本設定
- `TextBox.Text`：`Mode=TwoWay` + `UpdateSourceTrigger=PropertyChanged`（検索や簡易入力）
- 設定値や数値入力は、誤入力を防ぐため `UpdateSourceTrigger=LostFocus` を基本とする（例：BaseUrl、Model）。
- `Slider.Value`：`Mode=TwoWay`（ただし周波数ダイヤルは「連続変更でTune連打」を避けるため、**Commit方式**を採用：後述）
- `ItemsControl/TabControl`：`ItemsSource` は `ReadOnlyObservableCollection`、`SelectedItem` は `TwoWay`。
- `IsEnabled`：`!IsBusy` だけでなく `CanXxx` を併用して、操作可能条件を明示する。

### 3.4 バリデーション・エラー表示規約
- 入力フォーム（レター投稿、設定編集）は `INotifyDataErrorInfo` を基本とし、エラーを **フィールド単位** で表示する。
- 例外（通信失敗、DBロック等）は `Notifications`（トースト/スナックバー）へ。
- 重大（起動に関わる設定破損等）のみ、モーダル（ダイアログ）を許容（ただし最小限）。

### 3.5 スレッド／Dispatcher規約
- UseCaseからのイベント／ステータス更新はバックグラウンドから来る可能性があるため、ViewModelは UIスレッドへ marshal してコレクション更新する。
- 原則：`ObservableCollection` 更新は UIスレッド限定。

### 3.6 ナビゲーション・ダイアログ規約
- 画面遷移：`INavigationService`（抽象）を経由する。
- ファイル選択、確認ダイアログ：`IUiDialogService`（抽象）を経由する。
- 通知：`INotificationService`（抽象）を経由する。

### 3.7 UserSettings（user.config）への永続化規約（UI状態）
UserSettingsに保持するのは **端末依存のUI状態のみ** とし、共有可能な主設定（Station/Provider/Cache 等）は config.json に保持する。

推奨キー（例）：
- `Ui.Window.Left/Top/Width/Height/State`
- `Ui.LastStationId`
- `Ui.Volume` / `Ui.IsMuted`
- `Ui.Main.RightPane.IsOpen`
- `Ui.Main.RightPane.SelectedTab`
- `Ui.Main.Caption.IsEnabled`
- `Ui.Letter.Filter.StationId/Status/Query`（任意）
- `Ui.Settings.SelectedCategoryKey`

保存タイミング：
- アプリ終了時（必須）
- 主要トグル変更時（任意：小さなI/Oで済むなら逐次保存）

---

## 4. 共通ViewModel（Shell）

### 4.1 ShellViewModel（トップナビ＋ステータス）
**責務**
- トップレベルナビ（ラジオ／レター／設定）とコンテンツ切替
- 右側ステータス領域（Provider状態、簡易エラー数、保存状態）

**状態（State）**
- `ShellState = Initializing | Ready | Degraded`
  - Degraded：一部機能縮退（例：DB初期化失敗によりレター無効）

**主要プロパティ**
- `NavigationItemVm[] NavItems`
- `NavigationItemVm SelectedNavItem`
- `object CurrentContentViewModel`（または `IPageVm`）
- `ProviderStatusVm ProviderStatus`（LLM/TTS）
- `int UnreadNotificationCount`
- `bool IsSavePending`（設定の未保存など）

**主要Command**
- `NavigateCommand(NavTarget target)`
- `OpenLogFolderCommand`（任意：デバッグ用）
- `ClearNotificationsCommand`

---

## 5. 画面詳細設計：メイン画面（ラジオ）

### 5.1 画面状態（State machine）
`MainRadioState`
- `Initializing`：起動直後（config/user.settings 読込、起動局決定）
- `Ready`：再生停止中（局は選択されている）
- `SwitchingStation`：局切替中（Tune/Scan）
- `Buffering`：先読みが閾値未満（再生は継続するが「準備中」を表示）
- `Playing`：再生中
- `Paused`：一時停止（MVPでPauseを実装する場合）
- `Stopping`：停止処理中
- `Degraded`：Provider不調などで品質低下（フォールバック動作中）
- `Error`：直近操作で失敗（ただしアプリは継続）

**遷移の代表**
- Initializing → Ready（起動局確定）
- Ready ↔ Playing（Play/Pause）
- * → SwitchingStation → Buffering/Playing/Ready（Tune/Scan）
- * → Error（操作失敗）→（復帰）Ready/Playing

### 5.2 MainRadioViewModel（確定）

#### 5.2.1 表示・入力プロパティ
- `MainRadioState State`
- `string? StateMessage`（例：`"局切替中…"`, `"先読み中 1/2"`）
- `double DialFrequencyMhz`（UI入力：ダイヤル/スライダーの現在値）
- `double CurrentFrequencyMhz`（確定値：実際にTune済みの周波数）
- `string FrequencyDisplay`（例：`"76.5 MHz"`）

- `ReadOnlyObservableCollection<StationListItemVm> Stations`
- `StationListItemVm? SelectedStation`（クリック選択でTune）

- `StationVm? CurrentStation`
  - `string Id`
  - `string Name`
  - `string? Genre`
  - `string? Description`
  - `string? PersonalityDisplayName`

- `NowPlayingVm? NowPlaying`
  - `string SegmentType`（TALK/MUSIC/LETTER/JINGLE）
  - `string Title`（任意：曲名やコーナー名）
  - `TimeSpan? Remaining`

- `bool IsPlaying`
- `bool IsPaused`（実装しない場合は省略）
- `double Volume`（0.0〜1.0）
- `bool IsMuted`

- `ProviderStatusVm ProviderStatus`
  - `ProviderHealthVm Llm`
  - `ProviderHealthVm Tts`

- `BufferStatusVm BufferStatus`
  - `int TargetSegments`
  - `int ReadySegments`
  - `bool IsLow`（閾値未満）

- 右ペイン（タブ）
  - `bool IsRightPaneOpen`
  - `MainRightPaneTab SelectedRightPaneTab`（Caption/Queue/Letter）
  - `bool IsCaptionEnabled`
  - `string CaptionText`
  - `ReadOnlyObservableCollection<QueueItemVm> QueueItems`
  - `LetterOnAirVm? LetterOnAir`（レターコーナー時のみ）

- 画面幅モード（任意：右ペイン自動折りたたみ）
  - `bool IsNarrowMode`
  - `bool IsRightPaneAutoCollapsed`

#### 5.2.2 Command（確定）
- `InitializeCommand`（起動後1回：Load settings → Tune startup station）
- `CommitDialFrequencyCommand`（DialFrequencyMhz → Tune）
- `TuneToFrequencyCommand(double mhz)`（Scan/クリックから直接呼ぶ用途）
- `ScanNextCommand`
- `ScanPrevCommand`

- `TogglePlayPauseCommand`
- `StopCommand`

- `SetVolumeCommand(double volume)`（Slider操作）
- `ToggleMuteCommand`
- `ToggleCaptionEnabledCommand`
- `ToggleRightPaneCommand`
- `SelectRightPaneTabCommand(MainRightPaneTab tab)`

- `OpenLetterCommand`（ナビゲーション）

> 周波数ダイヤルは `DialFrequencyMhz` を TwoWay で更新しつつ、Tune実行は `CommitDialFrequencyCommand`（デバウンスまたはドラッグ完了）で行う。これにより Tune連打を抑制する。

#### 5.2.3 UseCase連携（責務境界）
- `IRadioUseCase` を唯一の入口として利用する（Tune/Scan/PlayPause/Stop/GetStatus/イベント購読）。
- `SubscribeEventsAsync` がある場合は、ViewModel側で購読し、`NowPlaying`・`CaptionText`・`QueueItems` を増分更新する。
- `GetStatusAsync` は「初期表示」「復帰」「イベント未実装の暫定」に利用する。

#### 5.2.4 バインディング対応表（主要コントロール）
| UI要素 | Binding | 備考 |
|---|---|---|
| 局一覧（ListBox） | `ItemsSource=Stations` / `SelectedItem=SelectedStation` | Selected変更で `TuneToFrequencyCommand` を呼ぶ（イベント→Command） |
| 周波数スライダー | `Value=DialFrequencyMhz` | 変更確定時に `CommitDialFrequencyCommand` |
| スキャン前/次 | `Command=ScanPrevCommand/ScanNextCommand` | マウスホイールはView側で捕捉→Commandへ |
| 再生/停止 | `Command=TogglePlayPauseCommand/StopCommand` | `IsEnabled` は `CanExecute` |
| 音量 | `Value=Volume` + `Command=SetVolumeCommand` | 連続更新OK（Audio側は軽量想定） |
| 右ペインTab | `SelectedItem=SelectedRightPaneTab` | `IsRightPaneOpen` が false の場合は表示なし |
| 字幕テキスト | `Text=CaptionText` | Copy可（任意） |

#### 5.2.5 永続化（UserSettings）
- 起動時復元：`Ui.LastStationId` / `Ui.Volume` / `Ui.IsMuted` / `Ui.Main.RightPane.*` / `Ui.Main.Caption.IsEnabled`
- 変更時保存（任意）：
  - `Volume`（一定間隔 or 変更確定時）
  - `IsRightPaneOpen` / `SelectedRightPaneTab` / `IsCaptionEnabled`

---

## 6. 画面詳細設計：レター画面

### 6.1 画面状態（State machine）
`LetterState`
- `Initializing`：DB初期化結果待ち、初回一覧ロード
- `Ready`：一覧/詳細表示可能
- `LoadingList`：一覧再取得中
- `LoadingDetail`：詳細取得中
- `Submitting`：投稿処理中
- `ChangingStatus`：状態更新中
- `GeneratingReply`：返信生成中
- `MarkingAired`：放送記録中
- `DbUnavailable`：DB初期化/接続失敗（レター機能縮退）
- `Error`：直近操作失敗（ただし継続）

### 6.2 LetterViewModel（確定）

#### 6.2.1 表示・入力プロパティ
- `LetterState State`
- `bool IsBusy` / `string? BusyMessage`（Stateから派生可）

- `LetterFilterVm Filter`
  - `string? StationId`（null=全局）
  - `LetterStatus? Status`（null=全状態）
  - `string? Query`（部分一致）
  - `bool? Archived`（任意）

- `ReadOnlyObservableCollection<LetterSummaryVm> Letters`
- `LetterSummaryVm? SelectedLetter`

- `LetterDetailVm? Detail`
  - `string Id`
  - `string StationId`
  - `string Subject`
  - `string Body`
  - `string? RadioName`
  - `DateTimeOffset CreatedAt`
  - `DateTimeOffset UpdatedAt`
  - `LetterStatus Status`
  - `ReadOnlyObservableCollection<LetterThreadItemVm> Thread`
  - `ReadOnlyObservableCollection<LetterAiringVm> Airings`（任意）

- 操作可否（Can系：UIの明確化のため推奨）
  - `bool CanChangeStatus`
  - `bool CanGenerateReply`
  - `bool CanRegenerateReply`
  - `bool CanMarkAsOnAir`

#### 6.2.2 Command（確定）
- `InitializeCommand`
- `RefreshCommand`（一覧再取得）
- `ApplyFilterCommand`（Filter変更確定：デバウンス可）
- `SelectLetterCommand(string letterId)`（Selected変更でDetail取得）

- `OpenCreateLetterCommand`（投稿ダイアログ起動：IUiDialogService）
- `SubmitLetterCommand`（投稿：ダイアログ内VMから呼ぶでも可）

- `ChangeStatusCommand(LetterStatus status)`（保留/採用/返信済等）
- `GenerateReplyCommand`
- `RegenerateReplyCommand`
- `MarkAsOnAirCommand`

- `ToggleArchivedCommand`（任意）
- `DeleteLetterCommand`（任意：MVP外でも良い）

#### 6.2.3 UseCase連携（責務境界）
- `ILetterUseCase` に対して
  - `QueryAsync(Filter)`（一覧）
  - `GetDetailAsync(id)`（詳細）
  - `SubmitAsync(cmd)`（投稿）
  - `ChangeStatusAsync(id,status)`
  - `GenerateReplyAsync/RegenerateReplyAsync`
  - `MarkAiredAsync(id, stationId, segmentId?)`
を委譲する。

### 6.3 LetterComposeViewModel（投稿ダイアログ用・推奨）
**責務**
- 投稿フォームの入力と検証のみ（送信は UseCase 委譲）
- 入力ミスをフィールド単位で提示

**主要プロパティ**
- `string? StationId`（既定：現在局 or 最後の局）
- `string Subject`
- `string Body`
- `string? RadioName`
- `bool IsAnonymous`（任意：RadioName未設定と同義でも可）
- `bool IsValid`（派生）
- `ReadOnlyObservableCollection<FieldErrorVm> Errors`（任意：INotifyDataErrorInfoで代替可）

**主要Command**
- `SubmitCommand`
- `CancelCommand`

### 6.4 バインディング対応表（主要コントロール）
| UI要素 | Binding | 備考 |
|---|---|---|
| フィルタ（局/状態） | `SelectedValue=Filter.StationId/Filter.Status` | 変更時 `ApplyFilterCommand`（デバウンス推奨） |
| 検索 | `Text=Filter.Query` | Enterで確定、または遅延確定 |
| 一覧 | `ItemsSource=Letters` / `SelectedItem=SelectedLetter` | 選択で詳細ロード |
| 詳細本文 | `Text=Detail.Body`（ReadOnly） | 長文はScrollViewer |
| スレッド | `ItemsSource=Detail.Thread` | 返信生成後に追加 |
| フッターボタン | `Command=ChangeStatus/GenerateReply/...` | `Can*` と `IsBusy` を反映 |

### 6.5 DB障害時の縮退（必須）
- DB初期化/マイグレーション失敗時、`State=DbUnavailable` とし、
  - 一覧/投稿/操作を無効化し、説明文を表示する
  - 通知（トースト）で「レター機能が利用できない」旨を提示
- ラジオ再生は継続可能とする（メイン機能優先）。

---

## 7. 画面詳細設計：設定画面

### 7.1 画面状態（State machine）
`SettingsState`
- `Initializing`：Config読込（初回）
- `Ready`：編集可能
- `Validating`：入力検証中
- `Saving`：保存中
- `Exporting` / `Importing`
- `TestingProvider`：LLM/TTS接続テスト
- `LoadingStats`：キャッシュ統計取得
- `ClearingCache`：キャッシュ削除
- `Error`：直近操作失敗（継続）

### 7.2 SettingsViewModel（確定）

#### 7.2.1 ルートプロパティ
- `SettingsState State`
- `string ConfigPath`
- `bool HasUnsavedChanges`
- `ReadOnlyObservableCollection<SettingsCategoryVm> Categories`
- `SettingsCategoryVm SelectedCategory`

- `AppConfigVm Config`（編集対象：DTOをVM化）
  - `int ConfigVersion`（ReadOnly：保存時に自動更新する方針でも可）
  - `AppGeneralVm App`
  - `PathsVm Paths`
  - `PlayoutVm Playout`
  - `CacheVm Cache`
  - `ProvidersVm Providers`
  - `TemplatesVm Templates`（任意：MVPでは表示のみでも可）
  - `StationsVm Stations`（MVPでは簡易編集 or 読み取り）

- `ReadOnlyObservableCollection<ValidationIssueVm> ValidationIssues`
  - `Severity (ERROR/WARN)`, `Path`, `Message`, `Code`

- `CacheStatsVm? CacheStats`
- `ProviderTestResultVm? LastLlmTest`
- `ProviderTestResultVm? LastTtsTest`

#### 7.2.2 Command（確定）
- `InitializeCommand`（既定パスのconfigロード）
- `BrowseConfigPathCommand`（ファイル選択：IUiDialogService）
- `LoadConfigCommand`（ConfigPathでロード）
- `SaveCommand`（Validate → Save）
- `ExportConfigCommand`
- `ImportConfigCommand`

- `TestLlmCommand`
- `TestTtsCommand`

- `RefreshCacheStatsCommand`
- `ClearCacheCommand`

- `ScanMusicFoldersCommand`（任意：音源カテゴリに紐づけ）

#### 7.2.3 保存・検証の挙動（規約）
- 保存は必ず
  1) JSON Schema検証
  2) セマンティック検証（重複・参照整合・apiKeyRef形式等）
  3) 正規化（相対パス→絶対、既定値補完）
  を通してから実行する。
- ERRORがある場合は保存を拒否し、ValidationIssuesを表示する。
- WARNのみの場合は保存を許可（ユーザーが理解できる文言を併記）。

### 7.3 SettingsCategoryVm（カテゴリVMの標準形）
- `string Key`（永続化用：例 `general`, `playout`, `llm`, `tts`, `music`, `cache`, `import_export`）
- `string Title`
- `object ContentViewModel`（カテゴリ別VM）
- `bool HasWarning` / `bool HasError`（ValidationIssuesから集計）

> 実装を簡略化する場合、カテゴリ別VMは持たず、`Config` の該当サブVMへ直接バインドしてもよい。

### 7.4 バインディング対応表（主要コントロール）
| UI要素 | Binding | 備考 |
|---|---|---|
| ConfigPath | `Text=ConfigPath` | Browseで更新 |
| カテゴリ一覧 | `ItemsSource=Categories` / `SelectedItem=SelectedCategory` | SelectedCategoryKeyをuser.settingsへ |
| 編集フォーム | `DataContext=SelectedCategory.ContentViewModel` | カテゴリごとにテンプレート切替 |
| 保存ボタン | `Command=SaveCommand` / `IsEnabled=HasUnsavedChanges && !IsBusy` | |
| バリデーション一覧 | `ItemsSource=ValidationIssues` | ERROR/WARNを色分け（Style） |
| Providerテスト | `Command=TestLlmCommand/TestTtsCommand` | 結果を `Last*Test` に反映 |
| キャッシュ削除 | `Command=ClearCacheCommand` | 実行前確認ダイアログ（任意） |

---

## 8. 付録：画面間で共通利用するVMモデル（最小）

### 8.1 ProviderStatusVm（表示用）
- `ProviderHealthVm Llm` / `ProviderHealthVm Tts`
  - `string ProviderType`
  - `string? ModelOrPreset`
  - `ProviderHealthState State`（OK/WARN/ERROR/UNKNOWN）
  - `string? Message`（最終エラー要約）
  - `DateTimeOffset? LastCheckedAt`

### 8.2 QueueItemVm（再生キュー）
- `string CorrelationId`
- `string SegmentType`
- `string Title`
- `TimeSpan? Duration`
- `QueueItemState State`（Queued/Preparing/Ready/Playing/Played/Skipped）

### 8.3 Notification
- `UiNotificationVm`
  - `NotificationLevel`（Info/Warn/Error）
  - `string Message`
  - `DateTimeOffset At`
  - `string? CorrelationId`（任意：ログ追跡）

---

## 9. 実装上の注意（Codex実装に向けた落とし穴回避）

- すべてのUseCase呼び出しは `CancellationToken` を受け、画面離脱／局切替／アプリ終了でキャンセルできるようにする。
- コレクション更新はUIスレッドに限定する（Dispatcher）。
- Tune（周波数変更）は「入力値の変化」ではなく「確定タイミング」で行う（Commit/デバウンス）。
- DB初期化失敗はレター画面のみ縮退させ、メイン再生は継続する（止めない設計）。
