# SeedShiftRadio アーキテクチャ方針設計書（ドラフト）

- バージョン: 0.1
- 作成日: 2026-01-04
- 対象: ローカル実行のAIラジオ風アプリ（Windows中心）

## 1. 目的と範囲

### 1.1 目的
- ローカル環境で「ラジオ風」体験を、途切れにくい連続再生（プレイアウト）で提供する。
- LLM/TTSをプロバイダ抽象化により差し替え可能にし、ローカルLLM/ローカルTTS実験基盤として拡張可能な構造を実現する。
- UIはWPFで構築しつつ、将来的なWinUI 3移行（UIレイヤ差し替え）を現実的にする。

### 1.2 範囲（MVP中心）
- 離散チャンネルによる局切替（周波数スナップ/スキャン）
- 短尺セグメント（20〜60秒）＋先読みバッファによる連続再生
- ローカル音源再生（BGM/ジングル等のキュー投入）
- トーク生成（LLM→台本）と音声化（TTS→音声資産）
- レター投稿/管理/放送内での取り上げ（コーナー化）

> 将来フェーズとして、長尺/ストリーミング生成、演出強化（ダッキング/クロスフェード等）、Foundry Local連携の高度化を想定する。

---

## 2. 前提・制約（アーキテクチャに効く確定事項）
- 周波数は離散チャンネル方式（スナップ/スキャン対象）。
- パーソナリティはチャンネルごとにシード保存し、再現性のため生成結果（PersonalityProfile）も保持する。
- MVPは短尺セグメント方式＋先読みバッファで途切れにくい体験を優先する（バッファ数は設定可能）。
- キャッシュはオプションで有効/無効、TTL/容量/削除ポリシー等を設定で制御する。
- 設定は基本設定ファイル（JSON/YAML）に保存し、`configVersion`、エクスポート/インポート、`apiKeyRef`参照方式を採用する。
- UI関連はWPFで作成するが、WinUI 3への移行余地を残す。
- A.I.VOICE連携は採用する。
- Foundry Localは将来フェーズとして、追加実装できる余地を残す（MVPでは必須要件としない）。

---

## 3. 技術方針（採用方針）

### 3.1 言語・ランタイム
- C# / .NET（現行LTS）を採用し、Windowsローカルアプリとして構築する。

### 3.2 UI
- **現行フェーズ**：WPF（MVVM）で実装する。
- **移行考慮**：WinUI 3移行余地を残すため、UIは「View/ViewModel」に閉じ、アプリケーションサービス層（UseCase）をUI非依存にする。
- UI固有機能（ダイヤル入力、ファイルピッカー、通知、設定エクスポートUI等）は抽象化し、WPF実装／WinUI実装を差し替え可能にする。

### 3.3 音声生成（TTS）
- TTSは差し替えI/Fで抽象化する。
- **A.I.VOICE連携を採用**し、TTSプロバイダ実装の一つとして組み込む。
- MVPの連続再生品質を担保するため、TTS生成レイテンシは先読みバッファで吸収する。

### 3.4 LLM
- LLMも差し替えI/Fで抽象化する。
- MVPは「OpenAI互換等のHTTP呼び出し」を基準実装とする。
- **Foundry Localは将来フェーズ**：追加Providerとして差し込める構造にする（設定で切替）。

---

## 4. 論理アーキテクチャ（層構造）

WinUI 3移行を見据え、UI技術から切り離せる構造を採用する。

### 4.1 レイヤ
1. **Presentation（UI）**
   - WPF View / ViewModel（状態とコマンド中心）
   - 将来：WinUI 3 Viewへ差し替え可能

2. **Application（UseCase / Orchestration）**
   - 局切替、再生開始/停止、セグメント生成指示、レター操作などの手続き的制御
   - UIから呼ばれる唯一の入口（Facade）
   - 例：`StartStation(stationId)`, `Tune(frequency)`, `SubmitLetter(...)`, `ToggleCache(...)`

3. **Domain（モデル / ルール）**
   - Station / PersonalityProfile / ScheduleTemplate / SegmentDefinition / Letter 等のドメインモデル
   - 周波数→Station解決、編成テンプレ解釈、レター選定ルール等（純粋ロジック）

4. **Infrastructure（実装詳細）**
   - LLM/TTS Provider実装、Audio再生、キャッシュ、DB、ファイルI/O、ログ、外部API

---

## 5. 主要コンポーネント設計

### 5.1 Playout（再生制御）コア
要件上の「連続再生（キュー制御）」を満たす中核。

#### PlayoutEngine
- PlayoutQueue（`PlayoutQueueItem`）を消費し、トーク/音楽/ジングル/レターを連続再生する。
- 局切替時は「現在再生停止またはフェードアウト → キュー初期化 → 切替先のオープニング/次セグメントへ」を基本動作とする。
- 先読みバッファ（Nセグメント）を維持する（設定可能）。

#### SegmentPipeline（生成・準備のパイプライン）
- Talk/Letter：`LLMで台本生成 → TTSで音声化 → キューへ投入`
- Music：ローカル音源からプレイリスト生成しキューへ投入
- 生成処理はキャンセル可能とし、局切替・停止時の中断に対応する。

> 将来（次フェーズ）でジングル/SE、ダッキング/クロスフェードに拡張する余地を、PlayoutEngine内部の「ミキサ層」として確保する。

### 5.2 Provider層（LLM/TTS差し替え）
共通インターフェース＋適応層（アダプタ）で差異を吸収する。

#### ILLMProvider（概念）
- `GenerateScript(context) -> scriptText`
- 将来拡張：ストリーミング、構造化出力、ツール呼び出し等は「機能フラグ＋適応層」で吸収する。

