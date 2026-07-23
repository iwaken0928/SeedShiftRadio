import type { NextRequest } from "next/server";

export function hasSameRequestOrigin(request: NextRequest) {
  const origin = parseOrigin(request.headers.get("origin"));
  const host = firstForwardedValue(request.headers.get("x-forwarded-host")) ?? request.headers.get("host")?.trim();
  const protocol = getRequestProtocol(request);

  return Boolean(origin && host && protocol && origin.host.toLowerCase() === host.toLowerCase() && origin.protocol === protocol);
}

export function getRequestProtocol(request: NextRequest) {
  return (
    normalizeProtocol(firstForwardedValue(request.headers.get("x-forwarded-proto"))) ??
    normalizeProtocol(request.nextUrl.protocol)
  );
}

function parseOrigin(value: string | null) {
  if (!value) return null;
  try {
    const origin = new URL(value);
    if (origin.username || origin.password || origin.pathname !== "/" || origin.search || origin.hash) return null;
    return origin;
  } catch {
    return null;
  }
}

function firstForwardedValue(value: string | null) {
  const first = value?.split(",", 1)[0]?.trim();
  return first || null;
}

function normalizeProtocol(value: string | null) {
  if (!value) return null;
  const protocol = value.trim().toLowerCase();
  if (protocol === "http" || protocol === "https") return `${protocol}:`;
  if (protocol === "http:" || protocol === "https:") return protocol;
  return null;
}
