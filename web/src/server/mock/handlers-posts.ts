import { POST_CATEGORIES, REACTION_TYPES } from "@/lib/api/types";
import type { BerthType, PostKind, ReactionType, Scope } from "@/lib/api/types";
import {
  ALLOWED_SCOPES, LIMITS, POST_KINDS, PUBLIC_KINDS, SCOPES, activeTrip, canSeeLive, decodeCursor, encodeCursor, hasLink,
  inScope, isBlockedBetween, isJourneyActive, isNewAccount, journeyEnd, tripOn,
} from "./access";
import { HOUR, MIN, istDate, now, startOfDay } from "./clock";
import type { Ctx } from "./ctx";
import { ApiFail, bad, conflict, forbidden, json, noContent, notFound, tooMany } from "./http";
import { removePost } from "./helpers";
import type { PostRec, Store, UserRec } from "./store";
import { bool, dateParam, int, oneOf, str } from "./v";
import { toComment, toPost, visibleComments } from "./views";

const BERTH_LABEL: Record<BerthType, string> = {
  LOWER: "Lower berth", MIDDLE: "Middle berth", UPPER: "Upper berth", SIDE_LOWER: "Side lower berth", SIDE_UPPER: "Side upper berth", WINDOW: "Window seat",
};
const lc = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);
const BERTHS = Object.keys(BERTH_LABEL) as BerthType[];

// ---- board -----------------------------------------------------------------------------

export function board(c: Ctx) {
  const s = c.store;
  let trainNo = c.query.get("train");
  let date = c.query.get("date");
  if (!trainNo || !date) {
    const t = activeTrip(s, c.me.id);
    if (!t) throw forbidden("Add your train first to see its board.", "TRIP_REQUIRED");
    trainNo = t.trainNo;
    date = t.journeyDate;
  } else {
    dateParam(date);
  }
  if (!tripOn(s, c.me.id, trainNo, date)) throw forbidden("You can only see boards for trains you're travelling on.", "NO_TRIP_ON_TRAIN");

  const kind = oneOf(Object.fromEntries(c.query), "kind", POST_KINDS);
  const scope = oneOf(Object.fromEntries(c.query), "scope", SCOPES);
  const coach = c.query.get("coach")?.toUpperCase();
  const limit = Math.min(Math.max(Number(c.query.get("limit") ?? 20) || 20, 1), 50);
  const cursor = decodeCursor(c.query.get("cursor"));
  if (c.query.get("cursor") && !cursor) throw bad("cursor is not valid.", "INVALID_CURSOR");

  const all = [...s.posts.values()]
    .filter((p) => p.trainNo === trainNo && p.journeyDate === date && canSeeLive(s, c.me.id, p))
    .filter((p) => (!kind || p.kind === kind) && (!scope || p.scope === scope) && (!coach || p.coach === coach))
    .sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? 1 : -1));

  const start = cursor ? all.findIndex((p) => p.createdAt < cursor.t || (p.createdAt === cursor.t && p.id < cursor.id)) : 0;
  const rest = start < 0 ? [] : all.slice(start);
  const page = rest.slice(0, limit);
  const next = rest.length > limit ? page[page.length - 1] : null;
  return json({ items: page.map((p) => toPost(s, p, c.me)), nextCursor: next ? encodeCursor(next.createdAt, next.id) : null });
}

// ---- create ----------------------------------------------------------------------------

function enforceLimits(s: Store, me: UserRec, tripId: string, kind: PostKind, scope: Scope) {
  const mine = [...s.posts.values()].filter((p) => p.userId === me.id);
  const last = mine.reduce((m, p) => Math.max(m, p.createdAt), 0);
  const sinceLast = (now() - last) / 1000;
  if (last && sinceLast < LIMITS.postCooldownSec) throw tooMany("Please wait a moment before posting again.", "POST_COOLDOWN", LIMITS.postCooldownSec - sinceLast);

  const dayStart = startOfDay(istDate(now()));
  if (mine.filter((p) => p.createdAt >= dayStart).length >= LIMITS.postsPerDay) {
    throw tooMany(`You can post ${LIMITS.postsPerDay} times a day. Try again tomorrow.`, "DAILY_LIMIT", (dayStart + 86_400_000 - now()) / 1000);
  }
  const isBroadcast = kind === "ALERT" || scope === "TRAIN";
  if (isBroadcast && mine.filter((p) => p.tripId === tripId && (p.kind === "ALERT" || p.scope === "TRAIN")).length >= LIMITS.broadcastsPerJourney) {
    throw tooMany(`You can send ${LIMITS.broadcastsPerJourney} alerts or train-wide posts per journey.`, "BROADCAST_LIMIT", 3600);
  }
}