#### ITtsProvider（概念）
- `Synthesize(scriptText, voiceProfile) -> audioAsset`
- 将来拡張：ストリーミングTTS、発話単位の増減など。

#### ProviderFactory
- 設定（providerType/baseUrl/model/apiKeyRef…）から具体実装を生成する。

##### A.I.VOICE採用（TTS Provider実装）
- `AIVoiceTtsProvider` を **ITtsProvider実装**として追加する。
- 方針：
  - 生成結果は **音声ファイル（WAV等）**として扱い、PlayoutEngineは“ファイル/ストリーム再生”に専念する（TTS側に再生責務を持たせない）。
  - `voiceProfile`（voiceId/速度/ピッチ等）をA.I.VOICE側パラメータへマッピングできるよう、設定項目を保持する。
  - 生成キューは直列（単一ワーカー）を基本とし、先読みバッファで体験を担保する。

##### Foundry Local（将来フェーズ）
- `OpenAICompatibleLLMProvider`（HTTP）を基準実装として維持し、`providerType: foundry_local` 等で追加実装できる余地を残す。
- Providerの差異は「実装差し替え」で閉じ込め、Application/Domain/Presentationへ波及させない。

---

## 6. データ・永続化方針

### 6.1 設定（2段構え）
- **主設定（移植・共有したい）**：`config.json`（推奨）
  - `configVersion` を持ち、将来のスキーマ移行に備える。
  - LLM/TTSのAPIキーは直書き禁止とし、`apiKeyRef`（例：環境変数参照）で解決する。
  - Station（周波数/seed/personalityProfile/編成/音源ルール/レター方針）を格納する。
  - UIからエクスポート/インポート可能とする。

- **軽量ユーザー設定（端末依存でよい）**：`user.config`（WPF標準User Settings 等）
  - ウィンドウサイズ、最後に選択した局、音量等の“なくても動く”UI状態のみ。
  - 主設定の保存場所（パス）をユーザーごとに切り替える場合は、その“パス”をここへ保存する。

### 6.2 運用データ（DB）
- レター（お便り）は永続化対象（未読/保留/採用/返信済の状態遷移、pickedAt等）。
- DBはローカル前提のため、実装容易性・移植性の観点でSQLiteを第一候補とする。
- 生成履歴やメタ情報（キャッシュ索引）も必要に応じてDB側で管理する。

### 6.3 キャッシュ
- 台本（Script）・音声（Audio）の生成資産をキャッシュし、TTL/最大容量/LRU等で制御する。
- 生成失敗時はキャッシュ再利用もフォールバック手段として使用する。

---

## 7. 可用性・エラー処理（止めない設計）
- LLM/TTS失敗でもアプリ全体が停止しない（リトライ/スキップ/代替/キャッシュ再利用等）。
- 「無音にならない」ことを優先し、失敗時は短い案内セグメント、BGM延長、別コーナー差し替え等の回避策を用意する。
- レター処理失敗時は別レターへ切替、返信失敗は保留にして再生成可能とする。

---

## 8. セキュリティ・安全設計（ローカル前提でも最低限）
- APIキー等の機密情報は設定直書きせず `apiKeyRef` を利用し、ログ/画面露出を禁止する。
- レターはLLM入力となるため、入力は引用扱いとして取り込み、プロンプト注入に対する最小限の防御（システム指示の優先、出力制約、NGポリシー等）を行う。

---

## 9. UI移行（WPF → WinUI 3）を成立させる設計ガイド
移行時に“書き換え範囲”をUIに閉じ込める。

- UIは **ViewModel層で完結**し、UseCase（Application層）を呼び出すだけにする。
- 音声再生・生成・DB・キャッシュ等はすべてUI非依存サービスとしてDI登録する。
- UI固有要素（ダイヤル操作、通知、ファイルピッカー）は `IUiDialogService` / `INotificationService` 等で抽象化し、WPF実装／WinUI実装を差し替える。

---

## 10. 将来拡張（余地の残し方）

### 10.1 Foundry Local（将来フェーズ）
- Provider追加で対応できることを設計上担保（ProviderFactory + 機能フラグ吸収）。

### 10.2 長尺/ストリーミング生成
- MVPは短尺＋先読みだが、将来拡張として長尺/ストリーミング生成を検討可能とする。

### 10.3 演出強化
- ジングル/SE、ダッキング/クロスフェードは次フェーズ候補（PlayoutEngineのミキサ層で受ける）。

---

## 11. 未決事項（設計判断ポイント）
- 機密情報保管方式：env参照固定か、暗号化保存等も将来対応するか。
- 生成単位拡張の判断基準（性能計測指標）をどのタイミングで定義するか。
- レターモデレーション粒度、読み上げ方針（全文/要約/抜粋）の既定値。
- 音声演出（フェード/ミキシング）のMVP要件（最小実装ライン）の明確化。

---

## 付録：プロジェクト分割案（例）
- `App.Presentation.Wpf`（将来：`App.Presentation.WinUI`）
- `App.Application`（UseCase）
- `App.Domain`（モデル/ルール）
- `App.Infrastructure`
  - `Infrastructure.Providers.Llm.OpenAICompatible`
  - `Infrastructure.Providers.Llm.FoundryLocal`（将来）
  - `Infrastructure.Providers.Tts.LocalHttp`
  - `Infrastructure.Providers.Tts.AIVoice`
  - `Infrastructure.Audio`（再生/ミキサ）
  - `Infrastructure.Persistence`（SQLite）
  - `Infrastructure.Cache`（ファイル＋メタ）
