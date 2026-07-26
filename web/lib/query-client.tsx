"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { usePathname } from "next/navigation";
import { useEffect, useState, type PropsWithChildren } from "react";
import { LiveStreamBridge } from "@/components/live-stream-bridge";
import { useUiStore } from "@/stores/ui-store";

export function AppProviders({ children }: PropsWithChildren) {
  const pathname = usePathname();
  const hasHydrated = useUiStore((state) => state.hasHydrated);
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5_000,
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  }));

  useEffect(() => {
    void Promise.resolve(useUiStore.persist.rehydrate()).finally(() => {
      useUiStore.getState().setHasHydrated(true);
    });
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      {hasHydrated ? (
        <>
          {pathname !== "/stream-harness" ? <LiveStreamBridge /> : null}
          {children}
        </>
      ) : (
        <div className="min-h-screen bg-slate-50" aria-label="画面を準備中" />
      )}
    </QueryClientProvider>
  );
}
