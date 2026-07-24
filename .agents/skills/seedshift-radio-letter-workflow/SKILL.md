---
name: seedshift-radio-letter-workflow
description: Use when working on SeedShiftRadio letter submission, moderation, adoption and reply transitions, idempotency, reply threads, broadcast linkage, or letter-related UI and API behavior. This skill helps keep `letter` workflows aligned with `docs/02`, `docs/12`, `docs/06`, and `docs/08`.
---

# SeedShiftRadio Letter Workflow

## 主に使う役割

- `server`
- `web`
- `qa`
- `architect`

## 最初に見るドキュメント

- レター API 契約: `docs/02_API仕様書.md`
- レター状態遷移: `docs/12_レター機能設計書.md`
- LLM 安全設計: `docs/06_LLM台本生成設計書.md`
- 日本語品質と安全性: `docs/08_日本語パーソナリティ・TTS・読み辞書設計書.md`

## 現物確認の起点

- レター API と service: `src/main/java/com/seedshiftradio/letter`
- 採用先 session 参照: `src/main/java/com/seedshiftradio/radio`
- 回帰確認: `src/test/java/com/seedshiftradio/letter`

## ワークフロー

1. 公開投稿導線と管理導線を分けて考える
2. `POST /api/letters` の公開性、`GET /api/letters` と状態更新系の管理トークン前提を確認する
3. `UNREAD -> PENDING -> ADOPTED -> REPLIED` の遷移と、`ADOPTED` 時の `sessionId` 必須を守る
4. 投稿 idempotency と二重採用防止を先に確認する
5. 放送採用や返信で本文を再利用する場合も、レター本文を命令ではなくユーザー入力として扱う

## ガードレール

- `letter` を正本、`letter_reply` を返信履歴として扱う
- `adoptedInSessionId` を欠落させたまま `ADOPTED` へ進めない
- `radioName`, `subject`, `body` を標準ログへそのまま出し過ぎない
- 管理画面や API で返信や採用履歴を見せても、生ログ全文返却にはしない
- 放送用テキスト生成は `seedshift-radio-broadcast-quality` と併用し、日本語品質と安全性を別途確認する

## 完了前チェック

- `docs/02_API仕様書.md` と `docs/12_レター機能設計書.md` を必要に応じて更新したか
- 投稿 idempotency、状態遷移、返信追加、採用 session 紐付けの test を押さえたか
- UI を触る場合は `/letters` の二重送信防止と履歴表示を確認したか
