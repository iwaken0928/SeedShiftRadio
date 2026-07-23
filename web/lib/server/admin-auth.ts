import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import type { NextRequest } from "next/server";

export const ADMIN_SESSION_COOKIE = "seedshift_admin_session";
const SESSION_TTL_SECONDS = 8 * 60 * 60;

type SessionPayload = { id: string; expiresAt: number };

export function createAdminSession(now = Date.now()) {
  const payload: SessionPayload = {
    id: randomBytes(24).toString("base64url"),
    expiresAt: Math.floor(now / 1000) + SESSION_TTL_SECONDS,
  };
  const encoded = Buffer.from(JSON.stringify(payload)).toString("base64url");
  return `${encoded}.${sign(encoded, sessionSecret())}`;
}

export function readAdminSession(value: string | undefined, now = Date.now()): SessionPayload | null {
  if (!value) return null;
  const [encoded, signature, extra] = value.split(".");
  if (!encoded || !signature || extra || !safeEqual(signature, sign(encoded, sessionSecret()))) return null;
  try {
    const payload = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8")) as SessionPayload;
    if (typeof payload.id !== "string" || !payload.id || typeof payload.expiresAt !== "number") return null;
    return payload.expiresAt > Math.floor(now / 1000) ? payload : null;
  } catch {
    return null;
  }
}

export function getRequestAdminSession(request: NextRequest) {
  return readAdminSession(request.cookies.get(ADMIN_SESSION_COOKIE)?.value);
}

export function createCsrfToken(session: SessionPayload) {
  return sign(`csrf:${session.id}`, sessionSecret());
}

export function verifyCsrfToken(session: SessionPayload, token: string | null) {
  return Boolean(token && safeEqual(token, createCsrfToken(session)));
}

export function verifyLoginPassword(password: string) {
  const expected = process.env.SEEDSHIFT_WEB_ADMIN_PASSWORD?.trim();
  return Boolean(expected && safeEqual(password, expected));
}

export function getServerAdminToken() {
  return process.env.SEEDSHIFT_ADMIN_TOKEN?.trim() || null;
}

export function sessionCookieOptions(secure: boolean) {
  return {
    httpOnly: true,
    secure,
    sameSite: "strict" as const,
    path: "/",
    maxAge: SESSION_TTL_SECONDS,
  };
}

function sessionSecret() {
  const configured = process.env.SEEDSHIFT_WEB_SESSION_SECRET?.trim();
  if (configured && (process.env.NODE_ENV !== "production" || Buffer.byteLength(configured, "utf8") >= 32)) return configured;
  if (process.env.NODE_ENV === "production") {
    throw new Error("SEEDSHIFT_WEB_SESSION_SECRET must be at least 32 bytes in production");
  }
  return "seedshift-radio-development-session-secret";
}

function sign(value: string, secret: string) {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

function safeEqual(actual: string, expected: string) {
  const actualBuffer = Buffer.from(actual);
  const expectedBuffer = Buffer.from(expected);
  return actualBuffer.length === expectedBuffer.length && timingSafeEqual(actualBuffer, expectedBuffer);
}
