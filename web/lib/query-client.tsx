"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { usePathname } from "next/navigation";
import { useState, type PropsWithChildren } from "react";
import { LiveStreamBridge } from "@/components/live-stream-bridge";

export function AppProviders({ children }: PropsWithChildren) {
  const pathname = usePathname();
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5_000,
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  }));

  return (
    <QueryClientProvider client={queryClient}>
      {pathname !== "/stream-harness" ? <LiveStreamBridge /> : null}
      {children}
    </QueryClientProvider>
  );
}
