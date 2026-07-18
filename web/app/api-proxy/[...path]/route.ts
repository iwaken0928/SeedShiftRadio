import type { NextRequest } from "next/server";
import { getRequestAdminSession, getServerAdminToken, verifyCsrfToken } from "@/lib/server/admin-auth";
import { isUnsafeMethod, requiresAdminSession } from "@/lib/server/proxy-policy";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

type RouteContext = {
  params: Promise<{
    path?: string[];
  }>;
};

async function proxy(request: NextRequest, context: RouteContext) {
  const { path = [] } = await context.params;
  const adminRequest = requiresAdminSession(request.method, path);
  if (adminRequest) {
    const session = getRequestAdminSession(request);
    if (!session) {
      return Response.json({ code: "ADMIN_SESSION_REQUIRED", message: "管理者ログインが必要です。" }, { status: 401 });
    }
    if (isUnsafeMethod(request.method) && !verifyCsrfToken(session, request.headers.get("x-csrf-token"))) {
      return Response.json({ code: "CSRF_TOKEN_INVALID", message: "CSRF token が不正です。" }, { status: 403 });
    }
  }
  const target = buildTargetUrl(path, request.nextUrl.search);
  const headers = filterHeaders(request.headers);
  headers.delete("cookie");
  headers.delete("x-admin-token");
  headers.delete("x-csrf-token");
  if (adminRequest) {
    const adminToken = getServerAdminToken();
    if (!adminToken) {
      return Response.json({ code: "ADMIN_AUTH_UNAVAILABLE", message: "管理 API の認証設定がありません。" }, { status: 503 });
    }
    headers.set("X-Admin-Token", adminToken);
  }
  const body = await readRequestBody(request);
  const response = await fetch(target, {
    method: request.method,
    headers,
    body,
    cache: "no-store",
    redirect: "manual",
  });

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: filterHeaders(response.headers),
  });
}

function buildTargetUrl(path: string[], search: string) {
  const base = process.env.SERVER_INTERNAL_API_BASE_URL?.trim() || "http://127.0.0.1:8080";
  const normalizedBase = base.endsWith("/") ? base : `${base}/`;
  const encodedPath = path.map((segment) => encodeURIComponent(segment)).join("/");
  const url = new URL(encodedPath, normalizedBase);
  url.search = search;
  return url;
}

function filterHeaders(source: Headers) {
  const headers = new Headers();
  source.forEach((value, key) => {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) {
      headers.set(key, value);
    }
  });
  return headers;
}

async function readRequestBody(request: NextRequest) {
  if (request.method === "GET" || request.method === "HEAD") {
    return undefined;
  }
  return await request.arrayBuffer();
}

export {
  proxy as DELETE,
  proxy as GET,
  proxy as HEAD,
  proxy as PATCH,
  proxy as POST,
  proxy as PUT,
};
