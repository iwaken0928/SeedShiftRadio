---
name: seedshift-radio-architecture
description: Use when working on SeedShiftRadio architecture, API contracts, module boundaries, playout behavior, data layout, provider integration, or implementation planning across the Spring Boot server, Next.js web UI, Python MusicGen worker, and the design documents under /doc. This skill helps keep changes aligned with the API-first local AI radio architecture and preserve compatibility with the future C# native client.
---

# SeedShiftRadio Architecture

## Use This Skill When

- 変更が `API`, `DTO`, `SSE`, `Queue`, `Playout`, `Provider`, `DB`, `config.json`, `ディレクトリ構成` に触れる
- 作業が `server`, `web`, `workers/musicgen`, `doc` のどこへ属するか判断したい
- 機能追加時に、どの設計書を更新すべきか整理したい
- 将来の `C# Native Client` を壊さないか確認したい

## 最初に見るドキュメント

- 実装順序と全体入口: `doc/00_実装ドキュメント一覧.md`
- 責務境界: `doc/01_アーキテクチャ方針設計書.md`
- API と共有契約: `doc/02_API仕様書.md`
- UI と再生挙動: `doc/03_Web画面設計書.md`
- データとファイル配置: `doc/04_データ構造設計書.md`
- プレイアウトと fallback: `doc/05_プレイアウト・キュー制御設計書.md`
- Provider 連携: `doc/07_Provider連携設計書.md`
- MusicGen worker: `doc/09_MusicGen連携設計書.md`
- C# Native Client 互換: `doc/10_CSharpネイティブクライアント連携設計書.md`
- 運用とテスト: `doc/11_運用・監視・セキュリティ・テスト設計書.md`

## ワークフロー

1. 要求が触る境界を `server`, `web`, `worker`, `doc` に分解する
2. 共有契約が絡むなら `doc/02_API仕様書.md` を必須参照にする
3. 再生状態とキュー正本は `Server` に残し、`Web` は表示と操作に限定する
4. MusicGen は高遅延な別ワーカー前提を崩さない
5. 挙動変更がある場合は、対応する `doc/*.md` を同じ変更で更新する

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
- 仕様更新と判断根拠は `doc`

## 完了前チェック

- 変更先の境界は妥当か
- 共有契約を壊していないか
- 影響する `doc/*.md` を更新したか
- fallback と監視への影響を見たか
