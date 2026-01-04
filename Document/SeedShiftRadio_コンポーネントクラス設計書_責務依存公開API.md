# SeedShiftRadio コンポーネント／クラス設計書（責務・依存・公開API）

- 版：0.1（ドラフト）
- 作成日：2026-01-04
- 対象：SeedShiftRadio（Windowsローカルアプリ / WPF、将来WinUI 3移行考慮）
- 参照：
  - SeedShiftRadio 要件定義書 v0.3
  - SeedShiftRadio アーキテクチャ方針設計書 v0.1
  - SeedShiftRadio 画面レイアウト設計書（A案） v0.1
  - SeedShiftRadio データ構造設計書（ハイブリッド） v0.1
  - SeedShiftRadio プログラム概要設計書 v0.1

---

## 1. 目的・スコープ

本書は、既存の要件／アーキテクチャ／画面／データ設計を踏まえ、実装に着手できる粒度で **コンポーネント／クラスの責務・依存関係・公開API** を定義する。

- MVPの中心：**短尺セグメント（20〜60秒）＋先読みバッファによる途切れにくい連続再生（Playout）**
- 主要機能：局切替（周波数スナップ／スキャン）、トーク生成（LLM）、読み上げ（TTS：A.I.VOICE）、音楽再生（ローカル音源）、レター機能、設定／キャッシュ／ログ

> 命名・APIはC#の一般的な慣習（`Async` + `CancellationToken`）を採用する。文書中の簡略名（TuneToFrequency等）は実装時に `TuneToFrequencyAsync` とする。

---

## 2. レイヤ構造と依存ルール

### 2.1 レイヤ
- **Presentation**：WPF（将来WinUI 3差し替え可能）。MVVMで状態表示／コマンド受付のみ
- **Application**：UseCase／Coordinator。UIからの要求を手続き的にオーケストレーション
- **Domain**：純粋ロジック（モデル・ルール・選定）。I/Oを持たない
- **Infrastructure**：LLM/TTS Provider、Audio再生、SQLite、ファイルI/O、キャッシュ、ログ

### 2.2 依存方向
- Presentation → Application → Domain
- Application →（Interface経由で）Infrastructure
- Domain は Infrastructure に依存しない（副作用禁止）
- 例外：Cross-cutting（ILogger/IClock 等）はインターフェースを介して注入

### 2.3 例外処理方針（止めない設計）
- 生成／再生は **止めない** を最優先し、失敗時は `IFallbackPolicy` により「再生可能アイテム」へ置換する
- UIへは非ブロッキング通知（トースト）で提示し、詳細は技術ログ（ファイル）へ出力

### 2.4 相関ID（Correlation）
- 生成→TTS→再生→ログまで **CorrelationId** を貫通させ、障害調査・追跡性を担保する

---

## 3. コンポーネント俯瞰図

```mermaid
flowchart LR
  subgraph Presentation
    VM[ViewModel
(Main/Letter/Settings)]
  end

  subgraph Application
    RU[RadioUseCase]
    LU[LetterUseCase]
    SU[SettingsUseCase]
    PC[PlayoutCoordinator]
    PE[PlayoutEngine]
    BM[BufferManager]
    SP[SegmentPipeline]
  end

  subgraph Domain
    SR[StationResolver]
    SI[ScheduleInterpreter]
    LP[LetterPicker]
    ST[Station/Program Models]
  end

  subgraph Infrastructure
    LLM[ILLMProvider]
    TTS[ITtsProvider]
    AP[IAudioPlayer]
    LRepo[ILetterRepository
(SQLite)]
    Cfg[IConfigProvider]
    Cache[ICacheStore]
    Log[IAppLogger]
    Music[IMusicLibrary]
  end

  VM --> RU
  VM --> LU
  VM --> SU

  RU --> PC
  PC --> PE
  PC --> BM
  BM --> SP

  RU --> SR
  BM --> SI
  SP --> LP
  SR --> ST
  SI --> ST

  SP --> LLM
  SP --> TTS
  PE --> AP
  LU --> LRepo
  SU --> Cfg
  SU --> Cache
  BM --> Cache
  SP --> Cache
  Application --> Log
  SP --> Music
```

