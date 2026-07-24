---
name: seedshift-radio-architecture
description: Use when working on SeedShiftRadio architecture, API contracts, module boundaries, playout behavior, data layout, provider integration, or implementation planning across the Spring Boot server, Next.js web UI, Python MusicGen worker, and the design documents under /docs. This skill helps keep changes aligned with the API-first local AI radio architecture and preserve compatibility with the future C# native client.
---

# SeedShiftRadio Architecture

## 最初に見るドキュメント

- 実装順序と全体入口: `docs/00_実装ドキュメント一覧.md`
- 責務境界: `docs/01_アーキテクチャ方針設計書.md`
- API と共有契約: `docs/02_API仕様書.md`
- UI と再生挙動: `docs/03_Web画面設計書.md`
- データとファイル配置: `docs/04_データ構造設計書.md`
- プレイアウトと fallback: `docs/05_プレイアウト・キュー制御設計書.md`
- Provider 連携: `docs/07_Provider連携設計書.md`
- MusicGen worker: `docs/09_MusicGen連携設計書.md`
- C# Native Client 互換: `docs/10_CSharpネイティブクライアント連携設計書.md`
- 運用とテスト: `docs/11_運用・監視・セキュリティ・テスト設計書.md`
- 局管理と番組編成: `docs/13_局管理・番組編成制御設計書.md`
- レビュー対応と再試験順序: `docs/14_レビュー対応・再試験計画.md`

## 現物確認の起点

- 現在の実装は想定上の `/server`, `/web`, `/workers/musicgen` 分離前で、主な現物は `src/main/java/com/seedshiftradio/**` と `src/test/java/com/seedshiftradio/**` にある
- Spring Boot サーバーの現物確認は `radio`, `letter`, `programming`, `settings`, `monitor`, `station`, `stream` を優先する
- 複数領域にまたがるレビュー対応では `docs/14_レビュー対応・再試験計画.md` を起点に phase と担当を揃える

## ワークフロー

1. 要求が触る境界を `server`, `web`, `worker`, `docs` に分解する
2. 共有契約が絡むなら `docs/02_API仕様書.md` を必須参照にする
3. 複数領域やレビュー指摘の対応では `docs/14_レビュー対応・再試験計画.md` を参照し、実装順と再試験を先に固定する
4. 再生状態とキュー正本は `Server` に残し、`Web` は表示と操作に限定する
5. MusicGen は高遅延な別ワーカー前提を崩さない
6. 挙動変更がある場合は、対応する `docs/*.md` を同じ変更で更新する

## ガードレール

- API First を維持し、`Web` と将来の `C# Native Client` の両方が使える契約にする
- `Server` を radio status, queue, settings, persistence の正本にする
- 重い AI 推論やジョブ制御を `Web` 層へ持ち込まない
- provider 障害時は停止より縮退継続を優先する
- `bind host` は既定で `127.0.0.1` を前提にする
- 管理系 API は保護前提で扱う

## 実装判断の目安

- 画面状態とプレイヤー挙動は `web`
- DTO, REST, SSE, queue, playout, persistence は `server`
- 音楽生成ジョブと外部推論ワーカーは `workers/musicgen`
- 現在の Spring Boot 実装では `src/main/java/com/seedshiftradio/**` が `server` 相当の現物になる
- 仕様更新と判断根拠は `docs`

## 完了前チェック

- 変更先の境界は妥当か
- 共有契約を壊していないか
- 影響する `docs/*.md` を更新したか
- fallback と監視への影響を見たか
