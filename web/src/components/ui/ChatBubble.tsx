import { cn } from "@/lib/cn";

type ChatBubbleProps = {
  side: "in" | "out";
  time: string;
  children: React.ReactNode;
};

export function ChatBubble({ side, time, children }: ChatBubbleProps) {
  const mine = side === "out";
  return (
    <div className={cn("flex flex-col", mine ? "items-end" : "items-start")}>
      <p
        className={cn(
          "max-w-[82%] rounded-bubble px-3.5 py-[11.75px] text-lead",
          mine ? "bg-ink text-bg" : "bg-raised text-ink",
        )}
      >
        {children}
      </p>
      <span className="mt-1 text-caption text-muted">{time}</span>
    </div>
  );
}
