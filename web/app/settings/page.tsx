import { SettingsOverview } from "@/components/settings-overview";
import { requireAdminPage } from "@/lib/server/require-admin-page";

export default async function Page() {
  await requireAdminPage("/settings");
  return <SettingsOverview />;
}