---

## 4. Application層（UseCase / Coordinator）

### 4.1 `RadioUseCase`（Facade：ラジオ操作の単一入口）

**責務**
- 周波数操作（Tune/Scan）により局を決定し `PlayoutCoordinator` を通じて再生状態を一元制御
- UI表示のための状態（NowPlaying/Queue/Buffer/ProviderStatus）をDTOに整形して返す
- UI更新用イベントを発行（任意）

**依存**
- `IStationResolver`（Domain）
- `IPlayoutCoordinator`
- `IStatusQueryService`（任意：状態集約を分離する場合）
- `IAppLogger`

**公開API（案）**
```csharp
public interface IRadioUseCase
{
    Task TuneToFrequencyAsync(double mhz, CancellationToken ct);
    Task ScanNextAsync(CancellationToken ct);
    Task ScanPrevAsync(CancellationToken ct);

    Task PlayPauseAsync(CancellationToken ct);
    Task StopAsync(CancellationToken ct);

    Task<RadioStatusDto> GetStatusAsync(CancellationToken ct);

    IAsyncEnumerable<RadioEvent> SubscribeEventsAsync(CancellationToken ct); // 任意
}
```

---

### 4.2 `PlayoutCoordinator`（手続き制御の中核）

**責務**
- 局切替時の一連動作を責務として持つ  
  1) 生成処理のキャンセル  
  2) 再生停止（必要ならフェードアウト）  
  3) キュー初期化  
  4) `StationContext` 構築  
  5) `BufferManager.PrimeAsync` による先読み  
  6) `PlayoutEngine.StartAsync` による再生開始
- Stop時の後始末（生成停止・キュー停止・再生停止・状態更新）

**依存**
- `IPlayoutEngine`
- `IBufferManager`
- `IStationContextFactory`
- `IUserSettingsStore`（前回局/音量など軽量設定）
- `IAppLogger`

**公開API（案）**
```csharp
public interface IPlayoutCoordinator
{
    Task SwitchStationAsync(StationId stationId, CancellationToken ct);
    Task StartAsync(CancellationToken ct);
    Task StopAsync(StopOptions opt, CancellationToken ct);

    Task PrimeAsync(CancellationToken ct); // 起動時/局切替直後の先読み
    PlayoutSessionSnapshot GetSnapshot();
}
```

---

### 4.3 `LetterUseCase`（レター機能）

**責務**
- レター投稿、一覧/詳細、状態更新、返信生成、放送取り上げ履歴の記録
- UI用DTOへ整形（ページング／検索条件反映）
- 返信生成（LLM利用）を必要に応じて実行し、スレッドに追記

**依存**
- `ILetterRepository`（SQLite）
- `ILetterPicker`（Domain：コーナー選定ルール）
- `IReplyGenerator`（LLM利用：Infrastructure）
- `IClock`, `IIdGenerator`
- `IAppLogger`

**公開API（案）**
```csharp
public interface ILetterUseCase
{
    Task<LetterId> SubmitAsync(SubmitLetterCommand cmd, CancellationToken ct);
    Task<IReadOnlyList<LetterSummaryDto>> QueryAsync(LetterQuery query, CancellationToken ct);
    Task<LetterDetailDto> GetDetailAsync(LetterId id, CancellationToken ct);

    Task ChangeStatusAsync(LetterId id, LetterStatus status, CancellationToken ct);

    Task GenerateReplyAsync(LetterId id, CancellationToken ct);
    Task RegenerateReplyAsync(LetterId id, CancellationToken ct);

    Task MarkAiredAsync(LetterId id, StationId stationId, SegmentId? segmentId, CancellationToken ct);
}
```

---

### 4.4 `SettingsUseCase`（設定／疎通／キャッシュ管理）

**責務**
- 主設定（config.json）の読み書き・バリデーション・エクスポート/インポート
- Provider疎通（LLM/TTS）テスト
- キャッシュ設定・削除・統計取得

