---
name: seedshift-radio-ops-deploy
description: Use when working on SeedShiftRadio GitLab CI/CD, Podman or Podman Compose, production deploy and rollback, local compose startup, container images, smoke tests, CI variables, `.gitlab-ci.yml`, `infra/compose`, `infra/containers`, or `scripts/ci`. This skill keeps operations work aligned with `docs/11`, `docs/16`, `docs/17`, and the API-first local AI radio architecture.
---

# SeedShiftRadio Ops Deploy

## 最初に見るドキュメント

- Pipeline / deploy 仕様: `docs/16_パイプライン・コンテナデプロイ仕様書.md`
- 実作業手順: `docs/17_パイプライン・コンテナデプロイ手順書.md`
- 運用、監視、セキュリティ、テスト: `docs/11_運用・監視・セキュリティ・テスト設計書.md`
- DB、config、asset、volume: `docs/04_データ構造設計書.md`
- MusicGen worker 運用: `docs/09_MusicGen連携設計書.md`
- 残タスクと現在地: `docs/15_設計差分棚卸しと段階実装計画.md`
- ローカル起動メモ: `HELP.md`

## 現物確認の起点

- CI/CD: `.gitlab-ci.yml`
- Production compose: `infra/compose/compose.prod.yml`
- Local compose: `infra/compose/compose.yml`, `infra/compose/local-config.example.json`
- Container build: `infra/containers/server.Containerfile`, `infra/containers/web.Containerfile`, `workers/musicgen/Dockerfile.local`
- CI helpers: `scripts/ci/require-env.sh`, `scripts/ci/write-prod-env.sh`
- Smoke: `scripts/smoke-local-stack.sh`
- Env examples: `.env.example`, `infra/compose/prod.env.example`

## ワークフロー

1. 変更を `local compose`, `CI validation/test`, `container build/publish`, `deploy/migrate`, `smoke/rollback` に分類する
2. `docs/16` と `docs/17` で標準構成を確認してから、現物の pipeline / compose / script を読む
3. Production では PostgreSQL を compose 内に作らず、同一ホストの既存 PostgreSQL を正本にする
4. Server は既定で `127.0.0.1:8080` に bind し、Web は `0.0.0.0:3000` と `/api-proxy` 経由を維持する
5. Registry 未設定時の `localhost/seedshift-radio` fallback と、registry 利用時の push / pull 分岐を壊さない
6. 秘密値は GitLab CI/CD Variables または保護された環境変数だけから渡し、repo、標準ログ、docs、SSE、API response に実値を出さない
7. CI job 名、stage、runner tag、manual 条件を変える場合は `docs/16`, `docs/17`, 必要に応じて `docs/11` を同じ変更で更新する

## ガードレール

- `deploy-migrate` が失敗したら `deploy-app` へ進めない構造を保つ
- `deploy` 用 `.env` 生成は `umask 077` と secret redaction を前提にする
- DB migration 後の rollback は後方互換を前提にし、破壊的 rollback を pipeline だけで完結した扱いにしない
- `podman --remote` / `podman --remote compose` 前提を `docker compose` 前提へ置き換えない
- Testcontainers, Playwright, worker contract など重い suite は CI 分割と tag の意図を保つ
- `radioName`, letter body, prompt, API key, 管理トークン, DB password をログや調査メモに貼らない

## 検証の目安

- `.gitlab-ci.yml` を変えたら YAML parse と関連 job の条件を確認する
- compose を変えたら dummy env で `podman --remote compose ... config` 相当を確認する
- deploy script を変えたら secret 値が標準出力に出ないことを確認する
- Server / Web image を変えたら smoke target の `/api/health`, `/`, `/api-proxy/api/health` を意識する
- MusicGen deploy を変えたら `/data` volume の有無、host path 権限、`MUSICGEN_HOST_BIND` の公開範囲を確認する

## 完了前チェック

- `docs/16` / `docs/17` と現物の job 名、変数名、port、bind、volume が一致しているか
- Production compose に PostgreSQL を追加していないか
- Server の外部公開範囲を広げる変更になっていないか
- secret / prompt / letter body の非露出を維持しているか
- rollback と smoke の導線が残っているか
