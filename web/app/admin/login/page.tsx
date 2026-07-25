import { redirect } from "next/navigation";
import { cookies } from "next/headers";
import { AdminLogin } from "@/components/admin-login";
import { ADMIN_SESSION_COOKIE, readAdminSession } from "@/lib/server/admin-auth";

export default async function Page({ searchParams }: { searchParams: Promise<{ returnTo?: string }> }) {
  const cookieStore = await cookies();
  const params = await searchParams;
  const returnTo = sanitizeReturnTo(params.returnTo);
  if (readAdminSession(cookieStore.get(ADMIN_SESSION_COOKIE)?.value)) redirect(returnTo);
  return <AdminLogin returnTo={returnTo} />;
}

function sanitizeReturnTo(value?: string) {
  const allowedSettingsPages = new Set([
    "/settings",
    "/settings/system",
    "/settings/providers",
    "/settings/playout",
    "/settings/stations",
    "/settings/programming",
  ]);
  return value === "/monitor" || value === "/letters" || (value ? allowedSettingsPages.has(value) : false) ? value! : "/settings";
}
