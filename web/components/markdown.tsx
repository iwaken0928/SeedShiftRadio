import type { PropsWithChildren } from "react";
import clsx from "clsx";

export function PanelGrid({ children }: PropsWithChildren) {
  return <div className="grid gap-4 xl:grid-cols-12">{children}</div>;
}

export function PanelColumn({ children, className }: PropsWithChildren<{ className?: string }>) {
  return <div className={clsx("space-y-4", className)}>{children}</div>;
}
