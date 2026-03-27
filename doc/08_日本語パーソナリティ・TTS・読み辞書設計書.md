# AIローカルラジオアプリ 日本語パーソナリティ・TTS・読み辞書設計書

## 1. 目的

本書は日本語品質を保ちながら、人格設定と音声設定を分離するための設計を定義する。

## 2. 基本方針

- `Language Persona` と `Voice Persona` を分離する
- Web の標準は `Server-side TTS`
- 将来の Native Client 向けに `Client-side TTS` 契約を維持する
- 読み辞書と正規化は TTS エンジンから独立させる

## 3. Persona モデル

### 3.1 Language Persona

| 項目 | 説明 |
|---|---|
| `displayName` | 表示名 |
| `firstPerson` | 一人称 |
| `secondPerson` | 二人称 |
| `sentenceStyle` | 敬体 / 常体 |
| `phraseTraits` | 語尾や口癖 |
| `emotionTemperature` | 落ち着き / 熱量 |
| `topicBias` | 話題傾向 |
| `ngPolicy` | 禁則 |
| `letterTone` | レター回答時の態度 |

### 3.2 Voice Persona

| 項目 | 説明 |
|---|---|
| `engineType` | `VOICEVOX`, `AIVIS`, `SBV2` など |
| `speakerKey` | 話者ID |
| `styleKey` | スタイルID |
| `speed` | 話速 |
| `pitch` | ピッチ |
| `volume` | 音量 |
| `supportsClientSideTts` | Native 用可否 |
| `clientAdapterKey` | 将来の VOICEROID 系接続キー |

## 4. 正規化パイプライン

1. `JapaneseScriptNormalizer` が記号、URL、絵文字を整理する
2. `PronunciationDictionaryService` が読み変換を行う
3. `SentenceSplitter` が長文を短文化する
4. `PersonaStyleResolver` が語尾やテンポを補正する
5. `TtsVoiceRouter` が適切な Voice Persona を選ぶ
6. `JapaneseQualityGuard` が最終検査する

## 5. 読み辞書

### 5.1 辞書レイヤー

- 共通辞書
- station 固有辞書
- 一時セッション辞書

### 5.2 辞書項目

| 項目 | 説明 |
|---|---|
| `surface` | 原文 |
| `reading` | 読み |
| `scope` | global / station / session |
| `priority` | 優先度 |
| `note` | 管理用メモ |

## 6. `SpeechDirective` への変換

TTS 前の中立データとして以下を保持する。

- `text`
- `normalizedText`
- `pronunciationHints`
- `emotion`
- `tempo`
- `pauseHints`
- `personaRef`
- `voiceHint`

Web では主にデバッグ表示用、Native では実行用とする。

- `SpeechDirectiveAssembler` は `queue item`, `Language Persona`, `Voice Persona`, 必要時 `client_capabilities` を使って `voiceHint` を決定する
- `clientId` が与えられ、`preferredPlaybackMode=CLIENT_TTS` かつ `localVoiceProfiles` があれば、その候補を既定 `Voice Persona` より優先してよい

## 7. TTS Provider 方針

| 候補 | 位置づけ |
|---|---|
| `VOICEVOX` | MVP 第一候補 |
| `AivisSpeech` | よりキャラクター性を重視する代替 |
| `Style-Bert-VITS2` | 高自由度だが運用難度が高い代替 |

初期は自然さの極限より `導入しやすさ`, `安定性`, `読み制御のしやすさ` を優先する。

## 8. 品質ルール

- 一文は 60 文字程度を上限目安とし、長い場合は分割する
- 英数字混在語は読み辞書または正規化ルールで補正する
- URL は全文読み上げしない
- 放送に不向きな攻撃表現は言い換える
- punctuation を過剰に使わない

## 9. 生成物の扱い

- `normalizedText` は DB に保持して再現可能にする
- TTS の入力原文と実再生テキストが異なる場合は両方保持する
- Voice Persona 変更時は TTS キャッシュを無効化する

## 10. 将来拡張

- 掛け合い対応のため `speakerRole` を追加する
- Native Client 側で VOICEROID 系へ `clientAdapterKey` を使ってマッピングする
- station ごとに読み辞書の UI 編集機能を追加する
