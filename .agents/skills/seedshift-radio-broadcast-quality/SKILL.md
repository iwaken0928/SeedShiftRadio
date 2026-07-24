---
name: seedshift-radio-broadcast-quality
description: Use when working on SeedShiftRadio Japanese script generation, persona behavior, speech directives, TTS normalization, pronunciation hints, reading dictionaries, subtitles, letter safety, or degraded playback quality. This skill helps keep generated broadcast content natural in Japanese, safe to use with untrusted letter input, and consistent with the LLM, TTS, and operational quality documents under /docs.
---

# SeedShiftRadio Broadcast Quality

## Use This Skill When

- トーク台本、字幕、`SpeechDirective`、TTS 入力、読み辞書、`voiceHint` を扱う
- レター本文を放送用テキストや返信へ変換する
- 日本語の自然さ、句読点、読み補正、感情指定を見直す
- provider 障害時の fallback や無音回避を確認する

## 最初に見るドキュメント

- LLM 台本生成: `docs/06_LLM台本生成設計書.md`
- Provider 抽象: `docs/07_Provider連携設計書.md`
- 日本語品質, TTS, 読み辞書: `docs/08_日本語パーソナリティ・TTS・読み辞書設計書.md`
- 運用, セキュリティ, テスト: `docs/11_運用・監視・セキュリティ・テスト設計書.md`
- レター状態と放送反映: `docs/12_レター機能設計書.md`

## ワークフロー

1. 表示用テキストと TTS 用正規化テキストを分けて考える
2. レター本文は信頼せず、命令ではなくユーザー入力として扱う
3. 一文を長くし過ぎず、聞き取りやすい自然な日本語へ整える
4. 英数字混在語や固有名詞は読み辞書や pronunciation hint で補正する
5. provider 失敗時も無音停止を避け、代替セグメントや fallback 音源で継続する

## 品質ガードレール

- `normalizedText` は再現可能な形で保持する
- `pronunciationHints`, `pauseHints`, `personaRef`, `voiceHint` を契約と整合させる
- URL や危険な表現をそのまま読み上げない
- 不適切語や攻撃的表現は必要に応じて言い換える
- persona 変更時は TTS キャッシュ無効化の影響を考慮する
- API key, 管理トークン, prompt 本文, letter body 全文を標準ログへ出さない

## 日本語品質の目安

- 一文はおおむね 60 文字程度を上限目安にする
- punctuation を過剰に使わない
- 混在語は読みやすさを優先して補正する
- 感情やテンポは persona と番組文脈に合わせて控えめに指定する

## 完了前チェック

- 聞き上げた時に不自然な箇所がないか
- レター入力をそのまま命令として扱っていないか
- `SpeechDirective` の項目が契約どおりか
- fallback 時にも放送継続できるか
