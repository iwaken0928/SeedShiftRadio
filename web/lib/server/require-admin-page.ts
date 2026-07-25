import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ADMIN_SESSION_COOKIE, readAdminSession } from "@/lib/server/admin-auth";

export async function requireAdminPage(returnTo: "/monitor" | `/settings${string}`) {
  const cookieStore = await cookies();
  if (!readAdminSession(cookieStore.get(ADMIN_SESSION_COOKIE)?.value)) {
    redirect(`/admin/login?returnTo=${encodeURIComponent(returnTo)}`);
  }
}
