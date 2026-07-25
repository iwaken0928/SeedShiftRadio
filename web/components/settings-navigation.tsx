"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import clsx from "clsx";
import { SETTINGS_PAGES } from "@/lib/settings-pages";

export function SettingsNavigation() {
  const pathname = usePathname();

  return (
    <nav aria-label="設定カテゴリー" className="grid gap-2 sm:grid-cols-2 xl:grid-cols-5">
      {SETTINGS_PAGES.map((item) => {
        const active = pathname === item.href;
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={clsx(
              "interactive-control min-h-[92px] rounded-2xl border px-4 py-3",
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
