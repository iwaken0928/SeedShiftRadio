---
name: seedshift-radio-backlog-implementation
description: Use when working on SeedShiftRadio backlog-driven implementation from the design documents, especially when the request is to identify remaining unimplemented work from `doc/15_設計差分棚卸しと段階実装計画.md`, implement one or two items without broad re-survey, update the relevant design docs, and verify the change with focused tests.
---

# SeedShiftRadio Backlog Implementation

## 使う場面

- 「未実装機能を特定して実装して」
- 「設計書と実装管理資料から次にやる項目を選んで」
- 「広い再調査は避けて、関連設計だけ見て進めて」
- 「GitLab Issue を起点に残タスクを実装して」

## 最初に見るドキュメント

- 実装管理の起点: `doc/15_設計差分棚卸しと段階実装計画.md`
- 全体入口: `doc/00_実装ドキュメント一覧.md`
- 変更対象に応じた設計書:
  - API/DTO/SSE: `doc/02_API仕様書.md`
  - Web UI: `doc/03_Web画面設計書.md`
  - Data/DB/cache: `doc/04_データ構造設計書.md`
  - Playout/queue: `doc/05_プレイアウト・キュー制御設計書.md`
  - Provider/runtime: `doc/07_Provider連携設計書.md`
  - Security/test: `doc/11_運用・監視・セキュリティ・テスト設計書.md`
  - Letter: `doc/12_レター機能設計書.md`
  - Station/programming: `doc/13_局管理・番組編成制御設計書.md`

## ワークフロー

1. GitLab の対象 Issue を確認する。Issue が未指定なら `doc/15` から残タスクを 1 つか 2 つ選び、open Issue との重複を確認する
2. 選んだ項目に必要な設計書だけ読む。無関係な章まで広げない
3. 変更境界を `server`, `web`, `worker`, `doc` に分け、主担当を決める
4. まずコードを通し、次に設計書差分を同じ変更で閉じる
5. 検証は変更規模に合わせて `typecheck`, unit/service test, Playwright, `git diff --check` を選ぶ
6. 完了時は「何を選んだか」「何を直したか」「何を確認したか」「残りは何か」を短くまとめる

## GitLab 連携

- 作業状態、担当主体、優先度は `.gitlab/WORK_MANAGEMENT.md` に従う
- 着手時は対象 Issue を `status::作業中`、完了時は検証結果を記録して `status::確認待ち` にする
- commit message には対象 Issue の `refs #<issue_no>` を含める
- 範囲外の課題は変更へ混ぜず、既存 Issue を確認してから追加 Issue で追跡する
- サブエージェントはユーザーまたは Issue が明示し、重ならないファイル集合へ分割できる場合だけ使う

## ガードレール

- `doc/15` を起点にするが、正本は各設計書なので差分を放置しない
- 1 回で広く触りすぎず、着手項目を絞る
- 未保存 draft preview、station/template create/duplicate、secret redaction など既存 `/settings` 前提を壊さない
- 実行中 block 影響は「次の番組から反映」を維持する
- 秘密値、prompt、letter body、radioName をそのまま表示・ログ出力しない
- 生成物や一時差分 (`web/tsconfig.tsbuildinfo` など) は必要なら戻す

## 完了前チェック

- `doc/15` の状態・残タスク記述を更新したか
- 変更箇所に対応する `doc/02` から `doc/13` の更新要否を見たか
- テスト実施結果と未実施理由を説明できるか
- `git diff --check` が通るか

## 依頼テンプレート

依頼文の雛形は [references/request-templates.md](references/request-templates.md) を使う。
