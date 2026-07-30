import { JobExecutionSettingsPanel } from "@/components/job-execution-settings";
import { requireAdminPage } from "@/lib/server/require-admin-page";

export default async function Page() {
  await requireAdminPage("/settings/jobs");
  return <JobExecutionSettingsPanel />;
}
