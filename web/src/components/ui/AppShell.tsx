import { cn } from "@/lib/cn";

/** Phone-width column. Fills the screen on mobile; centred on larger screens. */
export function AppShell({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        "mx-auto flex min-h-dvh w-full max-w-[390px] flex-col bg-bg sm:border-x sm:border-line",
        className,
      )}
    >
      {children}
    </div>
  );
}
