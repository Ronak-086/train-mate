"use client";

import { cn } from "@/lib/cn";

export type TabItem<T extends string> = { id: T; label: string };

type TabsProps<T extends string> = {
  items: ReadonlyArray<TabItem<T>>;
  value: T;
  onChange: (id: T) => void;
  /** Accessible name for the group, e.g. "Post type". */
  label: string;
  /** Tighter spacing for filter rows. */
  compact?: boolean;
  className?: string;
};

/** Underline tabs: a hairline across the row and a thick bar under the active tab. */
export function Tabs<T extends string>({ items, value, onChange, label, compact, className }: TabsProps<T>) {
  return (
    <div role="tablist" aria-label={label} className={cn("flex border-b border-line", compact ? "gap-5" : "gap-6", className)}>
      {items.map((item) => {
        const active = item.id === value;
        return (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(item.id)}
            className={cn(
              "relative -mb-px pb-[13px] pt-3 text-tab",
              active ? "font-semibold text-ink" : "text-muted transition-colors hover:text-ink",
            )}
          >
            {item.label}
            {active && <span aria-hidden className="absolute inset-x-0 bottom-0 h-[3px] bg-ink" />}
          </button>
        );
      })}
    </div>
  );
}
