---
version: alpha
name: SeedShiftRadio Broadcast Console
description: 温かい深夜ラジオの空気と、運用コンソールの明快さを両立する Web デザインシステム
colors:
  canvas: "#F8F5EF"
  canvas-bottom: "#F2EFE8"
  text-strong: "#020617"
  text-primary: "#0F172A"
  text-secondary: "#475569"
  text-muted: "#5E6A79"
  surface: "#FFFFFF"
  surface-subtle: "#F8FAFC"
  surface-glass: "rgba(255, 255, 255, 0.88)"
  border: "#DCE2EC"
  border-strong: "#CBD5E1"
  brand: "#0B767B"
  brand-strong: "#0F766E"
  brand-bright: "#14B8A6"
  brand-soft: "#CCFBF1"
  warm: "#C26536"
  dark-surface: "#020617"
  on-dark: "#F8FAFC"
  status-neutral-bg: "#F1F5F9"
  status-neutral-text: "#334155"
  status-success-bg: "#D1FAE5"
  status-success-text: "#065F46"
  status-warning-bg: "#FEF3C7"
  status-warning-text: "#92400E"
  status-danger-bg: "#FFE4E6"
  status-danger-text: "#9F1239"
  status-danger-solid: "#E11D48"
typography:
  body:
    fontFamily: "Noto Sans JP, Hiragino Sans, Yu Gothic UI, Meiryo, sans-serif"
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.7
  body-small:
    fontFamily: "Noto Sans JP, Hiragino Sans, Yu Gothic UI, Meiryo, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.7
  display:
    fontFamily: "Space Grotesk, Noto Sans JP, sans-serif"
    fontSize: 24px
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: -0.02em
  section-title:
    fontFamily: "Noto Sans JP, Hiragino Sans, Yu Gothic UI, Meiryo, sans-serif"
    fontSize: 20px
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: -0.01em
  eyebrow:
    fontFamily: "Space Grotesk, Noto Sans JP, sans-serif"
    fontSize: 12px
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: 0.18em
  label:
    fontFamily: "Noto Sans JP, Hiragino Sans, Yu Gothic UI, Meiryo, sans-serif"
    fontSize: 13px
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: 0.04em
rounded:
  sm: 8px
  md: 12px
  control: 16px
  card: 24px
  frame: 32px
  pill: 9999px
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  2xl: 24px
  3xl: 32px
  4xl: 48px
components:
  app-frame:
    backgroundColor: "{colors.surface-glass}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.frame}"
    padding: 16px
  card:
    backgroundColor: "{colors.surface-glass}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.card}"
    padding: 20px
  button-primary:
    backgroundColor: "{colors.dark-surface}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.control}"
    padding: 12px
    height: 44px
  button-secondary:
    backgroundColor: "{colors.brand-strong}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.control}"
    padding: 12px
    height: 44px
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: 12px
    height: 44px
  badge-success:
    backgroundColor: "{colors.status-success-bg}"
    textColor: "{colors.status-success-text}"
    rounded: "{rounded.pill}"
    padding: 8px
  badge-warning:
    backgroundColor: "{colors.status-warning-bg}"
    textColor: "{colors.status-warning-text}"
    rounded: "{rounded.pill}"
    padding: 8px
  badge-danger:
    backgroundColor: "{colors.status-danger-bg}"
    textColor: "{colors.status-danger-text}"
    rounded: "{rounded.pill}"
    padding: 8px
---

## Brand & Style

SeedShiftRadio の視覚テーマは「深夜の個人ラジオブース」と「信頼できる運用卓」の中間に置く。
冷たい SaaS 管理画面には寄せず、温かいオフホワイト、青緑の放送ランプ、控えめなテラコッタを使う。
ただし、装飾よりも再生状態、次のキュー、異常、保存前の変更を速く読めることを優先する。

### 現状監査からの決定

- 現行のオフホワイト背景、青緑、テラコッタ、大きな角丸はブランド資産として継承する。
- 全カードへ同じ glass と登場アニメーションを掛ける構成は見直す。公開画面の主役カードにだけ空気感を残し、設定・監視は不透明度と情報密度を上げる。
- `Space Grotesk` は定義だけで終わらせず、英字ロゴ、eyebrow、数値メトリクスへ限定して使う。日本語本文と見出しは `Noto Sans JP` を正本とする。
- PC の固定 3 カラムではなく 12 カラムを基準にする。情報量に応じて 7:5、8:4、3 等分を選び、読み順は DOM 順と一致させる。
- モバイルではブランド、ナビ、状態 badge の全量を縦積みにしない。Now Playing と再生操作が最初の viewport に残る高さへ圧縮する。
- 主要操作の高さは 44px 以上とする。現行の約 37px のナビ・ボタンは次回 UI 適用時に拡大する。

