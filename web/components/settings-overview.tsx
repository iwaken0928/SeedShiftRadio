import Link from "next/link";
import { Card, SectionHeader } from "@/components/ui";
import { PanelColumn, PanelGrid } from "@/components/markdown";
import { SETTINGS_PAGES } from "@/lib/settings-pages";

export function SettingsOverview() {
  return (
    <PanelGrid>
      <PanelColumn className="xl:col-span-12">
        <Card>
          <SectionHeader
            eyebrow="Settings"
            title="設定する内容を選んでください"
            description="設定は役割ごとに分かれています。変更したい内容のカテゴリーを開くと、その設定に必要な説明、入力項目、保存操作だけが表示されます。"
          />
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {SETTINGS_PAGES.map((item, index) => (
              <Link
                key={item.href}
                href={item.href}
                className="interactive-control group rounded-3xl border border-slate-200 bg-white p-5 hover:border-teal-400"
              >
                <span className="font-[var(--font-display)] text-xs font-bold uppercase tracking-[0.18em] text-teal-700">
                  {String(index + 1).padStart(2, "0")}
                </span>
                <span className="mt-3 block text-xl font-bold tracking-tight text-slate-950">{item.label}</span>
                <span className="mt-2 block text-sm leading-7 text-slate-600">{item.description}</span>
                <span className="mt-5 inline-flex text-sm font-bold text-teal-800 group-hover:text-teal-600">
                  この設定を開く →
                </span>
              </Link>
            ))}
          </div>
          <div className="mt-5 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">
            再生中の番組構成に関わる変更は、現在の番組を途中で書き換えず、次の番組から反映されます。接続先を変更した場合は、保存後に「AI・音声接続」で接続確認を実行してください。
          </div>
        </Card>
      </PanelColumn>
    </PanelGrid>
  );
}
