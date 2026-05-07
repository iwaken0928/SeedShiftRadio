---
name: seedshift-radio-provider-runtime
description: Use when working on SeedShiftRadio provider abstraction, settings APIs, connection tests, health checks, generated assets, JobRunr jobs, or MusicGen worker integration. This skill helps keep LLM, TTS, and MusicGen runtime behavior aligned with `doc/04`, `doc/07`, `doc/09`, and `doc/11`.
---

# SeedShiftRadio Provider Runtime

## 主に使う役割

- `worker`
- `server`
- `architect`
- `qa`

## 最初に見るドキュメント

- 設定と asset メタ: `doc/04_データ構造設計書.md`
- Provider 抽象と health: `doc/07_Provider連携設計書.md`
- MusicGen worker 契約: `doc/09_MusicGen連携設計書.md`
- 監視とテスト: `doc/11_運用・監視・セキュリティ・テスト設計書.md`

## 現物確認の起点

- 設定と疎通確認: `src/main/java/com/seedshiftradio/settings`
- 監視 API: `src/main/java/com/seedshiftradio/monitor`
- playout からの利用面: `src/main/java/com/seedshiftradio/radio`
- 回帰確認: `src/test/java/com/seedshiftradio/settings`, `src/test/java/com/seedshiftradio/monitor`

## ワークフロー

1. `settings`, `ProviderRegistry`, `ProviderHealth`, `generated_asset`, `provider_job`, worker API のどこを触るかを先に分解する
2. Provider 固有差分は抽象層の下へ押し込み、上位 service が固有 API を直接知らない形を保つ
3. `/api/settings`, `/api/settings/test-connections`, `/api/health`, `/api/monitor/summary`, SSE `provider.health.changed` の payload をそろえる
4. 音声や音楽の生成物は Server 管理の asset として扱い、ファイル正本と DB メタの分担を崩さない
5. 高遅延な MusicGen は `GenerateMusicJob` と worker API に閉じ込め、再生経路から切り離す

## ガードレール

- Java 本体から provider 固有 CLI を直接実行しない
- `UP / DEGRADED / DOWN` と `lastCheckedAt`, `responseTimeMs`, `message`, `capabilities` の形を維持する
- 機密値は `env:` または `file:` 参照で扱い、平文露出を避ける
- Provider 障害時は停止ではなく placeholder, fallback asset, 代替 Provider で継続を優先する
- `provider.health.changed` と監視 API で異なる意味の status を返さない

## 完了前チェック

- `doc/04_データ構造設計書.md`, `doc/07_Provider連携設計書.md`, `doc/09_MusicGen連携設計書.md`, `doc/11_運用・監視・セキュリティ・テスト設計書.md` を必要に応じて更新したか
- settings 楽観ロック、疎通確認、health 状態分類、asset 配信、fallback の test を押さえたか
- ライセンスや運用台帳へ追記が必要か確認したか