**依存**
- `IConfigProvider`
- `IProviderFactory`
- `ICacheAdminService`
- `IUserSettingsStore`
- `IAppLogger`

**公開API（案）**
```csharp
public interface ISettingsUseCase
{
    Task<AppConfigDto> LoadConfigAsync(string path, CancellationToken ct);
    Task SaveConfigAsync(AppConfigDto config, string path, CancellationToken ct);

    Task ExportConfigAsync(string exportPath, CancellationToken ct);
    Task ImportConfigAsync(string importPath, CancellationToken ct);

    Task<ProviderTestResult> TestLlmAsync(CancellationToken ct);
    Task<ProviderTestResult> TestTtsAsync(CancellationToken ct);

    Task<CacheStatsDto> GetCacheStatsAsync(CancellationToken ct);
    Task ClearCacheAsync(CancellationToken ct);
}
```

---

## 5. Domain層（モデル / ルール：副作用なし）

### 5.1 主要モデル（Entity / ValueObject）

- `Station`：`StationId`, `FrequencyMHz`, `Seed`, `PersonalityProfile`, `ScheduleTemplate`, `MusicRules`, `LetterPolicy`
- `PersonalityProfile`：`StylePrompt`, `VoiceProfile`, `NgPolicy`, `TopicBias`
- `ScheduleTemplate` / `SegmentDefinition`：`SegmentType`, `TargetDurationSec`, `PromptTemplateId`, `Weight`
- `Letter` / `LetterThreadMessage` / `LetterAiring`
- `Frequency`（ValueObject：離散チャンネルの比較・表示・スナップ補助）

> Domainモデルは、DB構造（SQLite）や設定ファイル（config.json）と直接結合しない。Application/Infrastructureで変換する。

---

### 5.2 Domain Service

#### 5.2.1 `IStationResolver` / `StationResolver`
**責務**
- 周波数（mhz）→ Station 解決（離散スナップ／最近傍）
- `Next/Prev` スキャン（昇順リストを循環）

**依存**
- `IStationCatalog`（設定からロードしたStation一覧）

**公開API（案）**
```csharp
public interface IStationResolver
{
    Station ResolveByFrequency(double mhz);
    Station Next(StationId current);
    Station Prev(StationId current);
}
```

#### 5.2.2 `IScheduleInterpreter` / `ScheduleInterpreter`
**責務**
- Stationの編成から「次に生成すべき `SegmentDefinition`」を決定
- 目標尺や重み（Weight）等の解釈

**公開API（案）**
```csharp
public interface IScheduleInterpreter
{
    SegmentDefinition NextSegment(Station station, ProgramState state);
}
```

#### 5.2.3 `ILetterPicker` / `LetterPicker`
**責務**
- レターコーナーで取り上げる対象の選定（採用優先、取り上げ済み回避等）

**公開API（案）**
```csharp
public interface ILetterPicker
{
    LetterId? Pick(IReadOnlyList<Letter> candidates, LetterPickPolicy policy);
}
```

---

## 6. Infrastructure層（実装：Playout/Provider/永続化/設定/キャッシュ/ログ）

## 6.1 Playout（再生・キュー・バッファ）

### 6.1.1 `IPlayoutEngine` / `PlayoutEngine`
**責務**
- `IPlayoutQueue` の `PlayoutQueueItem` を消費して `IAudioPlayer` に委譲し再生
- キュー残量が `LowWatermark` を下回る場合に `IBufferManager.FillIfNeededAsync` をトリガ
- NowPlaying変更イベントを発行（UI字幕更新用）

**依存**
- `IPlayoutQueue`
- `IAudioPlayer`
- `IBufferManager`（補充トリガ）
- `IPlayHistoryRepository`（任意：履歴DB）
- `IAppLogger`

**公開API（案）**
```csharp
public interface IPlayoutEngine
{
    Task StartAsync(CancellationToken ct);
    Task StopAsync(StopOptions opt, CancellationToken ct);
    Task ResetQueueAsync(CancellationToken ct);

    PlayoutEngineState GetState();
    event EventHandler<NowPlayingChangedEventArgs>? NowPlayingChanged; // 任意
}
```

