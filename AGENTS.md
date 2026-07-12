# AGENTS.md

## 目的

このリポジトリは `SeedShiftRadio` の実装・設計ベースであり、現時点では `doc/` 配下の設計書が最重要の正本です。実装や更新を行う時は、関係する設計書を先に確認し、コードと設計のずれを放置しないでください。

## 基本方針

- 返答、説明、コミット要約、補助ドキュメントは日本語を基本にする
- API First を維持し、`Web Client` と将来の `C# Native Client` が同じ契約を使えるようにする
- `Server` を再生状態、キュー状態、設定、永続化の正本にする
- `Web` は表示、操作、音声再生、SSE購読に集中させる
- 重い AI 推論は `Server` 本体へ閉じ込めず、外部 Provider または Worker として分離する
- MusicGen は非同期ワーカー前提とし、再生停止より縮退継続を優先する
- 管理系 API と秘密情報は最小権限で扱い、ログへ秘密値や本文を過剰に残さない

## 標準スタック

- Server: `Java 21`, `Spring Boot`, `Spring MVC`, `Spring Actuator`, `JobRunr`
- Web: `Next.js App Router`, `React`, `TypeScript`, `TanStack Query`, `Zustand`, `Tailwind CSS`
- Persistence: `PostgreSQL`, `Flyway`, file storage
- AI / Worker: `Ollama`, `VOICEVOX`, `FastAPI` ベースの MusicGen worker
- Infra / CI: `GitLab CI/CD`, `Podman`, `Podman Compose`
- Test: `JUnit 5`, `Testcontainers`, `Vitest`, `Playwright`

## 参照優先ドキュメント

- 全体像と実装順序: `doc/00_実装ドキュメント一覧.md`
- アーキテクチャと責務境界: `doc/01_アーキテクチャ方針設計書.md`
- API, DTO, SSE 契約: `doc/02_API仕様書.md`
- Web UI の画面・状態・再生挙動: `doc/03_Web画面設計書.md`
- 設定, DB, ファイル配置, キャッシュ: `doc/04_データ構造設計書.md`
- プレイアウトとキュー制御: `doc/05_プレイアウト・キュー制御設計書.md`
- LLM 台本生成: `doc/06_LLM台本生成設計書.md`
- Provider 抽象と接続: `doc/07_Provider連携設計書.md`
- 日本語パーソナリティ, TTS, 読み辞書: `doc/08_日本語パーソナリティ・TTS・読み辞書設計書.md`
- MusicGen worker 契約: `doc/09_MusicGen連携設計書.md`
- 将来の C# Native Client 契約: `doc/10_CSharpネイティブクライアント連携設計書.md`
- 運用, 監視, セキュリティ, テスト: `doc/11_運用・監視・セキュリティ・テスト設計書.md`
- レター機能の状態遷移: `doc/12_レター機能設計書.md`
- 局管理と番組編成制御: `doc/13_局管理・番組編成制御設計書.md`
- レビュー対応と再試験: `doc/14_レビュー対応・再試験計画.md`
- 実装差分と残タスク: `doc/15_設計差分棚卸しと段階実装計画.md`
- パイプラインとコンテナデプロイ仕様: `doc/16_パイプライン・コンテナデプロイ仕様書.md`
- パイプラインとコンテナデプロイ手順: `doc/17_パイプライン・コンテナデプロイ手順書.md`

## 変更時の更新ルール

- API, DTO, SSE の変更時は `doc/02_API仕様書.md` を更新する
- 画面遷移, UI状態, プレイヤー挙動の変更時は `doc/03_Web画面設計書.md` を更新する
- DB, `config.json`, asset path, cache policy の変更時は `doc/04_データ構造設計書.md` を更新する
- Tune, Queue, fallback, 先読み制御の変更時は `doc/05_プレイアウト・キュー制御設計書.md` と必要に応じて `doc/11_運用・監視・セキュリティ・テスト設計書.md` を更新する
- LLM, TTS, 読み辞書, persona, speech directive の変更時は `doc/06_LLM台本生成設計書.md`, `doc/07_Provider連携設計書.md`, `doc/08_日本語パーソナリティ・TTS・読み辞書設計書.md` を見直す
- MusicGen worker の API やジョブ制御を変える時は `doc/09_MusicGen連携設計書.md` を更新する
- レター状態や放送採用フローの変更時は `doc/12_レター機能設計書.md` を更新する
- 局管理、番組テンプレート、編成ルール、preview、queue 計画入力を変える時は `doc/13_局管理・番組編成制御設計書.md` を更新する
- レビュー指摘の対応順や再試験計画を変える時は `doc/14_レビュー対応・再試験計画.md` を更新する
- 残タスク、実装状況、設計差分を整理した時は `doc/15_設計差分棚卸しと段階実装計画.md` を更新する
- CI/CD, container, compose, deploy, rollback, smoke test を変える時は `doc/16_パイプライン・コンテナデプロイ仕様書.md` と `doc/17_パイプライン・コンテナデプロイ手順書.md` を更新し、必要に応じて `doc/11_運用・監視・セキュリティ・テスト設計書.md` も見直す
- 将来の `C# Native Client` と共有する契約を壊す変更は避け、必要時は `doc/10_CSharpネイティブクライアント連携設計書.md` を確認する

