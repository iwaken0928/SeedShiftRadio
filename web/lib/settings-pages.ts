export const SETTINGS_PAGES = [
  {
    href: "/settings/system",
    label: "システム",
    description: "待受アドレス、保存先、管理認証、設定ファイルを管理します。",
  },
  {
    href: "/settings/providers",
    label: "AI・音声接続",
    description: "LLM、音声合成、音楽生成の接続先と優先順を管理します。",
  },
  {
    href: "/settings/playout",
    label: "再生・生成",
    description: "キューの先読み、生成量、キャッシュと共通の編成既定値を管理します。",
  },
  {
    href: "/settings/stations",
    label: "局",
    description: "局名、周波数、人格、音声、有効状態を管理します。",
  },
  {
    href: "/settings/programming",
    label: "番組編成",
    description: "局別ポリシー、番組テンプレート、時間帯ルールを管理し、編成を確認します。",
  },
] as const;