ブランド文言は UI 内では `SeedShiftRadio` を使用する。
短縮ロゴは `SS` とし、独立した正方形の dark surface に置く。
公開画面では「聴く・送る」を先に、管理画面では「把握する・判断する・保存する」を先に見せる。

## Colors

### 基本配色

- `canvas` から `canvas-bottom` への暖色系グラデーションをページ背景に使う。
- 左上に `brand-bright`、右上に `warm` の低不透明度 radial gradient を許可する。本文の背後で色面が強く見えない濃度に抑える。
- 本文は `text-primary`、見出しは `text-strong`、補足は `text-secondary` または `text-muted` を使う。
- `warm` は雰囲気づくり、補助的な注目、波形やアートワークに限定する。小さい本文や成功・警告の意味には流用しない。

### 状態色

状態は必ず色とテキストを併用する。

| 意味 | 背景 | 文字 | 例 |
|---|---|---|---|
| 通常・停止 | `status-neutral-bg` | `status-neutral-text` | `IDLE`, `CLOSED` |
| 正常・接続 | `status-success-bg` | `status-success-text` | `CONNECTED`, `READY` |
| 注意・準備 | `status-warning-bg` | `status-warning-text` | `PREPARING`, `DEGRADED`, `NO STATION` |
| 失敗・危険 | `status-danger-bg` | `status-danger-text` | `ERROR`, validation error |

白文字を使う青緑の solid button は `brand-strong` 以上の濃さにする。
`brand-bright` は focus ring、淡い装飾、選択背景へ使い、白文字との通常サイズの組み合わせには使わない。

## Typography

### 役割

- 日本語本文、フォーム、長い説明: `typography.body` または `typography.body-small`。
- 画面の主要見出し、Now Playing: `typography.display`。日本語を含む場合は先頭の `Space Grotesk` へ依存せずフォールバック後の字幅を確認する。
- カード見出し: `typography.section-title`。
- `RADIO`, `HEALTH`, `QUEUE` などの eyebrow: `typography.eyebrow`。常に大文字、1 行、補助情報として使う。
- 入力ラベル: `typography.label`。日本語ラベルを強制 uppercase にしない。

本文の既定は 16px、補助本文の下限は 14px とする。
11px と 12px は短い eyebrow や metric label にだけ許可し、説明文、エラー、操作ラベルには使わない。
長文の 1 行幅はおおむね 68 文字以下に抑える。

フォント取得に失敗しても階層が崩れないよう、system fallback を必ず残す。
本番 build を外部 Google Fonts の到達性へ依存させず、必要なら font asset を自己ホストする。

## Layout & Spacing

### ページフレーム

- 最大幅は 1600px、中央寄せ。
- 横 gutter は mobile 16px、tablet/desktop 24px。
- カード間は 16px、カード内は原則 20px。密なフォーム群でも項目間 12px を下回らない。
- desktop は 12 カラム。ラジオ画面は主要再生領域 7〜8、補助情報 4〜5 を基本にし、必要な時だけ 3 つの情報レーンへ分割する。
- 設定・監視は主要編集/サマリ 7〜8、補助 health/preview 4〜5 を基本にする。

### レスポンシブ

- 640px 未満: 1 カラム。操作、Now Playing、字幕、Queue の順を優先する。
- 640px 以上 1024px 未満: 1〜2 カラム。カード内部の metric は 2 列まで。
- 1024px 以上: 12 カラム。視線移動の多い 3 等分は、同格情報だけに使う。
- 横スクロールは JSON、ログ、表の明示的な scroll container 以外で発生させない。
- sticky header は mobile で 2 段を基本とし、brand/nav と live status を分離する。状態 badge は重要度順に最大 2 件を見せ、残りは詳細へ送る。

### 情報密度

公開画面は余白を広くし、1 カード 1 目的とする。
管理画面は余白を少し詰めてもよいが、editor、health、preview、danger zone を同じカードへ混在させない。
設定画面の説明文は常時表示する要点と、必要時に開く補足へ分ける。

## Elevation & Depth

ページ背景の gradient と grid dots は ambient layer であり、意味を持たせない。
標準カードは `surface-glass` を使い、本文の可読性を保つ。
設定、監視、表、長いフォームは `surface` または 92% 以上の白を使い、背景模様を透過させすぎない。

標準 shadow は次を基準にする。

```css
0 0 0 1px rgb(15 23 42 / 0.08), 0 16px 40px rgb(15 23 42 / 0.12)
```

同一階層の全カードへ強い shadow を重ねない。
dark surface は audio console、JSON、ログ、決定的な primary action へ限定する。

