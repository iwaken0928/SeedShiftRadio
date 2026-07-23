import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import {
  ADMIN_SESSION_COOKIE,
  getRequestAdminSession,
  sessionCookieOptions,
  verifyCsrfToken,
} from "@/lib/server/admin-auth";
import { getRequestProtocol } from "@/lib/server/request-origin";

export function POST(request: NextRequest) {
  const session = getRequestAdminSession(request);
  if (!session || !verifyCsrfToken(session, request.headers.get("x-csrf-token"))) {
    return Response.json({ message: "CSRF token が不正です。" }, { status: 403 });
  }
  const response = new NextResponse(null, { status: 204 });
  response.cookies.set(ADMIN_SESSION_COOKIE, "", {
    ...sessionCookieOptions(getRequestProtocol(request) === "https:"),
    maxAge: 0,
  });
  return response;
}
