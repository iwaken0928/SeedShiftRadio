# SeedShiftRadio プログラム概要設計書（ドラフト）

- 版：0.1（ドラフト）
- 作成日：2026-01-04
- 対象：SeedShiftRadio（Windowsローカルアプリ / WPF、将来WinUI 3移行考慮）
- 参照：
  - 要件定義書 v0.3
  - アーキテクチャ方針設計書 v0.1
  - 画面レイアウト設計書（A案） v0.1
  - データ構造設計書（ハイブリッド） v0.1

---

## 1. 目的・スコープ

本書は、既存の要件・アーキテクチャ・画面・データ設計を踏まえて、実装に着手できる粒度で「プログラムの全体構造（モジュール分割・責務・主要フロー・I/F）」を定義する。

MVPの主眼は以下：
- 離散チャンネルの周波数操作による局切替
- 短尺セグメント＋先読みバッファによる途切れにくい連続再生
- LLM/TTS差し替え（MVPはOpenAI互換HTTP＋A.I.VOICE）
- レター投稿/管理/放送内取り上げ（レターコーナー）

---

## 2. 全体アーキテクチャ（論理レイヤ）

UI差し替え（WPF→WinUI 3）を成立させるため、UIをアプリケーション層へ依存させ、ドメイン・インフラをUIから隔離する。

### 2.1 レイヤ構成
1. **Presentation（UI）**
   - WPF View / ViewModel（MVVM）
   - Shell（トップナビ＋コンテンツ領域＋常時プレイヤー）

2. **Application（UseCase / Orchestration）**
   - 画面操作を「手続き」に落とした入口（Facade）
   - 局切替、再生制御、生成パイプライン指示、レター操作、設定保存など

3. **Domain（モデル / ルール）**
   - Station / ScheduleTemplate / SegmentDefinition / Letter 等のモデル
   - 周波数→Station解決、編成テンプレ解釈、レター選定ルール等（純粋ロジック）

4. **Infrastructure（実装詳細）**
   - LLM/TTS Provider、音声再生、キャッシュ、SQLite、ファイルI/O、ログ

---

## 3. ソリューション/プロジェクト構成（案）

`SeedShiftRadio.sln`

- `App.Presentation.Wpf`
  - Views（XAML）
  - ViewModels（状態・コマンド）
  - UIサービス（IUiDialogService, INotificationService 等のWPF実装）
- `App.Application`
  - UseCase（RadioUseCase / LetterUseCase / SettingsUseCase）
  - コーディネータ（PlayoutCoordinator）
  - DTO/Command（UI↔UseCase）
- `App.Domain`
  - Entities/ValueObjects（Station, Frequency, PersonalityProfile, Letter 等）
  - Services（StationResolver, ScheduleInterpreter, LetterPicker 等）
  - Enums（SegmentType, LetterStatus 等）
- `App.Infrastructure`
  - Providers
    - LLM: OpenAICompatibleLlmProvider（MVP）
    - LLM: FoundryLocalLlmProvider（将来）
    - TTS: AivVoiceTtsProvider（MVP）
  - Audio
    - AudioPlayer（再生）
    - MusicLibrary / TrackSelector
    - Mixer（将来：ダッキング/クロスフェード）
  - Persistence
    - SQLite（letters, threads, airings, optional history）
    - Repositories
  - Config
    - ConfigLoader/Validator（config.json）
    - UserSettings（user.config）
    - SecretResolver（apiKeyRef env 等）
  - Cache
    - FileCacheStore（script/audio）
    - GeneratedAssetIndex（任意：DB併用）
  - Logging
    - JsonlFileLogger（ローテーション）

> 依存方向：Presentation → Application → Domain。Infrastructure は Application にDIで注入。

---

## 4. 主要ユースケース（UI→UseCase）

### 4.1 ラジオ（メイン画面）
- Tune（周波数変更→局切替）
- ScanNext/ScanPrev（前後局へ移動）
- Play/Pause/Stop
- Caption表示制御（UI状態）
- キュー/バッファ状態表示

### 4.2 レター（レター画面）
- SubmitLetter（新規投稿）
- ListLetters（フィルタ/検索）
- ChangeLetterStatus（未読→保留→採用→返信済）
- GenerateReply（テキスト返信生成、スレッド追加）
- MarkAsOnAir（放送内取り上げ履歴追加）

### 4.3 設定（設定画面）
- Load/Save Config（config.json）
- Export/Import（主設定）
- Test Provider（LLM/TTS疎通）
- Cache設定・クリア
- UserSettings（音量、ウィンドウ状態等）

---

## 5. 主要コンポーネント設計

### 5.1 Application層：UseCase / Coordinator

