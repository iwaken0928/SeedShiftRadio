---
name: seedshift-radio-programming-control
description: Use when working on SeedShiftRadio station administration, programming policies, program templates, program rules, preview resolution, queue planning inputs, or template validation. This skill helps keep programming control aligned with `docs/02`, `docs/04`, `docs/05`, and `docs/13`.
---

# SeedShiftRadio Programming Control

## 主に使う役割

- `planner`
- `architect`
- `server`
- `web`
- `qa`

## 最初に見るドキュメント

- 編成 API 契約: `docs/02_API仕様書.md`
- DB と version 管理: `docs/04_データ構造設計書.md`
- queue 接続と fallback: `docs/05_プレイアウト・キュー制御設計書.md`
- 局管理と番組編成: `docs/13_局管理・番組編成制御設計書.md`

## 現物確認の起点

- 編成解決と preview: `src/main/java/com/seedshiftradio/programming`
- station 管理: `src/main/java/com/seedshiftradio/station`
- playout 接続面: `src/main/java/com/seedshiftradio/radio`
- 回帰確認: `src/test/java/com/seedshiftradio/programming`

## ワークフロー

1. `Station`, `StationProgrammingPolicy`, `ProgramTemplate`, `ProgramRule`, `ProgramBlock` のどれを触るかを先に分解する
2. `PUT /stations/{id}/programming` と station 側の `programmingEnabled`, `defaultProgramTemplateId` の同期を確認する
3. `HARD` と `SOFT` の意味を保存時検証と実行時 fallback の両方で揃える
4. `Preview` は副作用なしで、未保存 payload の検証にも使える前提を崩さない
5. 実行中 `ProgramBlock` の template version は固定し、変更は次 block から反映する

## ガードレール

- `ProgramRule` 解決失敗時のみ `defaultTemplateId` や `legacyRatioFallback` に落とす
- `candidateSegmentTypes` と `fallbackSegmentTypes` は保存時に enum 検証する
- station 参照、personality、voice profile、template scope の整合を保存時に確認する
- `MusicGen` が `DOWN` のときは `MUSIC_AI` を避ける縮退を残す
- 管理 UI では `HARD` と `SOFT` の違い、実行中 block への反映タイミング、Preview 結果を明示する

## 完了前チェック

- `docs/02_API仕様書.md`, `docs/04_データ構造設計書.md`, `docs/05_プレイアウト・キュー制御設計書.md`, `docs/13_局管理・番組編成制御設計書.md` のどれを更新すべきか確認したか
- 保存時バリデーション、Preview、version 固定、fallback の test を押さえたか
- Queue 補充や ProgramBlock 解決への影響を見たか
