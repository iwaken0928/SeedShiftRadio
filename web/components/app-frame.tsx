"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { useState, type PropsWithChildren } from "react";
import { Badge, Button } from "@/components/ui";
import { useAdminSession } from "@/lib/admin-session";
import { useUiStore } from "@/stores/ui-store";

const publicNavItems = [
  { href: "/", label: "ラジオ" },
  { href: "/letters", label: "レター" },
];

const adminNavItems = [
  { href: "/settings", label: "管理" },
  { href: "/monitor", label: "監視" },
];

export function AppFrame({ children }: PropsWithChildren) {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const connectionStatus = useUiStore((state) => state.connectionStatus);
  const liveSubtitle = useUiStore((state) => state.liveSubtitle);
  const lastEventId = useUiStore((state) => state.lastEventId);
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const radioName = useUiStore((state) => state.radioName);
  const adminSession = useAdminSession();
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const navItems = adminSession.data?.authenticated ? [...publicNavItems, ...adminNavItems] : publicNavItems;

  async function logout() {
    setLogoutError(null);
    try {
      const sessionResponse = await fetch("/api/auth/session", { cache: "no-store" });
      const session = await sessionResponse.json() as { csrfToken?: string };
      if (!session.csrfToken) throw new Error("管理 session を確認できませんでした。");
      const response = await fetch("/api/auth/logout", { method: "POST", headers: { "X-CSRF-Token": session.csrfToken } });
      if (!response.ok) throw new Error("ログアウトできませんでした。");
      queryClient.setQueryData(["admin-session"], { authenticated: false });
      router.push("/");
      router.refresh();
    } catch (cause) {
      setLogoutError(cause instanceof Error ? cause.message : "ログアウトできませんでした。");
      await queryClient.invalidateQueries({ queryKey: ["admin-session"] });
    }
  }

  return (
    <div className="relative min-h-screen text-slate-900">
      <div className="absolute inset-0 grid-dots opacity-40" aria-hidden="true" />
      <div className="relative mx-auto flex min-h-screen w-full max-w-[1600px] flex-col px-4 py-4 md:px-6 md:py-6">
        <header className="glass sticky top-4 z-20 mb-4 rounded-[2rem] border border-white/70 bg-white/[0.88] px-4 py-4 shadow-glow md:px-6">
          <div className="grid gap-3 lg:grid-cols-[auto_minmax(0,1fr)_auto] lg:items-center lg:gap-4">
            <div className="flex min-w-0 items-center gap-3 sm:gap-4">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-950 font-[var(--font-display)] text-base font-bold text-white shadow-lg shadow-slate-950/20 sm:h-12 sm:w-12 sm:rounded-2xl sm:text-lg">
                SS
              </div>
              <div className="min-w-0">
                <div className="truncate font-[var(--font-display)] text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">SeedShiftRadio</div>
                <div className="truncate text-sm font-semibold tracking-tight text-slate-950 sm:text-lg">Local AI radio console</div>
              </div>
            </div>
            <nav aria-label="主要ナビゲーション" className="flex min-w-0 gap-2 overflow-x-auto pb-1 lg:justify-center lg:pb-0">
              {navItems.map((item) => {
                const active = pathname === item.href || (item.href !== "/" && pathname.startsWith(`${item.href}/`));
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    className={clsx(
                      "interactive-control shrink-0 rounded-full border px-4 py-2 text-sm font-semibold",
                      active
                        ? "border-slate-950 bg-slate-950 text-white"
                        : "border-slate-200 bg-white/70 text-slate-700 hover:border-teal-300 hover:text-slate-950",
                    )}
                  >
                    {item.label}
                  </Link>
                );
              })}
            </nav>
            <div className="flex min-w-0 flex-wrap items-center gap-2 lg:justify-end">
              {adminSession.data?.authenticated ? <Button tone="ghost" onClick={logout}>ログアウト</Button> : null}
              <div data-testid="connection-status">
                <Badge tone={connectionStatus === "connected" ? "success" : connectionStatus === "reconnecting" ? "warning" : "default"}>
                  {connectionStatus.toUpperCase()}
                </Badge>
              </div>
              {selectedStationId ? <Badge tone="accent">{selectedStationId}</Badge> : <Badge tone="warning">NO STATION</Badge>}
              <span className="hidden xl:inline-flex"><Badge tone="default">{radioName}</Badge></span>
            </div>
          </div>
          {logoutError ? <p role="alert" className="motion-notice mt-3 text-sm font-semibold text-rose-700">{logoutError}</p> : null}
          {liveSubtitle ? (
            <p
              className="mt-3 rounded-2xl bg-slate-950/5 px-4 py-2 text-sm text-slate-700"
              aria-live="polite"
              aria-atomic="true"
              role="status"
              data-testid="live-subtitle"
            >
              {liveSubtitle}
            </p>
          ) : null}
          <div className="sr-only" data-testid="live-subtitle-value">
            {liveSubtitle}
          </div>
          <div className="sr-only" data-testid="last-event-id">
            {lastEventId ?? ""}
          </div>
        </header>

        <main className="relative flex-1">{children}</main>
      </div>
    </div>
  );
}