## 実装境界

- `server` は REST API, SSE, playout, queue, persistence, provider gateway, jobs を担当する
- `web` はラジオ UI, レター UI, 設定 UI, 監視 UI, audio playback を担当する
- `workers/musicgen` は高遅延な音楽生成を非同期ジョブとして担当する
- `infra/compose` はローカル起動と依存サービス定義を担当する
- `infra/containers`, `.gitlab-ci.yml`, `scripts/ci` は CI/CD とデプロイ導線を担当する
- `doc` は設計正本として扱う

推奨構成がまだ未作成でも、基本的には以下を維持します。

```text
/server
/web
/workers/musicgen
/infra/compose
/doc
```

## 守るべき実装ルール

- `Server` を radio status と queue の正本にする
- `Web` に業務判断や重い AI 推論を持ち込まない
- MusicGen は別プロセスまたは HTTP worker 前提を崩さない
- provider 障害時は無音停止ではなく代替セグメントや縮退運転を優先する
- `bind host` の既定は `127.0.0.1` を前提にする
- 管理系 API はトークン保護前提で設計する
- レター本文は信頼せず、prompt injection 前提で扱う
- `radioName`, letter body, prompt 本文, API key, 管理トークンを標準ログへそのまま出さない

## 作業管理とエージェント運用

作業状態、担当主体、優先度、完了確認は GitLab Project Issue / Label / Issue Board を正本とし、`.gitlab/WORK_MANAGEMENT.md` に従います。
`doc/` は製品仕様と実装順の正本であり、Issue は作業範囲、完了条件、検証結果、残課題を追跡する単位です。

Codex は原則としてメインエージェントが Issue の確認、実装統合、最終検証、GitLab の完了更新まで担当します。
専門領域の手順は固定サブエージェントではなく `.agents/skills` の project skill を使います。
サブエージェントは、ユーザーまたは Issue が委任を明示し、重ならないファイル集合へ安全に分割できる場合だけ使用します。
サブエージェントを使っても GitLab 上に別の担当・状態体系は作らず、統合、最終テスト、commit、Issue 更新はメインエージェントへ戻します。
範囲外の課題は変更へ混ぜず、既存 Issue を確認してから追加 Issue として追跡します。

`model_reasoning_effort` は `.codex/config.toml` で原則固定せず、Codex UI、グローバル設定、起動時の選択に任せます。タスクごとに必要なインテリジェンスを選べる状態を優先してください。

## ローカル skill

`.agents/skills` にプロジェクト専用 skill を置いています。

- `seedshift-radio-architecture`: 設計書の読み分け、責務境界、API first の確認用
- `seedshift-radio-agent-maintenance`: `.codex`, `.agents`, `AGENTS.md`, ローカル skill の保守用
- `seedshift-radio-backlog-implementation`: `doc/15` を起点にした残タスク実装と設計書更新用
- `seedshift-radio-broadcast-quality`: 日本語台本、TTS、読み辞書、レター安全性、縮退品質の確認用
- `seedshift-radio-letter-workflow`: レター投稿、採用、返信、放送反映の状態遷移確認用
- `seedshift-radio-ops-deploy`: GitLab CI/CD, Podman Compose, deploy, rollback, smoke test の運用変更用
- `seedshift-radio-playout-contracts`: 再生状態、キュー、SSE、Native Client 互換の契約確認用
- `seedshift-radio-programming-control`: 局管理、番組テンプレート、編成ルール、queue 計画の確認用
- `seedshift-radio-provider-runtime`: Provider 抽象、接続テスト、生成 asset、MusicGen worker 連携用
- `seedshift-radio-qa-guardrails`: テスト計画、回帰確認、監視、セキュリティ確認用
- `seedshift-radio-web-ui`: Next.js 画面、SSE 購読、プレイヤー UX、client state 確認用

タスクが合う場合は、これらの skill を前提知識として先に参照してください。
