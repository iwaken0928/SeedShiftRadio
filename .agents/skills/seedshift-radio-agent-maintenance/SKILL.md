---
name: seedshift-radio-agent-maintenance
description: Use when updating SeedShiftRadio Codex and agent configuration, `.codex/config.toml`, `.codex/agents/*.toml`, `AGENTS.md`, `.agents/skills/**`, local project skill metadata, or deciding whether to add or revise an agent role or repository skill. This skill helps keep Codex operations Japanese-first, API-first, minimally duplicated, and free of pinned `model_reasoning_effort`.
---

# SeedShiftRadio Agent Maintenance

## 最初に見るファイル

- Repository operating rules: `AGENTS.md`
- Codex project config: `.codex/config.toml`
- Agent configs: `.codex/agents/*.toml`
- Environment setup: `.codex/environments/environment.toml`
- Existing local skills: `.agents/skills/*/SKILL.md`
- Skill UI metadata: `.agents/skills/*/agents/openai.yaml`

## ワークフロー

1. `AGENTS.md`, `.codex/config.toml`, `.codex/agents/*.toml`, `.agents/skills/*/SKILL.md` を棚卸しする
2. 既存 agent / skill で扱える作業か、繰り返し発生する未カバー領域かを判断する
3. 既存 skill に足せる場合は新規 skill を作らず、重複する説明を避けて最小更新にする
4. 新規 skill が必要な場合は `skill-creator` の `init_skill.py` で雛形を作り、`SKILL.md` と `agents/openai.yaml` を整える
5. 新規 agent が必要な場合は `.codex/config.toml` の `[agents.<name>]`、`.codex/agents/<name>.toml`、`AGENTS.md` の役割一覧を同じ変更で更新する
6. 設計正本や責務境界に影響する説明は、必要に応じて関連 `doc/*.md` も更新する
7. 最後に TOML / YAML / skill validation と未処理マーカーの残りを確認する

## 判断基準

- agent を追加するのは、書き込み担当が曖昧なファイル群や繰り返し発生する横断作業があるときだけにする
- skill を追加するのは、既存 skill の description では自然に trigger しない再利用手順があるときだけにする
- `model_reasoning_effort` は `.codex/config.toml` や `.codex/agents/*.toml` に原則固定しない
- 返答、説明、補助ドキュメント、コミット要約は日本語を基本にする
- API First、Server 正本、Web 表示層、Worker 分離、secret 非露出の原則を config にも反映する
- ファイル所有が重なる変更を複数 agent に同時編集させる設定へ寄せない

## 役割境界の目安

- `planner`: 読み取り専用の影響範囲整理
- `architect`: API 契約、責務境界、設計 drift 確認
- `server`: `src/main/java`, `src/test/java`, 将来の `/server`
- `web`: `/web`
- `worker`: `/workers`, provider worker integration
- `ops`: `.gitlab-ci.yml`, `infra/compose`, `infra/containers`, `scripts/ci`, deploy docs, `.codex`, `.agents`
- `qa`: regression, CI test split, observability, release readiness

## Skill 作成・更新の目安

- Frontmatter は `name` と `description` だけにする
- description に「何をする skill か」と「いつ使うか」を入れる
- 本文は手順、参照ファイル、ガードレール、完了前チェックに絞る
- `agents/openai.yaml` の `default_prompt` は `$skill-name` を含める
- 追加資料が不要なら `references/`, `scripts/`, `assets/` を作らない
- 新規 skill を `AGENTS.md` のローカル skill 一覧へ追加する

## 検証の目安

- Skill は `skill-creator/scripts/quick_validate.py <skill-dir>` で検証する
- TOML は Python `tomllib` などで parse する
- YAML は利用可能なら `yaml.safe_load` で parse する
- `rg -n "FIXME|model_reasoning_effort|radioName|API key|admin token"` で未処理マーカー、固定設定、秘密値露出の兆候を確認する

## 完了前チェック

- 新旧 agent の所有範囲が重複しすぎていないか
- skill が既存 skill と重複していないか
- `.codex/config.toml`, `.codex/agents/*.toml`, `AGENTS.md`, `agents/openai.yaml` の説明が一致しているか
- `model_reasoning_effort` を固定していないか
- 日本語運用、API First、secret 非露出の方針を弱めていないか
