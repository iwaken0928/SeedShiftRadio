---
name: seedshift-radio-web-ui
description: Use when working on SeedShiftRadio Next.js routes, radio player UX, SSE subscription and reconnect behavior, TanStack Query and Zustand state boundaries, or the `/`, `/letters`, `/settings`, and `/monitor` screens. This skill helps keep the web client aligned with `docs/03`, `docs/02`, `docs/05`, and `docs/12`.
---

# SeedShiftRadio Web UI

## 主に使う役割

- `web`
- `architect`
- `qa`

## 最初に見るドキュメント

- 画面構成と UI 状態: `docs/03_Web画面設計書.md`
- 参照する API と SSE: `docs/02_API仕様書.md`
- 再生と queue 契約: `docs/05_プレイアウト・キュー制御設計書.md`
- レター導線: `docs/12_レター機能設計書.md`

## 実装時の前提

- 将来の UI 実装は `/web` 配下の `Next.js App Router + TypeScript` を前提にする
- 現在の Spring Boot リポジトリへ UI を足す場合も、client 専用状態や再生ロジックを server 側へ混ぜ込まない

## ワークフロー

1. Server State と UI State を分離し、前者は `TanStack Query`, 後者は `Zustand` に寄せる
2. `/`, `/letters`, `/settings`, `/monitor` のどの画面に影響するかを先に固定する
3. 初期表示は REST、以後は SSE 差分更新、切断時は `Last-Event-ID` と指数バックオフで再同期する
4. 音声再生は `HTMLAudioElement` を基本にし、Queue 先頭 `READY` の順次再生、3 秒前 preload、再試行 1 回の挙動を守る
5. 管理 UI へ出す内容と公開 UI へ出す内容を分け、管理トークン前提の導線を混在させない

## ガードレール

- `radio.status.changed`, `queue.updated`, `program.changed`, `subtitle.updated`, `provider.health.changed`, `letter.updated` を UI 責務に応じて受け分ける
- `PREPARING`, `PLAYING`, `DEGRADED`, `ERROR` の表示差を潰さない
- `radioName` の保持、投稿二重送信防止、`aria-live` などの UX とアクセシビリティを守る
- `/settings` では機密値そのものを表示せず、危険設定には注意表示を付ける
- 実行中 block に影響する設定変更は「次の番組から反映」と明示する

## 完了前チェック

- `docs/03_Web画面設計書.md` を必要に応じて更新したか
- 参照する API/SSE 契約が `docs/02_API仕様書.md` と一致しているか
- `Vitest` と `Playwright` で happy path と再接続導線を押さえたか
