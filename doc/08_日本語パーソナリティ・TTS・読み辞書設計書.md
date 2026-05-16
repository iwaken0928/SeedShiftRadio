# AIローカルラジオアプリ 日本語パーソナリティ・TTS・読み辞書設計書

## 1. 目的

本書は日本語品質を保ちながら、人格設定と音声設定を分離するための設計を定義する。

## 2. 基本方針

- `Language Persona` と `Voice Persona` を分離する
- チャンネル、つまり `Station` ごとに異なる `Voice Persona` を割り当てられるようにする
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
| `engineType` | `VOICEVOX`, `AIVIS`, `SBV2`, `IRODORI_TTS` など |
| `scope` | `GLOBAL` / `STATION`。局専用の声か共通利用できる声か |
| `stationId` | `scope=STATION` の場合の所属局 |
| `providerKey` | 利用する TTS provider key。未指定時は engineType から既定 provider を解決する |
| `speakerKey` | 話者ID |
| `styleKey` | スタイルID |
| `speed` | 話速 |
| `pitch` | ピッチ |
| `volume` | 音量 |
| `providerOptions` | engine 固有の安全な追加設定。Irodori では style preset, emoji style, response format, chunking 方針など |
| `referenceVoiceRef` | 参照音声を使う engine 用の voice id または dataRoot 相対参照。Irodori では `voices/` 上の voice id を基本にする |
| `consentPolicyRef` | 参照音声・声質利用の同意、ライセンス、禁止事項への参照 |
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
- 先行生成や archive replay では、実行時に組み立て直すのではなく最終 `SpeechDirective` snapshot を script asset metadata へ保存して再利用してよい

## 7. TTS Provider 方針

| 候補 | 位置づけ |
|---|---|
| `VOICEVOX` | MVP の安定候補、Irodori 失敗時の fallback |
| `Irodori-TTS` | 日本語の自然なラジオパーソナリティ音声、参照音声による声質固定、感情・スタイル制御を狙う server-side TTS 候補 |
| `AivisSpeech` | よりキャラクター性を重視する代替 |
| `Style-Bert-VITS2` | 高自由度だが運用難度が高い代替 |

初期は自然さの極限より `導入しやすさ`, `安定性`, `読み制御のしやすさ` を優先する。ただし Irodori-TTS はラジオのパーソナリティ用途と相性がよいため、VOICEVOX を安定 fallback に残しながら、server-side TTS の高品質候補として採用設計に入れる。

### 7.1 Irodori-TTS 調査結果と採用判断

調査日: 2026-05-16

参照した一次情報:

- `Aratako/Irodori-TTS-500M-v3`: https://huggingface.co/Aratako/Irodori-TTS-500M-v3
- `Aratako/Irodori-TTS`: https://github.com/Aratako/Irodori-TTS
- `Aratako/Irodori-TTS-Server`: https://github.com/Aratako/Irodori-TTS-Server

現時点の最新版として扱う対象:

- base model は `Aratako/Irodori-TTS-500M-v3`
- GitHub `main` は v3 codebase を追跡し、v2 checkpoint への後方互換も維持する
- VoiceDesign は v3 release がまだなく、`Aratako/Irodori-TTS-500M-v2-VoiceDesign` が現行候補
- OpenAI Text-to-Speech API 互換の `Irodori-TTS-Server` が公開されており、SeedShiftRadio からはこの HTTP server を第一統合点にする

ラジオパーソナリティ用途で有望な点:

- 日本語 TTS に特化しており、TALK / LETTER の自然な発話に向く
- 参照音声による zero-shot voice cloning があり、局ごとの声質を固定しやすい
- emoji-based style control により、明るい、落ち着いた、笑いなどを台本側で軽く誘導できる
- v3 base では自動 duration prediction が入り、従来より `seconds` の手動指定に依存しにくい
- Irodori-TTS-Server は `POST /v1/audio/speech` を提供し、OpenAI 互換 TTS adapter として接続しやすい
- 長文 chunking と `wav`, `mp3`, `flac`, `opus`, `aac`, `pcm` の response format を扱える

制約と注意点:

