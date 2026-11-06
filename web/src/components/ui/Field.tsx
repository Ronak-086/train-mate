import { useId } from "react";
import { cn } from "@/lib/cn";
import { ChevronDownIcon } from "./icons";

const control =
  "h-[51px] w-full rounded-control border-[1.5px] border-ink bg-surface px-4 text-lead text-ink placeholder:text-faint";

type FieldProps = {
  label: string;
  children: (id: string) => React.ReactNode;
  className?: string;
};

/** Label plus a control, wired together for screen readers. */
export function Field({ label, children, className }: FieldProps) {
  const id = useId();
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-body font-medium">
        {label}
      </label>
      {children(id)}
    </div>
  );
}

export function TextInput({ className, ...rest }: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(control, className)} {...rest} />;
}

export function Select({ className, children, ...rest }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className="relative">
      <select className={cn(control, "appearance-none pr-10", className)} {...rest}>
        {children}
      </select>
      <ChevronDownIcon className="pointer-events-none absolute right-4 top-1/2 -translate-y-1/2 text-ink" />
    </div>
  );
}

export function Textarea({ className, ...rest }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn(control, "h-auto min-h-24 py-3", className)} {...rest} />;
}
