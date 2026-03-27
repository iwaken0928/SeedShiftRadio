---
name: seedshift-radio-qa-guardrails
description: Use when working on SeedShiftRadio test planning, regression review, CI split, observability checks, privacy and logging validation, or release-readiness verification across server, web, and worker changes. This skill helps keep verification aligned with `doc/11` and `doc/14`.
---

# SeedShiftRadio QA Guardrails

## 主に使う役割

- `qa`
- `planner`
- `server`
- `web`
- `worker`

## 最初に見るドキュメント

- 非機能, 監視, セキュリティ, テスト: `doc/11_運用・監視・セキュリティ・テスト設計書.md`
- 再試験順序と担当分担: `doc/14_レビュー対応・再試験計画.md`
- 変更箇所に対応する設計書: `doc/02` から `doc/13` の必要部分

## 現物確認の起点

- サーバー test: `src/test/java/com/seedshiftradio/**`
- migration と設定: `src/main/resources/db/migration`, `src/main/resources/application.properties`
- Web や worker が追加されたら、その test も同じ change set に含めて確認する

## ワークフロー

1. 変更を `Docker 不要 unit/service`, `MockMvc/API`, `Testcontainers`, `Web E2E`, `worker contract` に分解する
2. どの設計書のどの不変条件を test で押さえるかを先に決める
3. 回帰確認では正常系だけでなく、状態競合, fallback, degraded 継続, 認可失敗, 楽観ロックも確認する
4. 監視項目, SSE, ログ, 相関 ID, 秘密値露出の有無を変更とセットで確認する
5. 実行できなかった suite があれば理由と残リスクを明示する

## ガードレール

- MusicGen や TTS の障害で無音停止しないことを重視する
- `radioName`, `letter body`, `prompt`, API key, 管理トークンのログ露出を見逃さない
- `queue ready count`, provider health, program block version, recent errors などの監視導線を保つ
- Docker 依存 test だけで品質を担保したつもりにならず、ローカル単体 test を残す
- 設計書未更新のままコードだけ先行した change を放置しない

## 完了前チェック

- 変更領域ごとの test と手動確認観点を列挙したか
- `doc/11_運用・監視・セキュリティ・テスト設計書.md` や `doc/14_レビュー対応・再試験計画.md` の更新要否を確認したか
- 実行不可だった検証の前提条件と残リスクを明示したか
