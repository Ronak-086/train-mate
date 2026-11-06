import Link from "next/link";
import type { Trip, TrainStatus } from "@/lib/api";
import { formatClock, formatDay, formatRange, timeAgo } from "@/lib/format";
import { Card, DashedDivider } from "@/components/ui";

function Headline({ s }: { s: TrainStatus }) {
  const d = s.delayMinutes;
  if (d === null) return <p className="mt-2 text-h1 font-bold">Timetable only</p>;
  if (d > 0) return <p className="mt-[6.5px] text-display font-bold text-alert">{d} min late</p>;
  if (d < 0) return <p className="mt-[6.5px] text-display font-bold">{-d} min early</p>;
  return <p className="mt-[6.5px] text-display font-bold">On time</p>;
}

function summary(s: TrainStatus): string {
  const dest = s.destination;
  if (!dest) return "";
  if (s.source === "SCHEDULE") return `Scheduled to reach ${dest.station.name} at ${formatClock(dest.scheduledAt)}.`;
  const likely = s.delayLabel === "On time" ? "Likely on time." : `Likely ${s.delayLabel}.`;
  return `${likely} Reaches ${dest.station.name} around ${formatRange(dest.etaFrom, dest.etaTo)}.`;
}

type StatusCardProps = { trip: Trip; status: TrainStatus; nowMs: number };

/** Train status at a glance. The whole card opens the full route. */
export function StatusCard({ trip, status, nowMs }: StatusCardProps) {
  const next = status.nextStation;
  const nextTime = next ? (status.source === "SCHEDULE" ? formatClock(next.scheduledAt) : formatRange(next.etaFrom, next.etaTo)) : null;

  return (
    <Link href="/status" className="mt-2 block" aria-label={`Train ${trip.trainNo} status. Open the full route.`}>
      <Card>
        <div className="px-5 pb-4 pt-4">
          <p className="text-muted">
            {trip.trainNo} {trip.trainName} · {formatDay(trip.journeyDate)}
          </p>
          <Headline s={status} />
          <p className="mt-[3px] text-muted">{summary(status)}</p>
        </div>
        <DashedDivider />
        <div className="px-5 pb-4 pt-3.5">
          {next ? (
            <>
              <p className="text-muted">Next station</p>
              <div className="mt-px flex items-baseline justify-between gap-3">
                <p className="text-h2 font-bold">{next.station.name}</p>
                <p className="shrink-0 text-h4 font-semibold">{nextTime}</p>
              </div>
            </>
          ) : (
            <p className="text-h4 font-bold">{status.notice ?? "No more stops"}</p>
          )}
          <p className="mt-2.5 text-small text-muted">
            {status.source === "PROVIDER" && status.lastStation && status.lastSeenAt && status.fetchedAt
              ? `Last seen at ${status.lastStation.name}, ${formatClock(status.lastSeenAt)} · updated ${timeAgo(status.fetchedAt, nowMs)}`
              : (next && status.notice) || ""}
            {status.stale && status.source === "PROVIDER" ? " · updating" : ""}
          </p>
        </div>
      </Card>
    </Link>
  );
}
