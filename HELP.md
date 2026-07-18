# SeedShiftRadio ローカル起動ガイド

このリポジトリでは、現時点の repo root を `server` として扱い、`web` と `workers/musicgen` を分離運用します。今回のローカル起動導線は、`infra/compose` で依存サービスを立ち上げ、Server と Web はローカルプロセスとして起動する前提です。

## 1. 前提

- JDK 21 以上。ローカルでは JDK 25 などの新しい JDK も利用でき、コンパイル対象は Java 21 (`--release 21`) に固定する
- Node.js 22 系と npm
- Python 3.12
- Podman と Podman Compose

## 2. 初回準備

```bash
cp .env.example .env
mkdir -p data/config data/library/music
cp infra/compose/local-config.example.json data/config/config.json
```

`data/config/config.json` は、ローカル検証用に `MusicGen worker` を既定 provider とする最小設定テンプレートです。実運用向けの Provider や secret 参照は `/settings` から後で調整できます。

## 3. 依存サービス起動

まず PostgreSQL と MusicGen worker を起動します。

```bash
podman compose --env-file .env -f infra/compose/compose.yml up -d postgres musicgen
```

Ollama と VOICEVOX もローカルで併せて立ち上げる場合は `ai` profile を追加します。

```bash
podman compose --env-file .env -f infra/compose/compose.yml --profile ai up -d ollama voicevox
```

## 4. Server 起動

別ターミナルで環境変数を読み込み、Spring Boot を起動します。

```bash
set -a
source ./.env
set +a
./gradlew bootRun
```

起動後、`http://127.0.0.1:8080/api/health` が応答すれば Server は準備完了です。

## 5. Web 起動

さらに別ターミナルで Web を起動します。

```bash
cd web
set -a
source ../.env
set +a
npm run dev
```

ブラウザで次を開きます。

- `http://127.0.0.1:3000/`
- `http://127.0.0.1:3000/letters`
- `http://127.0.0.1:3000/settings`
- `http://127.0.0.1:3000/monitor`

`/letters` の管理 inbox、`/settings`、`/monitor` の現行開発導線は `NEXT_PUBLIC_SEEDSHIFT_ADMIN_TOKEN` が読み込まれている前提です。
この値は browser bundle へ入るため、公開環境では使用しません。
production の管理 UI 認証は GitLab Issue `P0-11` で server-side session 方式へ移行するまで未完成です。

## 6. 起動確認

主要 HTTP endpoint とページ表示は smoke script で確認できます。

```bash
./scripts/smoke-local-stack.sh
```

実行内容:

- `worker /health`
- `server /api/health`
- `server /api/radio/status`
- `server /api/radio/queue`
- `web /`, `/letters`, `/settings`, `/monitor`
- admin token がある場合は `server /api/settings`

## 7. 停止

Server / Web は起動したターミナルで停止し、依存サービスは compose で止めます。

```bash
podman compose --env-file .env -f infra/compose/compose.yml stop
```
