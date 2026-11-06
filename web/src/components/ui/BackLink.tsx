import Link from "next/link";
import { cn } from "@/lib/cn";
import { ChevronLeftIcon } from "./icons";

type BackLinkProps = {
  href: string;
  /** Accessible name, e.g. "Back to Home". */
  label: string;
  className?: string;
};

/** Icon-only back button with a 44px touch target. */
export function BackLink({ href, label, className }: BackLinkProps) {
  return (
    <Link
      href={href}
      aria-label={label}
      className={cn("flex size-11 shrink-0 items-center justify-center rounded-control text-ink transition-colors hover:bg-raised active:bg-raised", className)}
    >
      <ChevronLeftIcon />
    </Link>
  );
}