- streaming synthesis は未実装で、1 request は完成音声を返す方式である。ライブ発話の逐次 streaming ではなく、先行生成と cache 前提で使う
- 既定では同時 synthesis 1 件の queue 運用であり、モデル読み込み中や slot 待ち timeout では HTTP 503 になりうる
- NVIDIA GPU が実用上推奨される。CPU でも動く可能性はあるが、ラジオ再生の安定運用では `ttsAheadCount` と cache hit を厚めにする
- 漢字読み精度は同規模 TTS と比べて弱い旨が model card に明記されているため、複雑な漢字や固有名詞は読み辞書・かな化で補正する
- 声クローンは本人や権利者の明示同意がある参照音声だけを使う。声優、著名人、実在個人の無断模倣、誤認を招く deepfake 的利用は禁止事項として扱う
- model は MIT license だが、倫理的制約、参照音声、生成物の利用条件は別途 OSS/モデル台帳で管理する

採用方針:

- `IRODORI_TTS` は server-side TTS の高品質 provider として採用候補に昇格し、現行 server adapter は Irodori-TTS-Server の OpenAI互換 `/v1/audio/speech` を呼び出す
- `VOICEVOX` は軽量・安定 fallback として残し、現行 server adapter は `/audio_query` から `/synthesis` の順に WAV を生成する
- 初期 adapter は `Irodori-TTS-Server` の OpenAI互換 API に限定し、Java Server から Irodori の Python CLI を直接実行しない
- `voice_profile.speakerKey` は Irodori server の voice id、`styleKey` は station 側 style preset、`speed` は OpenAI互換 API の `speed` に対応させる
- 局ごとに異なる Irodori voice id / reference voice / style preset を割り当て、深夜局は落ち着いた声、朝局は明るい声、ニュース寄り局は抑制した声、のように分離してよい
- `pitch` / `volume` は Irodori-TTS-Server の互換 API では直接効かない可能性があるため、初期は保持のみとし、必要時に post-process または engine option へ拡張する
- `referenceVoiceRef` は `dataRoot` 配下の承認済み参照音声、または Irodori server 側 `voices.json` の id を指す。標準ログ、SSE、API response へ参照音声の実パスや個人名を出さない
- emoji style は LLM が自由に出すのではなく、`PersonaStyleResolver` が `emotion` / `tempo` / `styleKey` から許可済み emoji preset へ変換する

### 7.2 Irodori 用の発話整形ルール

- `normalizedText` は Irodori へ渡す最終文字列とし、漢字読みが不安定な語は `pronunciationHints` に基づきかな・カナへ置換する
- style emoji は文頭または文節単位に限定し、レター本文由来の絵文字をそのまま style control として扱わない
- 一文は 60 文字程度を上限に保ち、長文は句点・読点・話題境界で分割する
- `LETTER` 由来の本文は引用ではなく要約を読み上げ、攻撃的表現・個人情報・URL は通常 TTS と同じ正規化ルールで伏せる
- 参照音声や voice id が失効、未承認、ライセンス不明の場合は Irodori を使わず fallback VoiceProfile へ切り替える

## 8. 品質ルール

- 一文は 60 文字程度を上限目安とし、長い場合は分割する
- 英数字混在語は読み辞書または正規化ルールで補正する
- URL は全文読み上げしない
- 放送に不向きな攻撃表現は言い換える
- punctuation を過剰に使わない

## 9. 生成物の扱い

- `normalizedText` は script asset metadata に保持して再現可能にする
- TTS audio asset metadata は本文を保持せず、`normalizedTextHash`, `providerKey`, `adapter`, `voiceHint`, `speakerKey` / `voiceId`, hint count のような短い値だけにする
- Voice Persona 変更時は TTS キャッシュを無効化する
- `pronunciationHints`, `pauseHints`, `voiceHint` も replay 対応のため script asset metadata に保持し、audio asset metadata では count と `voiceHint` に抑える
- `LETTER` 由来の音声は既定で archive replay 候補にしない
- `TALK` の replay 候補化は station `replayPolicy` と `safetyFlags` の両方を満たした場合のみ許可する

## 10. 将来拡張

- 掛け合い対応のため `speakerRole` を追加する
- Native Client 側で VOICEROID 系へ `clientAdapterKey` を使ってマッピングする
- station ごとに読み辞書の UI 編集機能を追加する
