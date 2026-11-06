import { cn } from "@/lib/cn";
import { AlertTriangleIcon } from "./icons";

type BadgeProps = {
  tone?: "default" | "alert";
  children: React.ReactNode;
  className?: string;
};

/** Outlined label for a post kind. The alert tone adds an icon, so colour is never the only signal. */
export function Badge({ tone = "default", children, className }: BadgeProps) {
  const alert = tone === "alert";
  return (
    <span
      className={cn(
        "inline-flex h-[26px] items-center gap-1.5 rounded-md border px-[11px] text-small font-semibold leading-none",
        alert ? "border-alert text-alert" : "border-ink text-ink",
        className,
      )}
    >
      {alert && <AlertTriangleIcon width={17} height={17} />}
      {children}
    </span>
  );
}
