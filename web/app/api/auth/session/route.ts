import type { NextRequest } from "next/server";
import { createCsrfToken, getRequestAdminSession } from "@/lib/server/admin-auth";

export function GET(request: NextRequest) {
  const session = getRequestAdminSession(request);
  const response = Response.json(
    session ? { authenticated: true, csrfToken: createCsrfToken(session) } : { authenticated: false },
  );
  response.headers.set("Cache-Control", "no-store");
  return response;
}