export async function createPost(c: Ctx) {
  const s = c.store;
  const me = c.me;

  const idemKey = c.req.headers.get("Idempotency-Key");
  const idemId = idemKey ? `${me.id}:${idemKey}` : null;
  if (idemId) {
    const hit = s.idempotency.get(idemId);
    if (hit && now() - hit.at < 24 * HOUR) return json(hit.body, hit.status);
  }

  const b = await c.body();
  const trip = s.trips.get(str(b, "tripId", { max: 40, required: true })!);
  if (!trip || trip.userId !== me.id) throw notFound("Trip not found.", "TRIP_NOT_FOUND");
  if (!isJourneyActive(trip.trainNo, trip.journeyDate)) throw conflict("You can post once your journey day has started, and until it ends.", "JOURNEY_NOT_ACTIVE");

  const kind = oneOf(b, "kind", POST_KINDS, true)!;
  const scope = oneOf(b, "scope", SCOPES) ?? "COACH";
  if (!ALLOWED_SCOPES[kind].includes(scope)) throw bad(`${kind} posts can't use the ${scope} scope. Allowed: ${ALLOWED_SCOPES[kind].join(", ")}.`, "SCOPE_NOT_ALLOWED");
  if (isNewAccount(me) && scope !== "COACH") throw forbidden("New accounts can post to their own coach only.", "NEW_ACCOUNT_SCOPE");

  const note = str(b, "note", { max: 500 }) ?? null;
  let title = str(b, "title", { max: 80 });
  const category = oneOf(b, "category", POST_CATEGORIES) ?? null;
  const isUrgent = bool(b, "isUrgent") ?? false;
  let offeringBerth: BerthType | null = null;
  let wantsBerth: BerthType | null = null;
  let groupSize = 1;

  if (kind === "SWAP") {
    offeringBerth = oneOf(b, "offeringBerth", BERTHS, true)!;
    wantsBerth = oneOf(b, "wantsBerth", BERTHS, true)!;
    if (offeringBerth === wantsBerth) throw bad("Pick two different berth types to swap.");
    groupSize = int(b, "groupSize", { min: 1, max: 10 }) ?? 1;
    title = `${BERTH_LABEL[offeringBerth]} for ${lc(BERTH_LABEL[wantsBerth])}`;
  } else if (!title) {
    throw bad("title is required.");
  }

  if (isNewAccount(me) && hasLink(`${title} ${note ?? ""}`)) throw forbidden("New accounts can't post links yet.", "LINKS_NOT_ALLOWED");

  const hours = int(b, "expiresInHours", { min: 1, max: LIMITS.maxExpiryHours });
  const end = journeyEnd(trip.trainNo, trip.journeyDate);
  const expiresAt = Math.min(now() + (hours ?? LIMITS.maxExpiryHours) * HOUR, end);

  enforceLimits(s, me, trip.id, kind, scope);

  const id = s.nextId("post");
  const rec: PostRec = {
    id, userId: me.id, tripId: trip.id, kind, scope, category, title: title!, note, isUrgent, offeringBerth, wantsBerth, groupSize,
    status: "OPEN", trainNo: trip.trainNo, journeyDate: trip.journeyDate, coach: trip.coach, createdAt: now(), expiresAt, hidden: false,
  };
  s.posts.set(id, rec);
  const dto = toPost(s, rec, me);
  if (idemId) s.idempotency.set(idemId, { at: now(), status: 201, body: dto });
  return json(dto, 201);
}

// ---- read / update / delete ------------------------------------------------------------

/** Authors and conversation participants always see a post; others only while it is live and in scope. */
function loadVisible(c: Ctx): PostRec {
  const s = c.store;
  const p = s.posts.get(c.params.id);
  if (!p) throw notFound();
  if (p.userId === c.me.id) return p;
  const involved = [...s.convs.values()].some((cv) => cv.postId === p.id && (cv.helperId === c.me.id || cv.requesterId === c.me.id));
  if (involved) return p;
  if (isBlockedBetween(s, c.me.id, p.userId) || !inScope(s, c.me.id, p)) throw notFound();
  if (!canSeeLive(s, c.me.id, p)) throw new ApiFail(410, "POST_UNAVAILABLE", "This post is no longer available.");
  return p;
}

