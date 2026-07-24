# SeedShiftRadio

SeedShiftRadio は、ローカル AI を使ってラジオ台本、音声、音楽を生成し、局・番組編成・レター・再生キューを一体的に扱う AI ラジオシステムです。
Spring Boot Server を再生状態と永続化の正本とし、Next.js Web Client と将来の C# Native Client が同じ REST / SSE 契約を利用できる API First 構成を採用しています。

## 主な構成

- Server: Java 21、Spring Boot、Spring MVC、PostgreSQL、Flyway、JobRunr
- Web: Next.js App Router、React、TypeScript、TanStack Query、Zustand
- AI Provider: Ollama、VOICEVOX、Irodori-TTS-Server、ACE-Step
- Music worker: FastAPI ベースの互換 MusicGen worker
- Infra: Podman、Podman Compose、GitLab CI/CD

```text
.
├── src/                    # Spring Boot Server
├── web/                    # Next.js Web Client
├── workers/musicgen/       # 非同期 Music Generation worker
├── infra/                  # Compose とコンテナ定義
├── scripts/                # smoke test と CI 補助
└── docs/                   # 製品仕様と実装順の正本
```

## 必要な環境

- JDK 21 以上
- Node.js 22 系と npm
- Python 3.12
- Podman と Podman Compose

Ollama、VOICEVOX、Music Generation Provider の導入条件と接続設定は、Notion の [AI Provider 接続環境・セットアップガイド](https://app.notion.com/p/3a6dcb57dc5f8180a7d3fb54a3fde5f6) を参照してください。

## ローカル起動

PowerShell では、最初にローカル設定を用意します。

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force data/config, data/library/music
Copy-Item infra/compose/local-config.example.json data/config/config.json
```

PostgreSQL と互換 MusicGen worker を起動します。

```powershell
podman compose --env-file .env -f infra/compose/compose.yml up -d postgres musicgen
```

ローカルの Ollama と VOICEVOX も起動する場合は、`ai` profile を使います。

```powershell
podman compose --env-file .env -f infra/compose/compose.yml --profile ai up -d ollama voicevox
podman compose --env-file .env -f infra/compose/compose.yml exec ollama ollama pull qwen3:8b
```

別のターミナルで Server と Web を起動します。

```powershell
.\gradlew.bat bootRun
```

```powershell
npm --prefix web ci
npm --prefix web run dev
```

起動後の主な URL は次のとおりです。

- Web: `http://127.0.0.1:3000/`
- Server health: `http://127.0.0.1:8080/api/health`
- OpenAPI UI: `http://127.0.0.1:8080/swagger-ui.html`

詳しい起動・停止手順は [HELP.md](HELP.md)、コンテナを含む運用手順は [docs/17_パイプライン・コンテナデプロイ手順書.md](docs/17_パイプライン・コンテナデプロイ手順書.md) を参照してください。

## テスト

```powershell
.\gradlew.bat test
.\gradlew.bat check
npm --prefix web test
python -m pytest workers/musicgen/tests
```

Podman / Testcontainers を使う Server の統合テストは次で実行します。

```powershell
.\gradlew.bat dockerTest
```

## ドキュメント

設計書は `docs/` を正本として扱います。

- [実装ドキュメント一覧](docs/00_実装ドキュメント一覧.md)
- [アーキテクチャ方針](docs/01_アーキテクチャ方針設計書.md)
- [API 仕様](docs/02_API仕様書.md)
- [Provider 連携](docs/07_Provider連携設計書.md)
- [運用・監視・セキュリティ・テスト](docs/11_運用・監視・セキュリティ・テスト設計書.md)
- [実装差分と段階実装計画](docs/15_設計差分棚卸しと段階実装計画.md)

API、DTO、SSE、設定、DB、再生制御、Provider 契約を変更する場合は、実装と同じ変更で対応する設計書も更新してください。

## セキュリティ

- Server の bind host は既定で `127.0.0.1` です。
- 管理 API は `X-Admin-Token` による保護を前提とします。
- API key、管理 token、DB password、prompt、レター本文、歌詞本文をリポジトリや通常ログへ記録しないでください。
- レター本文は信頼できない入力として扱い、LLM への命令として解釈させません。
