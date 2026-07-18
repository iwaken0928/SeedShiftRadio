import { SettingsDashboard } from "@/components/settings-dashboard";
import { requireAdminPage } from "@/lib/server/require-admin-page";

export default async function Page() {
  await requireAdminPage("/settings");
  return <SettingsDashboard />;
}
