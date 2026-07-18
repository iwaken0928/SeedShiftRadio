"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Button, Card, Input, SectionHeader } from "@/components/ui";

export function AdminLogin({ returnTo }: { returnTo: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      if (!response.ok) {
        const payload = (await response.json().catch(() => ({}))) as { message?: string };
        throw new Error(payload.message || "ログインできませんでした。");
      }
      queryClient.setQueryData(["admin-session"], { authenticated: true });
      router.replace(returnTo);
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "ログインできませんでした。");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl pt-12">
      <Card>
        <SectionHeader eyebrow="Admin" title="管理画面へログイン" description="管理用パスワードを入力してください。Server の管理トークンが browser に送られることはありません。" />
        <form className="space-y-4" onSubmit={submit}>
          <Input aria-label="管理用パスワード" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
          {error ? <p role="alert" className="text-sm font-semibold text-rose-700">{error}</p> : null}
          <Button type="submit" disabled={submitting}>{submitting ? "確認中..." : "ログイン"}</Button>
        </form>
      </Card>
    </div>
  );
}
