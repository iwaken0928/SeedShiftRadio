"use client";

import clsx from "clsx";
import type { ButtonHTMLAttributes, ComponentPropsWithoutRef, InputHTMLAttributes, PropsWithChildren, ReactNode, TextareaHTMLAttributes } from "react";

export function Card({
  children,
  className,
  tone = "default",
  ...props
}: PropsWithChildren<ComponentPropsWithoutRef<"section"> & { tone?: "default" | "accent" | "warning" | "dark" }>) {
  const toneClass = {
    default: "bg-white/82 border-slate-200/80",
    accent: "bg-teal-50/90 border-teal-200/80",
    warning: "bg-amber-50/90 border-amber-200/80",
    dark: "bg-slate-950/92 border-slate-800 text-slate-50",
  }[tone];

  return (
    <section
      {...props}
      className={clsx("glass rounded-3xl border p-5 shadow-glow transition duration-300 animate-floatIn", toneClass, className)}
    >
      {children}
    </section>
  );
}

export function Badge({
  children,
  tone = "default",
}: PropsWithChildren<{ tone?: "default" | "success" | "warning" | "danger" | "accent" }>) {
  const toneClass = {
    default: "bg-slate-100 text-slate-700 border-slate-200",
    success: "bg-emerald-100 text-emerald-800 border-emerald-200",
    warning: "bg-amber-100 text-amber-800 border-amber-200",
    danger: "bg-rose-100 text-rose-800 border-rose-200",
    accent: "bg-teal-100 text-teal-800 border-teal-200",
  }[tone];

  return <span className={clsx("inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-semibold tracking-wide", toneClass)}>{children}</span>;
}

export function Button({
  children,
  className,
  tone = "primary",
  ...props
}: PropsWithChildren<
  ButtonHTMLAttributes<HTMLButtonElement> & {
    tone?: "primary" | "secondary" | "ghost" | "danger";
  }
>) {
  const toneClass = {
    primary: "bg-slate-950 text-white hover:bg-slate-800",
    secondary: "bg-teal-600 text-white hover:bg-teal-500",
    ghost: "bg-transparent text-slate-900 border border-slate-300 hover:bg-white/60",
    danger: "bg-rose-600 text-white hover:bg-rose-500",
  }[tone];

  return (
    <button
      {...props}
      className={clsx(
        "inline-flex items-center justify-center gap-2 rounded-2xl px-4 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-50",
        toneClass,
        className,
      )}
    >
      {children}
    </button>
  );
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={clsx(
        "w-full rounded-2xl border border-slate-300 bg-white/85 px-4 py-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-teal-500 focus:ring-2 focus:ring-teal-200",
        props.className,
      )}
    />
  );
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      {...props}
      className={clsx(
        "w-full rounded-2xl border border-slate-300 bg-white/85 px-4 py-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-teal-500 focus:ring-2 focus:ring-teal-200",
        props.className,
      )}
    />
  );
}

export function Label({ children, htmlFor }: PropsWithChildren<{ htmlFor?: string }>) {
  return (
    <label htmlFor={htmlFor} className="mb-2 block text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">
      {children}
    </label>
  );
}

export function SectionHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="space-y-1">
        {eyebrow ? <p className="text-xs font-semibold uppercase tracking-[0.24em] text-teal-700">{eyebrow}</p> : null}
        <h2 className="text-xl font-semibold tracking-tight text-slate-950">{title}</h2>
        {description ? <p className="max-w-2xl text-sm leading-6 text-slate-600">{description}</p> : null}
      </div>
      {action ? <div>{action}</div> : null}
    </div>
  );
}

export function Metric({
  label,
  value,
  tone = "default",
}: {
  label: string;
  value: ReactNode;
  tone?: "default" | "accent" | "warning" | "success";
}) {
  const toneClass = {
    default: "bg-white/80 border-slate-200",
    accent: "bg-teal-50/90 border-teal-200",
    warning: "bg-amber-50/90 border-amber-200",
    success: "bg-emerald-50/90 border-emerald-200",
  }[tone];

  return (
    <div className={clsx("rounded-2xl border px-4 py-3", toneClass)}>
      <div className="text-[11px] font-semibold uppercase tracking-[0.2em] text-slate-500">{label}</div>
      <div className="mt-2 text-sm font-semibold text-slate-950">{value}</div>
    </div>
  );
}

export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="rounded-3xl border border-dashed border-slate-300 bg-white/60 p-6 text-sm text-slate-600">
      <div className="font-semibold text-slate-900">{title}</div>
      {description ? <p className="mt-2 leading-6">{description}</p> : null}
    </div>
  );
}

export function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="overflow-x-auto rounded-3xl border border-slate-200 bg-slate-950 px-4 py-3 text-[12px] leading-6 text-slate-100">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}
