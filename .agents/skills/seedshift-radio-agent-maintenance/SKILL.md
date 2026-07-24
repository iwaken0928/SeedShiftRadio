---
name: seedshift-radio-agent-maintenance
description: Use when updating SeedShiftRadio Codex configuration, `.codex/config.toml`, `AGENTS.md`, GitLab-first agent operations, `.agents/skills/**`, local project skill metadata, or deciding whether delegation or a repository skill is needed. This skill helps keep Codex operations Japanese-first, API-first, minimally duplicated, and free of pinned `model_reasoning_effort`.
---

# SeedShiftRadio Agent Maintenance

## 最初に見るファイル

- Repository operating rules: `AGENTS.md`
- Codex project config: `.codex/config.toml`
- Work management: `.gitlab/WORK_MANAGEMENT.md`, `.gitlab/issue_templates/*`
- Environment setup: `.codex/environments/environment.toml`
- Existing local skills: `.agents/skills/*/SKILL.md`
- Skill UI metadata: `.agents/skills/*/agents/openai.yaml`

## ワークフロー

1. `AGENTS.md`, `.codex/config.toml`, `.gitlab/WORK_MANAGEMENT.md`, `.agents/skills/*/SKILL.md` を棚卸しする
2. 既存 skill で扱える作業か、繰り返し発生する未カバー領域かを判断する
3. 既存 skill に足せる場合は新規 skill を作らず、重複する説明を避けて最小更新にする
4. 新規 skill が必要な場合は `skill-creator` の `init_skill.py` で雛形を作り、`SKILL.md` と `agents/openai.yaml` を整える
5. サブエージェントは常設 role を増やす前に、ユーザーまたは Issue による明示的な委任と、重ならないファイル境界があるか確認する
6. 設計正本や責務境界に影響する説明は、必要に応じて関連 `docs/*.md` も更新する
7. 最後に TOML / YAML / skill validation と未処理マーカーの残りを確認する

## 判断基準

- 作業状態と担当主体は GitLab Issue / Label / Board を正本とし、Codex 内に重複する管理体系を作らない
- 固定 agent を追加せず、専門手順は既存 project skill の更新を優先する
- skill を追加するのは、既存 skill の description では自然に trigger しない再利用手順があるときだけにする
- `model_reasoning_effort` は `.codex/config.toml` に原則固定しない
- 返答、説明、補助ドキュメント、コミット要約は日本語を基本にする
- API First、Server 正本、Web 表示層、Worker 分離、secret 非露出の原則を config にも反映する
- ファイル所有が重なる変更を複数 agent に同時編集させない

## サブエージェントを使う条件

- ユーザーまたは Issue が委任を明示している
- 作業を重ならないファイル集合へ分割できる
- 統合、最終検証、commit、GitLab Issue 更新をメインエージェントが担当する
- 単一領域の通常作業や、project skill だけで十分な作業には使わない

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

- サブエージェント利用時にファイル所有が重複していないか
- skill が既存 skill と重複していないか
- `.codex/config.toml`, `AGENTS.md`, `.gitlab/WORK_MANAGEMENT.md`, `agents/openai.yaml` の説明が一致しているか
- `model_reasoning_effort` を固定していないか
- 日本語運用、API First、secret 非露出の方針を弱めていないか
