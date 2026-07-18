import { MonitorDashboard } from "@/components/monitor-dashboard";
import { requireAdminPage } from "@/lib/server/require-admin-page";

export default async function Page() {
  await requireAdminPage("/monitor");
  return <MonitorDashboard />;
}