### 6.1.2 `IPlayoutQueue` / `ChannelPlayoutQueue`
**責務**
- 再生順序保証のキュー（単一consumer）
- `Count` と閾値監視のためのメトリクス提供

**公開API（案）**
```csharp
public interface IPlayoutQueue
{
    ValueTask EnqueueAsync(PlayoutQueueItem item, CancellationToken ct);
    ValueTask<PlayoutQueueItem> DequeueAsync(CancellationToken ct);

    int Count { get; }
    void Clear();
}
```

### 6.1.3 `IBufferManager` / `BufferManager`
**責務**
- 常に `bufferSegments` 件（設定値）の「再生可能アイテム」を確保する（先読み）
- 局切替／停止でキャンセル伝播
- 生成並列度（LLM/TTS）の上限を制御

**依存**
- `IScheduleInterpreter`（次セグメント決定）
- `ISegmentPipeline`（生成）
- `IPlayoutQueue`
- `IFallbackPolicy`
- `IAppLogger`

**公開API（案）**
```csharp
public interface IBufferManager
{
    Task PrimeAsync(StationContext ctx, int targetCount, CancellationToken ct);
    Task FillIfNeededAsync(StationContext ctx, CancellationToken ct);

    void CancelCurrent(); // 局切替/停止
    BufferStatus GetStatus();
}
```

---

## 6.2 Segment生成パイプライン

### 6.2.1 `ISegmentPipeline` / `SegmentPipeline`
**責務**
- `SegmentDefinition` → `PlayoutQueueItem` へ変換するパイプライン
- 種別分岐
  - TALK/LETTER：台本生成→TTS→音声
  - MUSIC：選曲（ローカル音源）
  - JINGLE：固定音源

**依存**
- `IScriptGenerator`
- `ITtsSynthesizer`
- `ITrackSelector`
- `IJingleProvider`
- `ICorrelationIdFactory`
- `IAppLogger`

**公開API（案）**
```csharp
public interface ISegmentPipeline
{
    Task<PlayoutQueueItem> BuildAsync(StationContext ctx, SegmentDefinition seg, CancellationToken ct);
}
```

### 6.2.2 `IScriptGenerator` / `ScriptGenerator`
**責務**
- LLMへ渡すコンテキストを構築（Station/Personality/Segment/Letter等）
- scriptキャッシュを参照し、ヒット時はLLM呼び出しを回避

**依存**
- `ILLMProvider`
- `IPromptComposer`
- `ICacheStore`（script）
- `IAppLogger`

**公開API（案）**
```csharp
public interface IScriptGenerator
{
    Task<string> GenerateAsync(GenerateScriptContext ctx, CancellationToken ct);
}
```

### 6.2.3 `ITtsSynthesizer` / `TtsSynthesizer`
**責務**
- audioキャッシュを参照し、ヒット時はTTS呼び出しを回避
- `ITtsProvider` を利用して音声生成し `AudioAsset` を返す
- 失敗時は上位（Pipeline/Buffer/Fallback）で扱える例外として返す（握りつぶさない）

**依存**
- `ITtsProvider`
- `ICacheStore`（audio）
- `IAppLogger`

**公開API（案）**
```csharp
public interface ITtsSynthesizer
{
    Task<AudioAsset> SynthesizeAsync(string text, VoiceProfile voice, CancellationToken ct);
}
```

### 6.2.4 `ITrackSelector` / `TrackSelector`
**責務**
- ルールに沿った選曲（直近重複回避、ジャンル制限等）
- 音源不足時は例外またはnullを返し、`IFallbackPolicy` へ繋ぐ

**依存**
- `IMusicLibrary`
- `IRandom`
- `IPlayHistoryRepository`（任意）
- `IAppLogger`

**公開API（案）**
```csharp
public interface ITrackSelector
{
    Task<MusicTrack> PickNextAsync(MusicRules rules, CancellationToken ct);
}
```

