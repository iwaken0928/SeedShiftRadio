import type { PropsWithChildren } from "react";
import { MonitorNavigation } from "@/components/monitor-navigation";

export default function MonitorLayout({ children }: PropsWithChildren) {
  return (
    <div>
      <MonitorNavigation />
      {children}
    </div>
  );
}
