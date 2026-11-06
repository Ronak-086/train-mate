import Link from "next/link";
import { Badge } from "@/components/ui";

type PostRowProps = {
  href: string;
  label: string;
  tone?: "default" | "alert";
  title: string;
  meta: string;
  /** Alerts that most people have marked outdated. */
  dimmed?: boolean;
};

/** One post in a feed: kind badge, headline, and activity line. Rows are separated by a hairline. */
export function PostRow({ href, label, tone, title, meta, dimmed }: PostRowProps) {
  return (
    <li className="border-b border-line last:border-b-0">
      <Link href={href} className={dimmed ? "block py-4 opacity-60 transition-colors hover:bg-surface" : "block py-4 transition-colors hover:bg-surface"}>
        <Badge tone={tone}>{label}</Badge>
        <h3 className="mt-2 text-title font-semibold">{title}</h3>
        <p className="mt-[3px] text-body text-muted">{meta}</p>
      </Link>
    </li>
  );
}