---

## 6.3 フォールバック（止めない設計）

### 6.3.1 `IFallbackPolicy` / `FallbackPolicy`
**責務**
- LLM/TTS/Music不足等の障害を「再生可能アイテム」へ置換する

**依存**
- `IJingleProvider`
- `IAnnouncementGenerator`（固定文／ローカルテンプレ）
- `IAppLogger`

**公開API（案）**
```csharp
public interface IFallbackPolicy
{
    PlayoutQueueItem OnScriptFailed(StationContext ctx, SegmentDefinition seg, Exception ex);
    PlayoutQueueItem OnTtsFailed(StationContext ctx, SegmentDefinition seg, string script, Exception ex);
    PlayoutQueueItem OnMusicNotFound(StationContext ctx, SegmentDefinition seg);
}
```

---

## 6.4 Provider（LLM/TTS差し替え）

### 6.4.1 `ILLMProvider`
**責務**
- 台本（テキスト）生成を提供（MVP：OpenAI互換HTTP）
- 将来：Foundry Local等のローカルLLM実装を追加

**公開API（案）**
```csharp
public interface ILLMProvider
{
    Task<string> GenerateScriptAsync(GenerateScriptContext ctx, CancellationToken ct);
    ProviderCapabilities Capabilities { get; }
}
```

### 6.4.2 `ITtsProvider`
**責務**
- 音声生成を提供（MVP：A.I.VOICE連携）
- 出力はファイルパスまたはバイト列等で `AudioAsset` に統一する（再生責務は持たない）

**公開API（案）**
```csharp
public interface ITtsProvider
{
    Task<AudioAsset> SynthesizeAsync(string text, VoiceProfile voice, CancellationToken ct);
    ProviderCapabilities Capabilities { get; }
}
```

### 6.4.3 `IProviderFactory`
**責務**
- configから Provider 実体を生成（`apiKeyRef` の参照解決を含む）

**公開API（案）**
```csharp
public interface IProviderFactory
{
    ILLMProvider CreateLlm(ProviderConfig cfg);
    ITtsProvider CreateTts(ProviderConfig cfg);
}
```

---

## 6.5 Audio再生

### 6.5.1 `IAudioPlayer`
**責務**
- 音声ファイルの再生／停止（MVPは単純再生）
- 将来：ミキサ（BGM/Voice分離、ダッキング、クロスフェード）を追加可能にする

**公開API（案）**
```csharp
public interface IAudioPlayer
{
    Task PlayAsync(AudioSource source, PlaybackOptions opt, CancellationToken ct);
    Task StopAsync(StopOptions opt, CancellationToken ct);

    Task SetMasterVolumeAsync(double volume, CancellationToken ct);
}
```

---

## 6.6 永続化（SQLite）・設定・キャッシュ・ログ

### 6.6.1 `ILetterRepository`（SQLite）
**責務**
- letters / threads / airings のCRUD
- レター一覧・検索・状態更新を効率よく実行する（インデックスはデータ設計書準拠）

**公開API（案）**
```csharp
public interface ILetterRepository
{
    Task InsertAsync(Letter letter, CancellationToken ct);
    Task<IReadOnlyList<Letter>> QueryAsync(LetterQuery query, CancellationToken ct);
    Task<Letter?> FindAsync(LetterId id, CancellationToken ct);

    Task UpdateStatusAsync(LetterId id, LetterStatus status, CancellationToken ct);
    Task AddThreadAsync(LetterThreadMessage msg, CancellationToken ct);
    Task AddAiringAsync(LetterAiring airing, CancellationToken ct);
}
```

### 6.6.2 `IConfigProvider`（config.json）
**責務**
- Load/Save、`configVersion` 検証、将来のマイグレーション余地
- パス解決（相対→絶対）、機密参照（apiKeyRef）の検証

**公開API（案）**
```csharp
public interface IConfigProvider
{
    Task<AppConfig> LoadAsync(string path, CancellationToken ct);
    Task SaveAsync(AppConfig config, string path, CancellationToken ct);
    ValidationResult Validate(AppConfig config);
}
```

