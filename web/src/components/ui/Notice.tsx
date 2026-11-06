import { cn } from "@/lib/cn";

type NoticeProps = {
  tone?: "error" | "info";
  children: React.ReactNode;
  className?: string;
  /** Optional action, e.g. a Retry button. */
  action?: React.ReactNode;
};

/** Inline message. Errors are announced to screen readers; the icon-free border colour is backed by the wording. */
export function Notice({ tone = "info", children, className, action }: NoticeProps) {
  return (
    <div
      role={tone === "error" ? "alert" : "status"}
      className={cn(
        "flex items-start justify-between gap-3 rounded-control border px-3.5 py-3 text-small",
        tone === "error" ? "border-alert text-alert" : "border-line text-muted",
        className,
      )}
    >
      <p>{children}</p>
      {action}
    </div>
  );
}
