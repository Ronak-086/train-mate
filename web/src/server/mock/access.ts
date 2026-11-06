import type { PostKind, Scope } from "@/lib/api/types";
import { atIst, istDate, now, startOfDay } from "./clock";
import { routeFor } from "./data";
import type { PostRec, Store, TripRec } from "./store";

export const POST_KINDS: readonly PostKind[] = ["SWAP", "HELP", "ASK", "SHARE", "ALERT"];
export const SCOPES: readonly Scope[] = ["COACH", "NEARBY", "TRAIN"];

/** Spec 11.1: which scopes each kind may use. */
export const ALLOWED_SCOPES: Record<PostKind, readonly Scope[]> = {
  SWAP: ["COACH", "NEARBY"],
  HELP: ["COACH", "NEARBY"],
  SHARE: ["COACH"],
  ASK: ["COACH", "TRAIN"],
  ALERT: ["COACH", "TRAIN"],
};
/** Kinds that use public comments and reactions. The rest use private conversations. */
export const PUBLIC_KINDS: readonly PostKind[] = ["ASK", "ALERT", "SHARE"];
export const PRIVATE_KINDS: readonly PostKind[] = ["SWAP", "HELP", "SHARE"];

export const LIMITS = { postCooldownSec: 120, postsPerDay: 10, broadcastsPerJourney: 3, commentsPerHour: 20, maxExpiryHours: 24 } as const;

const COACH_RE = /^([A-Z]{1,2})(\d{1,2})$/;
export function coachesAdjacent(a: string, b: string): boolean {
  const x = COACH_RE.exec(a);
  const y = COACH_RE.exec(b);
  return !!x && !!y && x[1] === y[1] && Math.abs(Number(x[2]) - Number(y[2])) <= 1;
}

export const hasLink = (text: string) => /(https?:\/\/|www\.)/i.test(text);
export const isNewAccount = (u: { createdAt: number; trustScore: number }) => now() - u.createdAt < 24 * 3_600_000 || u.trustScore < 0;

export function isBlockedBetween(s: Store, a: string, b: string): boolean {
  return s.blocks.some((x) => (x.blockerId === a && x.blockedId === b) || (x.blockerId === b && x.blockedId === a));
}

export function tripOn(s: Store, userId: string, trainNo: string, date: string): TripRec | undefined {
  for (const t of s.trips.values()) if (t.userId === userId && t.trainNo === trainNo && t.journeyDate === date) return t;
  return undefined;
}

/** End of the journey (midnight after the last stop's day) as ms. */
export function journeyEnd(trainNo: string, date: string): number {
  const stops = routeFor(trainNo);
  const lastOffset = stops ? stops[stops.length - 1].dayOffset : 0;
  return startOfDay(date) + (lastOffset + 1) * 86_400_000;
}

export const journeyStart = (date: string) => atIst(date, "00:00");
export const isJourneyActive = (trainNo: string, date: string) => now() >= journeyStart(date) && now() < journeyEnd(trainNo, date);

/** A post others can still see and act on. */
export const isLive = (p: PostRec) => !p.hidden && p.status === "OPEN" && p.expiresAt > now();

/** Spec 11.1 visibility, ignoring liveness: same train and date, and the scope covers the viewer's coach. */
export function inScope(s: Store, viewerId: string, p: PostRec): boolean {
  const trip = tripOn(s, viewerId, p.trainNo, p.journeyDate);
  if (!trip) return false;
  if (p.scope === "TRAIN") return true;
  if (p.scope === "COACH") return trip.coach === p.coach;
  return coachesAdjacent(trip.coach, p.coach);
}

/** Live and visible to this person. Authors see their own live posts; expired or closed posts are not "live" for anyone. */
export function canSeeLive(s: Store, viewerId: string, p: PostRec): boolean {
  if (!isLive(p)) return false;
  if (p.userId === viewerId) return true;
  return !isBlockedBetween(s, viewerId, p.userId) && inScope(s, viewerId, p);
}

/** The trip a signed-in user is "on" right now: today's, else the nearest by date. */
export function activeTrip(s: Store, userId: string): TripRec | undefined {
  const today = startOfDay(istDate(now()));
  let best: TripRec | undefined;
  let bestGap = Infinity;
  for (const t of s.trips.values()) {
    if (t.userId !== userId) continue;
    const gap = Math.abs(startOfDay(t.journeyDate) - today);
    if (gap < bestGap || (gap === bestGap && best && t.journeyDate > best.journeyDate)) {
      best = t;
      bestGap = gap;
    }
  }
  return best;
}

/** Cursor = base64url("<createdAtMs>:<id>"), newest first. */
export const encodeCursor = (createdAt: number, id: string) => Buffer.from(`${createdAt}:${id}`).toString("base64url");
export function decodeCursor(c: string | null): { t: number; id: string } | null {
  if (!c) return null;
  try {
    const [t, ...rest] = Buffer.from(c, "base64url").toString().split(":");
    const n = Number(t);
    return Number.isFinite(n) && rest.length ? { t: n, id: rest.join(":") } : null;
  } catch {
    return null;
  }
}
