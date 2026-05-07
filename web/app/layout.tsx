import type { Metadata } from "next";
import { Noto_Sans_JP, Space_Grotesk } from "next/font/google";
import type { ReactNode } from "react";
import { AppFrame } from "@/components/app-frame";
import { AppProviders } from "@/lib/query-client";
import "@/app/globals.css";

const bodyFont = Noto_Sans_JP({
  subsets: ["latin"],
  variable: "--font-body",
  weight: ["400", "500", "700"],
});

const displayFont = Space_Grotesk({
  subsets: ["latin"],
  variable: "--font-display",
  weight: ["500", "700"],
});

export const metadata: Metadata = {
  title: "SeedShiftRadio Web",
  description: "SeedShiftRadio のラジオ操作、レター、設定、監視を扱う Web UI",
};

type RootLayoutProps = Readonly<{ children: ReactNode }>;

export default function RootLayout({ children }: RootLayoutProps) {
  return (
    <html lang="ja" className={`${bodyFont.variable} ${displayFont.variable}`}>
      <body className="font-[var(--font-body)]">
        <AppProviders>
          <AppFrame>{children}</AppFrame>
        </AppProviders>
      </body>
    </html>
  );
}