#### RadioUseCase（Facade）
- `TuneToFrequency(double mhz)`
- `ScanNext() / ScanPrev()`
- `PlayPause() / Stop()`
- `GetStatus()`（UI表示用：NowPlaying/Queue/Buffer/ProviderStatus）

内部で `PlayoutCoordinator` を呼び、局切替や再生状態を一元制御する。

#### PlayoutCoordinator（手続き制御の中核）
- 局切替時の一連動作を責務として持つ
  1) 既存生成処理のキャンセル
  2) 再生停止（またはフェードアウト）
  3) キュー初期化
  4) 新StationContextを構築
  5) BufferManagerを起動し、Nセグメント先読み
  6) PlayoutEngineへ再生開始指示

#### LetterUseCase
- `SubmitLetter(dto)`
- `GetLetters(filter)`
- `ChangeStatus(letterId, status)`
- `GenerateReply(letterId)`
- `MarkAired(letterId, stationId, segmentId?)`

#### SettingsUseCase
- `LoadConfig(path)`
- `SaveConfig(config)`
- `ExportConfig(path)`
- `ImportConfig(path)`
- `TestLlm() / TestTts()`
- `ClearCache()`

---

### 5.2 Domain層：モデルとルール

- `Frequency`（ValueObject：double MHz）
- `Station`
  - `StationId`, `FrequencyMHz`, `Name`, `Genre`, `Seed`
  - `PersonalityProfile`, `Schedule`, `MusicRules`, `LetterPolicy`
- `ScheduleTemplate` / `SegmentDefinition`
  - セグメント種別、目標尺、テンプレID等
- `Letter` / `LetterThreadMessage` / `LetterAiring`
- `StationResolver`
  - `ResolveByFrequency(mhz) -> Station`
  - `Next/PrevStation(currentStationId)`
- `ScheduleInterpreter`
  - 現在の編成から次に必要なSegmentDefinitionを選ぶ
- `LetterPicker`
  - 採用（ADOPTED）優先、取り上げ済み回避などを実装可能（MVPは単純で可）

Domainは副作用を持たず、Applicationから呼ばれる「判断ロジック」を提供する。

---

### 5.3 Infrastructure層：再生と生成のパイプライン

#### PlayoutEngine
- `PlayoutQueueItem` を逐次消費し、AudioPlayerで再生する。
- キュー枯渇を防ぐため、Queue残量が閾値を下回ったら BufferManager に補充要求を出す（pull型）。

#### BufferManager
- 目標：常に `bufferSegments` 件（設定値）の「再生可能アイテム」を用意する。
- 生成はバックグラウンドで実行し、キャンセル（局切替/停止）に対応する。

#### SegmentPipeline（生成・準備）
- 入力：`StationContext` + `SegmentDefinition`
- 出力：`PlayoutQueueItem`（再生可能な音声/音源参照）
- 典型パス：
  - TALK/LETTER: `ScriptGenerator` → `TtsSynthesizer` → `CacheStore` → Queue投入
  - MUSIC: `TrackSelector` → Queue投入
  - JINGLE: 既定ファイル → Queue投入

#### ScriptGenerator
- `ILLMProvider.GenerateScript(context) -> string`
- キャッシュキー（input_hash）を計算し、既存台本キャッシュがあれば再利用。

#### TtsSynthesizer
- `ITtsProvider.Synthesize(text, voiceProfile) -> AudioAsset`
- A.I.VOICEは「音声ファイル生成」に統一（再生はAudioPlayerが担当）。

#### AudioPlayer（MVPは単純再生）
- 音声ファイル（WAV/MP3）を再生
- 将来：ミキサ層（BGM/Voice別音量、ダッキング、クロスフェード）

---

## 6. 代表シーケンス（テキスト図）

### 6.1 Tune（周波数変更→局切替）
```
UI(ViewModel)
  -> RadioUseCase.TuneToFrequency(mhz)
    -> StationResolver.ResolveByFrequency(mhz)
    -> PlayoutCoordinator.SwitchStation(station)
        - Cancel current pipeline
        - Stop/FadeOut AudioPlayer
        - PlayoutEngine.ResetQueue()
        - BufferManager.Prime(stationContext, N)
        - PlayoutEngine.Start()
```

### 6.2 再生ループ（バッファ維持）
```
PlayoutEngine
  loop:
    item = queue.Take()
    AudioPlayer.Play(item)
    if queue.Count < LowWatermark:
        BufferManager.FillAsync()
```

### 6.3 レター投稿→放送内取り上げ
```
UI -> LetterUseCase.SubmitLetter(dto)
   -> LetterRepository.Insert(letters)

放送（SegmentPipeline: LETTER）
  -> LetterPicker.Pick(ADOPTED first)
  -> ScriptGenerator(読み上げ＋回答)
  -> TTS
  -> queue投入
  -> 放送完了時:
       LetterUseCase.MarkAired(letterId, stationId)
       (必要なら status更新 / airings追加)
```

