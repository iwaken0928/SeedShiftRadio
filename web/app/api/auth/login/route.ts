import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { ADMIN_SESSION_COOKIE, createAdminSession, sessionCookieOptions, verifyLoginPassword } from "@/lib/server/admin-auth";

export async function POST(request: NextRequest) {
  if (!hasSameOrigin(request)) {
    return Response.json({ message: "同一 origin から操作してください。" }, { status: 403 });
  }
  const payload = (await request.json().catch(() => null)) as { password?: unknown } | null;
  if (!payload || typeof payload.password !== "string" || !verifyLoginPassword(payload.password)) {
    return Response.json({ message: "ログイン情報が正しくありません。" }, { status: 401 });
  }
  const response = NextResponse.json({ authenticated: true });
  response.headers.set("Cache-Control", "no-store");
  response.cookies.set(ADMIN_SESSION_COOKIE, createAdminSession(), sessionCookieOptions());
  return response;
}

function hasSameOrigin(request: NextRequest) {
  const origin = request.headers.get("origin");
  return Boolean(origin && origin === request.nextUrl.origin);
}
