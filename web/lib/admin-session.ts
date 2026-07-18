"use client";

import { useQuery } from "@tanstack/react-query";

export type AdminSessionResponse = { authenticated: boolean; csrfToken?: string };

export async function fetchAdminSession(): Promise<AdminSessionResponse> {
  const response = await fetch("/api/auth/session", { cache: "no-store" });
  if (!response.ok) return { authenticated: false };
  return (await response.json()) as AdminSessionResponse;
}

export function useAdminSession() {
  return useQuery({ queryKey: ["admin-session"], queryFn: fetchAdminSession, retry: false, staleTime: 30_000 });
}
