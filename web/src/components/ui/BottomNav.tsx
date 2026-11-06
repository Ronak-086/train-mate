import Link from "next/link";
import { cn } from "@/lib/cn";

export type NavItem = { id: string; href: string; label: string };

/** Four-tab bar pinned to the bottom of the phone column. */
export function BottomNav({ items, activeId }: { items: ReadonlyArray<NavItem>; activeId: string }) {
  return (
    <nav
      aria-label="Main"
      className="sticky bottom-0 mt-auto grid h-16 grid-cols-4 border-t border-line bg-surface"
    >
      {items.map((item) => {
        const active = item.id === activeId;
        return (
          <Link
            key={item.id}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "relative flex items-center justify-center text-body",
              active ? "font-semibold text-ink" : "text-muted transition-colors hover:bg-raised hover:text-ink",
            )}
          >
            {active && <span aria-hidden className="absolute inset-x-0 -top-px h-[3px] bg-ink" />}
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