### 6.6.3 `IUserSettingsStore`（user.config）
**責務**
- UI状態（ウィンドウ位置・音量・前回局など）に限定して保持

**公開API（案）**
```csharp
public interface IUserSettingsStore
{
    UserSettings Load();
    void Save(UserSettings settings);
}
```

### 6.6.4 `ICacheStore` / `ICacheAdminService`
**責務**
- script/audioキャッシュの読み書き、TTL/容量/LRU等の削除ポリシー
- 管理機能（削除／統計取得）は `ICacheAdminService` として分離可

**公開API（案）**
```csharp
public interface ICacheStore
{
    Task<CacheHit<string>> TryGetScriptAsync(CacheKey key, CancellationToken ct);
    Task PutScriptAsync(CacheKey key, string script, CacheMetadata meta, CancellationToken ct);

    Task<CacheHit<AudioAsset>> TryGetAudioAsync(CacheKey key, CancellationToken ct);
    Task PutAudioAsync(CacheKey key, AudioAsset asset, CacheMetadata meta, CancellationToken ct);
}

public interface ICacheAdminService
{
    Task<CacheStats> GetStatsAsync(CancellationToken ct);
    Task EvictAsync(EvictionPolicy policy, CancellationToken ct);
}
```

### 6.6.5 `IAppLogger`（ファイルJSONL）
**責務**
- 技術ログ（障害調査・観測）をローテーション付きで保存
- 機密情報（APIキー等）は出力しない（マスク／参照IDのみ）

**公開API（案）**
```csharp
public interface IAppLogger
{
    void Info(LogEvent evt);
    void Warn(LogEvent evt);
    void Error(LogEvent evt, Exception ex);
}
```

---

## 7. Presentation層（WPF/WinUI：最小責務）

> Presentationは **UseCase呼び出しと状態表示** に責務を限定する。生成・再生・永続化・I/Oは持たない。

### 7.1 `MainViewModel`（ラジオ画面）
**責務**
- 周波数／局／再生状態／字幕／キュー／バッファの表示
- ユーザー操作（Tune/Scan/Play/Stop）を `IRadioUseCase` へ委譲
- 画面レイアウト（A案）のペイン状態を保持（user.config）

**依存**
- `IRadioUseCase`
- `INavigationService`
- `INotificationService`

**公開API（WPFバインディング）**
- Properties：`CurrentFrequencyMhz`, `CurrentStation`, `IsPlaying`, `CaptionText`, `QueueItems`, `BufferStatus`
- Commands：`TuneToFrequencyCommand`, `ScanNextCommand`, `TogglePlayPauseCommand`, `StopCommand`

### 7.2 `LetterViewModel` / `SettingsViewModel`
- `ILetterUseCase` / `ISettingsUseCase` を呼び出し、DTOを表示用に保持する
- エラーはトースト表示、詳細はログへ

---

## 8. 実装に向けた補足（最低限）

### 8.1 スレッド／キャンセル伝播（必須）
- `PlayoutCoordinator.SwitchStationAsync` は「新しい局のCancellationTokenSource」を生成し、`BufferManager` と `PlayoutEngine` に伝播する
- キュー消費（再生）と生成（先読み）は別タスクで動作する

### 8.2 DI登録（例：概念）
- ApplicationはInterfaceで受け取り、Infrastructure実装を注入する
- UI層は `IRadioUseCase` などUseCaseのみを解決する

---

## 付録A：主要インターフェース一覧（実装優先順）

1. `IRadioUseCase`, `IPlayoutCoordinator`
2. `IPlayoutEngine`, `IBufferManager`, `IPlayoutQueue`
3. `ISegmentPipeline`, `IScriptGenerator`, `ITtsSynthesizer`
4. `ILLMProvider`, `ITtsProvider`, `IProviderFactory`
5. `IAudioPlayer`
6. `ILetterRepository`, `IConfigProvider`, `ICacheStore`, `IAppLogger`

