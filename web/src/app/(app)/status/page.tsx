"use client";

import { useQuery } from "@tanstack/react-query";
import { AppShell, BackLink, Button, EmptyState, LinkButton, Notice, Skeleton } from "@/components/ui";
import { api } from "@/lib/api";
import { addMinutesHHmm, formatClock, formatDay, formatHHmm, formatRange, timeAgo } from "@/lib/format";
import { useActiveTrip, useServerNow, useStatus } from "@/lib/queries";

const capitalise = (t: string) => t.charAt(0).toUpperCase() + t.slice(1);

export default function StatusPage() {
  const { trip, isPending } = useActiveTrip();
  const status = useStatus(trip);
  const now = useServerNow();
  const route = useQuery({ queryKey: ["route", trip?.trainNo], queryFn: () => api.getRoute(trip!.trainNo), enabled: !!trip });
  const s = status.data;
  const stops = route.data?.stops ?? [];
  const lastSeq = s?.lastStation ? (stops.find((x) => x.station.code === s.lastStation!.code)?.seq ?? 0) : 0;
  const nextSeq = s?.nextStation ? (stops.find((x) => x.station.code === s.nextStation!.station.code)?.seq ?? 0) : 0;
  const delay = s?.delayMinutes ?? 0;

  return (
    <AppShell>
      <header className="flex items-center border-b border-line pb-2 pl-1 pr-5 pt-[11px]">
        <BackLink href="/home" label="Back to Home" />
      </header>
      <main className="flex-1 px-5 pb-8 pt-5">
        {isPending ? (
          <Skeleton className="h-40" />
        ) : !trip ? (
          <EmptyState title="Add your train first"><LinkButton href="/trips/new" block className="mt-5">Add my train</LinkButton></EmptyState>
        ) : (
          <>
            <h1 className="text-h1 font-bold">{trip.trainNo} {trip.trainName}</h1>
            <p className="mt-[5px] text-muted">{formatDay(trip.journeyDate)} · {trip.boardingStn.name} to {trip.destStn.name}</p>

            {status.isError ? (
              <Notice tone="error" className="mt-4" action={<Button variant="text" className="h-auto shrink-0" onClick={() => void status.refetch()}>Retry</Button>}>Couldn&apos;t load the train status.</Notice>
            ) : s ? (
              <p className="mt-4 text-small text-muted">
                {s.source === "PROVIDER" && s.fetchedAt ? `${capitalise(s.delayLabel ?? "")}. Updated ${timeAgo(s.fetchedAt, now)}.` : s.notice}
                {s.source === "PROVIDER" && s.stale ? " Updating now." : ""}
              </p>
            ) : null}

            {route.isError && <Notice tone="error" className="mt-4">Couldn&apos;t load the route.</Notice>}
            <ol className="mt-3">
              {stops.map((stop) => {
                const sched = (stop.schedArr ?? stop.schedDep)!;
                const passed = s?.source === "PROVIDER" && stop.seq <= lastSeq;
                const isNext = stop.seq === nextSeq && nextSeq > 0;
                const eta = s?.nextStation && isNext ? formatRange(s.nextStation.etaFrom, s.nextStation.etaTo) : s?.destination && stop.seq === stops.length ? formatRange(s.destination.etaFrom, s.destination.etaTo) : null;
                return (
                  <li key={stop.seq} className={`border-b border-line py-3.5 last:border-b-0 ${passed ? "text-muted" : ""}`} aria-current={isNext ? "step" : undefined}>
                    <p className={`text-title ${isNext ? "font-bold" : "font-semibold"}`}>
                      {stop.station.name}
                      {isNext && <span className="ml-2 text-body font-normal text-muted">Next</span>}
                      {passed && <span className="ml-2 text-body font-normal">Passed</span>}
                    </p>
                    <p className="mt-[3px] text-body text-muted">
                      Scheduled {formatHHmm(sched)}
                      {passed && s?.lastStation?.code === stop.station.code && s.lastSeenAt ? ` · Seen ${formatClock(s.lastSeenAt)}` : ""}
                      {!passed && s?.source === "PROVIDER" ? ` · Expected ${eta ?? formatHHmm(addMinutesHHmm(sched, delay))}` : ""}
                    </p>
                  </li>
                );
              })}
            </ol>
          </>
        )}
      </main>
    </AppShell>
  );
}
