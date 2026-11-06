import Link from "next/link";
import { cn } from "@/lib/cn";

type Variant = "primary" | "outline" | "text";

export function buttonClass(variant: Variant = "primary", block = false) {
  return cn(
    "inline-flex items-center justify-center font-semibold transition-colors duration-150 disabled:opacity-40",
    variant === "primary" && "h-14 rounded-control bg-ink px-6 text-lead text-bg enabled:hover:bg-ink-light active:opacity-85",
    variant === "outline" && "h-11 rounded-control border-[1.5px] border-ink bg-surface px-4 text-body text-ink enabled:hover:bg-raised active:bg-raised",
    variant === "text" && "h-11 px-2 text-body text-muted underline underline-offset-2 enabled:hover:text-ink active:text-ink",
    block && "w-full",
  );
}

type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  /** Stretch to the full width of the container. */
  block?: boolean;
};

export function Button({ variant = "primary", block, className, type = "button", ...rest }: ButtonProps) {
  return <button type={type} className={cn(buttonClass(variant, block), className)} {...rest} />;
}

type LinkButtonProps = Omit<React.ComponentProps<typeof Link>, "className"> & { variant?: Variant; block?: boolean; className?: string };

/** A link that looks like a button. */
export function LinkButton({ variant = "primary", block, className, ...rest }: LinkButtonProps) {
  // Links are never :disabled, so the hover shades are applied directly.
  const hover = variant === "primary" ? "hover:bg-ink-light" : variant === "outline" ? "hover:bg-raised" : "hover:text-ink";
  return <Link className={cn(buttonClass(variant, block), hover, className)} {...rest} />;
}