export function getPost(c: Ctx) {
  return json(toPost(c.store, loadVisible(c), c.me));
}

export async function patchPost(c: Ctx) {
  const p = c.store.posts.get(c.params.id);
  if (!p || p.userId !== c.me.id) throw notFound();
  const b = await c.body();
  const note = str(b, "note", { max: 500 });
  const title = str(b, "title", { max: 80 });
  const status = oneOf(b, "status", ["CLOSED"] as const);
  if (title !== undefined && p.kind === "SWAP") throw bad("A swap's title comes from its berths.");
  if (note === undefined && title === undefined && !status) throw bad("Nothing to change.");
  if (note !== undefined) p.note = note;
  if (title !== undefined) p.title = title;
  if (status && p.status === "OPEN") p.status = "CLOSED";
  return json(toPost(c.store, p, c.me));
}

export function deletePost(c: Ctx) {
  const p = c.store.posts.get(c.params.id);
  if (!p || p.userId !== c.me.id) throw notFound();
  removePost(c.store, p.id);
  return noContent();
}

// ---- comments --------------------------------------------------------------------------

export function listComments(c: Ctx) {
  const p = loadVisible(c);
  if (!PUBLIC_KINDS.includes(p.kind)) throw bad("This kind of post uses private chat, not comments.", "COMMENTS_NOT_ALLOWED");
  const after = Number(String(c.query.get("after") ?? "").replace(/^c_/, "")) || 0;
  const items = visibleComments(c.store, p.id).filter((x) => x.seq > after).map((x) => toComment(c.store, x, c.me));
  return json({ items });
}

export async function addComment(c: Ctx) {
  const s = c.store;
  const p = loadVisible(c);
  if (!PUBLIC_KINDS.includes(p.kind)) throw bad("This kind of post uses private chat, not comments.", "COMMENTS_NOT_ALLOWED");
  if (!canSeeLive(s, c.me.id, p) || p.status !== "OPEN") throw new ApiFail(410, "POST_UNAVAILABLE", "This post is closed.");
  const body = str(await c.body(), "body", { max: 500, required: true })!;
  if (isNewAccount(c.me) && hasLink(body)) throw forbidden("New accounts can't post links yet.", "LINKS_NOT_ALLOWED");

  const recent = [...s.comments.values()].filter((x) => x.userId === c.me.id && now() - x.createdAt < HOUR);
  if (recent.length >= LIMITS.commentsPerHour) {
    const oldest = Math.min(...recent.map((x) => x.createdAt));
    throw tooMany(`You can write ${LIMITS.commentsPerHour} comments an hour.`, "COMMENT_LIMIT", (oldest + HOUR - now()) / 1000);
  }
  const seq = s.nextCommentSeq();
  const rec = { seq, id: `c_${seq}`, postId: p.id, userId: c.me.id, body, createdAt: now(), hidden: false };
  s.comments.set(rec.id, rec);
  return json(toComment(s, rec, c.me), 201);
}

// ---- reactions -------------------------------------------------------------------------

export async function addReaction(c: Ctx) {
  const s = c.store;
  const p = loadVisible(c);
  if (!PUBLIC_KINDS.includes(p.kind)) throw bad("You can only react to questions, alerts and shared items.", "REACTIONS_NOT_ALLOWED");
  if (!canSeeLive(s, c.me.id, p)) throw new ApiFail(410, "POST_UNAVAILABLE", "This post is closed.");
  const type = oneOf(await c.body(), "type", REACTION_TYPES, true)! as ReactionType;
  if (!s.reactions.some((r) => r.postId === p.id && r.userId === c.me.id && r.type === type)) {
    s.reactions.push({ postId: p.id, userId: c.me.id, type });
    // Three "outdated" votes give the alert a short grace period, then it expires.
    if (type === "OUTDATED" && s.reactions.filter((r) => r.postId === p.id && r.type === "OUTDATED").length >= 3) p.expiresAt = Math.min(p.expiresAt, now() + 10 * MIN);
  }
  return json(toPost(s, p, c.me));
}

export function removeReaction(c: Ctx) {
  const s = c.store;
  const p = loadVisible(c);
  const type = oneOf(Object.fromEntries(c.query), "type", REACTION_TYPES, true)!;
  s.reactions = s.reactions.filter((r) => !(r.postId === p.id && r.userId === c.me.id && r.type === type));
  return json(toPost(s, p, c.me));
}
