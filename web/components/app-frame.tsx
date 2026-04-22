"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import clsx from "clsx";
import type { PropsWithChildren } from "react";
import { Badge } from "@/components/ui";
import { getAdminToken } from "@/lib/env";
import { useUiStore } from "@/stores/ui-store";

const publicNavItems = [
  { href: "/", label: "Radio" },
  { href: "/letters", label: "Letters" },
];

const adminNavItems = [
  { href: "/settings", label: "Settings" },
  { href: "/monitor", label: "Monitor" },
];

export function AppFrame({ children }: PropsWithChildren) {
  const pathname = usePathname();
  const connectionStatus = useUiStore((state) => state.connectionStatus);
  const liveSubtitle = useUiStore((state) => state.liveSubtitle);
  const lastEventId = useUiStore((state) => state.lastEventId);
  const selectedStationId = useUiStore((state) => state.selectedStationId);
  const radioName = useUiStore((state) => state.radioName);
  const hasAdminToken = Boolean(getAdminToken());
  const navItems = hasAdminToken ? [...publicNavItems, ...adminNavItems] : publicNavItems;

  return (
    <div className="relative min-h-screen text-slate-900">
      <div className="absolute inset-0 grid-dots opacity-40" aria-hidden="true" />
      <div className="relative mx-auto flex min-h-screen w-full max-w-[1600px] flex-col px-4 py-4 md:px-6 md:py-6">
        <header className="glass sticky top-4 z-20 mb-4 rounded-[2rem] border border-white/70 px-4 py-4 shadow-glow md:px-6">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex items-center gap-4">
              <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-950 text-lg font-bold text-white shadow-lg shadow-slate-950/20">
                SS
              </div>
              <div>
                <div className="text-xs font-semibold uppercase tracking-[0.24em] text-teal-700">SeedShiftRadio</div>
                <div className="text-lg font-semibold tracking-tight text-slate-950">Local AI radio console</div>
              </div>
            </div>
            <nav className="flex flex-wrap gap-2">
              {navItems.map((item) => {
                const active = pathname === item.href;
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={clsx(
                      "rounded-full border px-4 py-2 text-sm font-semibold transition",
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
            <div className="flex flex-wrap items-center gap-2">
              <div data-testid="connection-status">
                <Badge tone={connectionStatus === "connected" ? "success" : connectionStatus === "reconnecting" ? "warning" : "default"}>
                  {connectionStatus.toUpperCase()}
                </Badge>
              </div>
              {selectedStationId ? <Badge tone="accent">{selectedStationId}</Badge> : <Badge tone="warning">NO STATION</Badge>}
              <Badge tone="default">{radioName}</Badge>
            </div>
          </div>
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
