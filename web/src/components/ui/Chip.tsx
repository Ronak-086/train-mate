import { cn } from "@/lib/cn";

type ChipProps = Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "onChange"> & {
  selected?: boolean;
};

/** Toggle used in single-choice pickers (post kind). */
export function Chip({ selected, className, type = "button", ...rest }: ChipProps) {
  return (
    <button
      type={type}
      aria-pressed={selected}
      className={cn(
        "h-12 rounded-control border-[1.5px] border-ink px-[18px] text-body font-semibold",
        "transition-colors duration-150",
        selected ? "bg-ink text-bg enabled:hover:bg-ink-light" : "bg-surface text-ink enabled:hover:bg-raised",
        className,
      )}
      {...rest}
    />
  );
}