---

## 7. データ・永続化（ハイブリッド方針の具体化）

### 7.1 主設定（config.json）
- Station定義、周波数、seed、personalityProfile、編成、音源ルール、プロバイダ、キャッシュ等を保持
- `configVersion` によるスキーマ検証・将来マイグレーション

### 7.2 軽量設定（user.config）
- ウィンドウ位置・サイズ、音量、最後に選んだ局、右ペイン状態などUI状態に限定

### 7.3 運用データ（SQLite）
- MUST：letters / letter_threads / letter_airings
- SHOULD：play_history / generated_assets（履歴/キャッシュ索引）
- RepositoryパターンでApplicationからアクセス（UI直アクセス禁止）

### 7.4 技術ログ（ファイル）
- JSON Lines（1行1イベント）
- correlationIdを全経路で引き回し、生成/再生/エラーを関連付ける
- 日次＋サイズローテーション、保持期間設定

---

## 8. エラー処理・フォールバック（止めない設計）

MVPの最低ライン：
- LLM失敗：リトライ（設定回数）→ スキップ → 代替セグメント（短い案内/ジングル）→ BGM延長
- TTS失敗：リトライ→（可能なら別preset）→ テキスト字幕のみ→ スキップ
- 音源なし：音楽枠をトークへ置換、またはジングル挿入
- レター生成失敗：別レターへ切替／要約なし短縮／レターコーナースキップ
- すべてログ（ファイル）へ記録し、UIは非ブロッキング通知（トースト）で提示

---

## 9. 並行処理モデル（WPF前提）

- UIスレッド：ViewModel更新、コマンド受付
- 生成ワーカー：LLM/TTS（I/O中心）を非同期で実行
  - MVP推奨：TTSは単一ワーカー（A.I.VOICE連携安定化）
  - LLMは並列度を1〜2に制限（レート/リソース考慮）
- PlayoutEngine：キュー消費は単一コンシューマ（再生順序保証）
- キュー実装：`System.Threading.Channels`（推奨）またはBlockingCollection
- 取消：`CancellationToken` を Tune/Stop/アプリ終了で伝播

---

## 10. 拡張ポイント（将来フェーズに備えた差し替え口）

- UI移行：Presentation差し替え（WPF→WinUI 3）。UseCase/Domain/Infrastructureを維持。
- LLM Provider追加：Foundry Localを `ILLMProvider` 実装として追加
- ストリーミング生成：`ILLMProvider` の拡張（機能フラグ）＋SegmentPipeline差し替え
- 演出強化：AudioMixer導入（ダッキング/クロスフェード/SEレイヤ）
- 機密参照強化：apiKeyRefの解決方式追加（WinCred/DPAPI等）

---

## 11. MVP実装の優先順位（案）

1. 基盤：Configロード/検証、DI、ログ（JSONL）、例外基盤
2. ラジオ基本：StationResolver、Tune/Scan、簡易PlayoutEngine（音楽のみでもよい）
3. 生成：OpenAI互換LLM→台本、A.I.VOICE→音声ファイル、Talkセグメント再生
4. 先読み：BufferManager（N=2）で途切れにくさを担保
5. レター：SQLite CRUD＋画面（一覧/投稿/詳細）＋レターコーナーセグメント
6. 設定画面：Provider/Cache/Pathsの編集と保存、接続テスト、キャッシュクリア

---

## 付録A. 主要I/F一覧（案）

- `ILLMProvider`
  - `Task<string> GenerateScriptAsync(GenerateScriptContext ctx, CancellationToken ct)`
- `ITtsProvider`
  - `Task<AudioAsset> SynthesizeAsync(string text, VoiceProfile voice, CancellationToken ct)`
- `IAudioPlayer`
  - `Task PlayAsync(AudioSource source, PlaybackOptions opt, CancellationToken ct)`
  - `void Stop(StopOptions opt)`
- `ICacheStore`
  - `Task<CacheHit<string>> TryGetScriptAsync(key)`
  - `Task PutScriptAsync(key, value)`
  - `Task<CacheHit<AudioAsset>> TryGetAudioAsync(key)`
  - `Task PutAudioAsync(key, asset)`
- `ILetterRepository`
  - `Task InsertAsync(Letter)`
  - `Task<IReadOnlyList<Letter>> QueryAsync(filter)`
  - `Task UpdateStatusAsync(letterId, status)`
  - `Task AddThreadAsync(letterId, message)`
  - `Task AddAiringAsync(letterId, airing)`
- `IConfigProvider`
  - `AppConfig Load(path)`
  - `void Save(AppConfig config, path)`