motion は 160〜240ms の ease-out を基準とする。
初期表示の主要ブロックだけに 1 回使い、10 秒更新される monitor card や状態 badge の再描画には登場 animation を付けない。
`prefers-reduced-motion: reduce` では移動と反復 pulse を停止する。

## Shapes

- `frame` 32px: app header などページの外枠。
- `card` 24px: 標準カード、empty state の大枠。
- `control` 16px: button、input、select、textarea。
- `pill` 9999px: nav item、badge、短い filter。
- `sm` と `md` は表内 cell、compact alert、補助コンテナに限定する。

角丸の入れ子では外側より内側を 8px 以上小さくする。
すべてを pill にせず、状態と短い選択肢にだけ pill を使う。

## Components

### App frame と navigation

ブランド、公開 navigation、管理 navigation、接続/局/再生状態を同じ視覚階層に並べない。
公開 navigation は `Radio`, `Letters`、管理 navigation は `Settings`, `Monitor` とし、権限がない時は管理項目を表示しない。
active nav は dark surface、inactive は白系 surface と border で表す。
active 状態は色だけでなく `aria-current="page"` でも伝える。

### Card

標準 Card は heading、短い description、content、必要なら action の順とする。
tone variant は base の glass 背景より優先されなければならない。
base class と variant class の双方で `background` を競合させず、semantic tone が確実に描画される構造にする。

Card tone の用途は次の通り。

- default: 通常情報、フォーム、一覧。
- accent: Now Playing、選択中、次に行う安全な操作。
- warning: degraded、未保存、次回反映、要確認。
- dark: audio console、コード/JSON、短い高コントラスト領域。

### Button

- primary: 画面内の主操作を 1 つまで。再生、保存、確定。
- secondary: Tune、新規作成、接続テストなど前向きな補助操作。
- ghost: 戻る、reset、表示切替。
- danger: 削除、停止、破棄。通常操作と距離を取り、確認文脈を付ける。

高さは 44px 以上、左右 padding は 16px 以上。
disabled は opacity だけでなく cursor と必要な説明を用意する。
focus-visible は 2px の `brand-bright` ring と 2px の surface offset を持つ。

### Form

label は input の上に置き、placeholder を label の代用にしない。
input、select、textarea は同じ border、radius、focus ring を使う。
help text は field の直下、error は help text と置き換えるか隣接させる。
JSON editor と長い設定群は通常フォームと視覚的に分け、等幅 font を使用する。
未保存、保存中、保存成功、競合、validation error を card 上部の同じ位置に出す。

### Radio and audio

Now Playing はラジオ画面の最重要カードとし、番組名、セグメント種別、進行、再生操作を一つのまとまりで見せる。
Queue は現在から未来への順序が読める縦方向の流れにする。
字幕は装飾カードではなく高コントラストの live region とし、長文でも 2〜4 行で追える幅を確保する。

### Status, metrics, and monitoring

badge は 1〜2 語の状態に限定し、原因説明を badge 内へ詰め込まない。
metric は label、value、unit、freshness の順で読めるようにする。
monitor の自動更新でカード全体を動かさず、値または短い status だけ更新する。
エラー一覧は発生時刻、分類、対象、短い説明、次の行動を同じ順序で表示する。

### Empty, loading, and error states

empty state は「何もない理由」と「次にできること」を 1 文ずつ示す。
loading は layout を維持する skeleton を使い、既存コンテンツを全面的に消さない。
error は秘密情報や raw response を出さず、再試行、設定確認、権限確認のうち実行可能な導線を示す。

## Do's and Don'ts

### Do

- 再生状態、接続状態、生成状態を色、ラベル、必要なら icon の複数手段で示す。
- Now Playing、字幕、主要操作をモバイルの早い位置へ置く。
- 公開画面と管理画面の navigation、密度、説明量を分ける。
- semantic color と typography role を `DESIGN.md` の token へ寄せ、画面固有の hex 値を増やさない。
- 44px の操作領域、明確な focus-visible、`aria-live`、`aria-current` を保つ。
- 実データが長い場合、英数字 ID、日本語、空状態、degraded/error を含めて確認する。

### Don't

- すべての card に同じ半透明度、強い shadow、登場 animation を付けない。
- `brand-bright` に白い小文字を載せない。
- 状態を teal 一色へまとめず、warning と danger の意味を保つ。
- 11px/12px を説明文や操作ラベルに使わない。
- mobile header へ全 badge を縦積みし、再生操作を first viewport から追い出さない。
- placeholder、色、hover だけに意味を依存させない。
- prompt、lyrics、letter body、radioName、secret、raw provider response を監視カードや error detail に表示しない。

