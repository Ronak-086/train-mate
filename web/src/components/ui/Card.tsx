import { cn } from "@/lib/cn";

export function Card({ className, ...rest }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("rounded-card border border-line bg-surface", className)} {...rest} />;
}

export function DashedDivider({ className }: { className?: string }) {
  return <hr className={cn("border-0 border-t border-dashed border-line-dashed", className)} />;
}
