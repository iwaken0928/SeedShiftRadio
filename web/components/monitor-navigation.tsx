"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import clsx from "clsx";

const MONITOR_PAGES = [
  {
    href: "/monitor",
    label: "サマリー",
    description: "再生、Provider、生成ジョブ、監査イベントの現在状態",
  },
  {
    href: "/monitor/logs",
    label: "運用ログ",
    description: "生成失敗、Provider エラー、相関 ID を追跡する構造化ログ",
  },
] as const;

export function MonitorNavigation() {
  const pathname = usePathname();

  return (
    <nav aria-label="監視カテゴリー" className="mb-4 grid gap-2 sm:grid-cols-2">
      {MONITOR_PAGES.map((item) => {
        const active = pathname === item.href;
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={clsx(
              "interactive-control rounded-2xl border px-4 py-3",
              active
                ? "border-slate-950 bg-slate-950 text-white"
                : "border-slate-200 bg-white text-slate-900 hover:border-teal-400",
            )}
          >
            <span className="block text-sm font-bold">{item.label}</span>
            <span className={clsx("mt-1 block text-xs leading-5", active ? "text-slate-200" : "text-slate-600")}>
              {item.description}
            </span>
          </Link>
        );
      })}
    </nav>
  );
}
