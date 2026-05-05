import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
    "./stores/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      boxShadow: {
        glow: "0 0 0 1px rgba(15, 23, 42, 0.08), 0 16px 40px rgba(15, 23, 42, 0.12)",
      },
      keyframes: {
        floatIn: {
          "0%": { opacity: "0", transform: "translateY(12px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        pulseSoft: {
          "0%, 100%": { opacity: "0.72" },
          "50%": { opacity: "1" },
        },
      },
      animation: {
        floatIn: "floatIn 420ms ease-out both",
        pulseSoft: "pulseSoft 4s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};

export default config;
