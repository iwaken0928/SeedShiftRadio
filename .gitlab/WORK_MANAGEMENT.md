# GitLab 作業管理

## 正本と対象

日々の作業状態は GitLab Project Issue / Label / Issue Board を正本とする。
製品仕様と実装順は引き続き `docs/`、特に `docs/15_設計差分棚卸しと段階実装計画.md` を正本とする。
Issue は設計書を複製する場所ではなく、作業範囲、完了条件、検証結果、残課題を追跡する単位として扱う。

GitLab CE でも運用できるよう、Epic、Roadmap、custom status には依存しない。

## 初回接続

1. `.gitlab/gitlab.env.example` を `.gitlab/gitlab.env` へコピーする。
2. `GITLAB_HOST` と `GITLAB_PROJECT_ID` を確認し、`GITLAB_TOKEN` を設定する。
3. token には Project Issue、Label、Board を操作できる最小権限を付与する。
4. `.gitlab/gitlab.env` が `git status` に表示されないことを確認する。

token は commit、ログ、Issue、コメントへ記載しない。

## Label

Label は scoped label として次の最小構成を使う。

| 系統 | Label | 用途 |
|---|---|---|
| kind | `kind::feature` | 新機能・機能完成 |
| kind | `kind::bug` | 不具合修正 |
| kind | `kind::docs` | 設計書・運用文書 |
| kind | `kind::maintenance` | 保守・依存更新・整理 |
| area | `area::server` | Spring Boot server |
| area | `area::web` | Next.js web client |
| area | `area::worker` | MusicGen worker / AI worker |
| area | `area::ops` | CI/CD、container、compose、運用 |
| area | `area::architecture` | API 契約・横断設計 |
| priority | `priority::P0` | MVP 成立を妨げる最優先課題 |
| priority | `priority::P1` | 品質・運用完成度に必要な課題 |
| priority | `priority::P2` | 改善・追随課題 |
| status | `status::未着手` | 未整理または依存未確認 |
| status | `status::着手可能` | 完了条件と依存が明確 |
| status | `status::作業中` | 実装・検証中 |
| status | `status::確認待ち` | 実装完了、人間の確認待ち |
| status | `status::保留` | 外部要因または判断待ち |
| agent | `agent::codex` | Codex が主に実装する作業 |
| agent | `agent::human` | 人間の判断・実環境操作が主となる作業 |

完了した Issue は `status::完了` Label で列に残さず close する。
一つの Issue に付与する `status::*` は常に一つだけにする。

## Issue Board

Project Issue Board は次の Label list を左から順に作る。

1. `status::着手可能`
2. `status::作業中`
3. `status::確認待ち`
4. `status::保留`

Open 列は triage、Closed 列は完了履歴として使う。
`status::未着手` は backlog の検索用とし、Board の常設列にはしない。

## Issue 化の単位

`docs/15` の未実装 ID を原則一つの Issue にする。
同じ migration や API contract を不可分に変更する場合だけ、複数 ID を一つへまとめる。
Issue title は `[P0-01] LLM adapter を追加する` のように設計書 ID を先頭へ置く。

現時点で Issue 化候補となる残タスクは、少なくとも次のとおり。

- `P0-01`, `P0-02b`, `P0-02c`, `P0-03`, `P0-04`, `P0-05`, `P0-05a`
- `P0-06`, `P0-09`
- `P2-06`

`部分実装` の項目は、Issue 本文へ現状と残りを分けて記載する。
Issue 作成前に GitLab の open Issue を検索し、重複を作らない。

## 標準フロー

1. Issue 本文、依存 Issue、関係する設計書を確認する。
2. 着手可能なら `status::作業中` へ変更し、現在ブランチと作業範囲をコメントする。
3. commit message に `refs #<issue_no>` を含める。
4. focused test と必要な回帰確認を実行する。
5. 完了コメントへ変更概要、テスト、commit、未対応事項を書く。
6. `status::確認待ち` へ変更する。
7. 人間の確認後に Issue を close する。

実装中に見つかった範囲外の課題は、その場で無関係な修正へ広げず、既存 Issue を確認してから追加 Issue として追跡する。
