import type { ReactNode } from "react";
import { SettingsNavigation } from "@/components/settings-navigation";

export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <div className="space-y-4">
      <section className="rounded-[2rem] border border-white/70 bg-white/[0.92] p-4 shadow-glow md:p-5">
        <div className="mb-4 max-w-3xl">
          <p className="font-[var(--font-display)] text-xs font-bold uppercase tracking-[0.18em] text-teal-700">Administration</p>
          <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-950">管理コンソール</h1>
          <p className="mt-2 text-sm leading-7 text-slate-600">
            ダッシュボードで全体状況を確認し、局・番組・生成コンテンツ・接続設定を役割ごとのページで管理します。
          </p>
        </div>
        <SettingsNavigation />
      </section>
      {children}
    </div>
  );
}
