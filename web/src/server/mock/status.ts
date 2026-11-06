import type { StationEta, TrainStatus } from "@/lib/api/types";
import { MIN, atIst, iso, now } from "./clock";
import { routeFor } from "./data";
import type { Store } from "./store";

/** Trains the "provider" claims to track, with their current delay. Everything else is timetable-only. */
const PROVIDER_DELAYS: Record<string, number> = { "12345:2026-10-25": 12 };

const round5 = (n: number) => Math.round(n / 5) * 5;
const TTL_TRACKED_MIN = 5;
const TTL_UNTRACKED_MIN = 25;
/** Extra minutes added to the top of an arrival window to allow for stops still ahead. */
const WINDOW_SLACK_MIN = 10;

/** "10–15 min late"; "up to 20 min late" when old data makes the range reach zero; "On time" only when the delay is zero. */
function delayLabel(delay: number, low: number, high: number): string {
  if (delay > 0) return low > 0 ? `${low}–${high} min late` : `up to ${high} min late`;
  if (delay < 0) return high < 0 ? `${-high}–${-low} min early` : `up to ${-low} min early`;
  return "On time";
}

/**
 * Status for one train on one date, computed from the timetable and the mock clock.
 * Mirrors the spec's lazy-refresh behaviour: data older than its TTL is flagged stale once,
 * then treated as refreshed. The delay uncertainty uses the age of the last fetch (not the
 * time the train crossed its last station) so a train that simply hasn't reached its next
 * stop yet isn't penalised.
 */
export function computeStatus(store: Store, trainNo: string, date: string, tracked: boolean): TrainStatus {
  const stops = routeFor(trainNo)!;
  const key = `${trainNo}:${date}`;
  const t = now();
  const sched = (i: number) => atIst(date, (stops[i].schedArr ?? stops[i].schedDep)!, stops[i].dayOffset);
  const station = (i: number) => stops[i].station;
  const last = stops.length - 1;

  const base: TrainStatus = {
    trainNo, date, source: "SCHEDULE", lastStation: null, lastSeenAt: null, fetchedAt: null, delayMinutes: null,
    delayLowMin: null, delayHighMin: null, delayLabel: null, nextStation: null, destination: null, stale: false, notice: null,
  };

  const scheduleOnly = (notice: string): TrainStatus => {
    const nextIdx = stops.findIndex((_, i) => sched(i) > t);
    const eta = (i: number): StationEta => ({ station: station(i), scheduledAt: iso(sched(i)), etaFrom: iso(sched(i)), etaTo: iso(sched(i)) });
    return { ...base, notice, nextStation: nextIdx >= 0 ? eta(nextIdx) : null, destination: eta(last) };
  };

  const delay = PROVIDER_DELAYS[key];
  if (delay === undefined) return scheduleOnly("Timetable only. Live position isn't available for this train.");

  const passed = stops.map((_, i) => i).filter((i) => sched(i) + delay * MIN <= t);
  if (passed.length === 0) return scheduleOnly("This train hasn't started yet.");
  const lastIdx = passed[passed.length - 1];

  // Staleness: flagged once when the data outlives its TTL, then "refreshed".
  let fetchedAt = store.statusFetchedAt.get(key);
  if (fetchedAt === undefined) fetchedAt = t - 4 * MIN;
  const ageMin = (t - fetchedAt) / MIN;
  const stale = ageMin > (tracked ? TTL_TRACKED_MIN : TTL_UNTRACKED_MIN);
  store.statusFetchedAt.set(key, stale ? t : fetchedAt);

  const r = ageMin < 10 ? 3 : ageMin <= 30 ? 5 : 10;
  const low = round5(delay - r);
  let high = round5(delay + r);
  if (high <= low) high = low + 5;

  const eta = (i: number): StationEta => ({
    station: station(i),
    scheduledAt: iso(sched(i)),
    etaFrom: iso(sched(i) + low * MIN),
    etaTo: iso(sched(i) + (high + WINDOW_SLACK_MIN) * MIN),
  });
  const arrived = lastIdx === last;

  return {
    ...base,
    source: "PROVIDER",
    lastStation: station(lastIdx),
    lastSeenAt: iso(sched(lastIdx) + delay * MIN),
    fetchedAt: iso(fetchedAt),
    delayMinutes: delay,
    delayLowMin: low,
    delayHighMin: high,
    delayLabel: delayLabel(delay, low, high),
    nextStation: arrived ? null : eta(lastIdx + 1),
    destination: eta(last),
    stale,
    notice: arrived ? `Arrived at ${station(last).name}.` : stale ? "Updating. This may be a few minutes out of date." : null,
  };
}
