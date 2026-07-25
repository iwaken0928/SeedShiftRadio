import { StationContentManagement } from "@/components/station-content-management";
import { requireAdminPage } from "@/lib/server/require-admin-page";

export default async function Page() {
  await requireAdminPage("/settings/content");
  return <StationContentManagement />;
}
