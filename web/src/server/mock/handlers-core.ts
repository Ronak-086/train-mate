import { BERTH_TYPES, TRAVEL_CLASSES } from "@/lib/api/types";
import { activeTripOrNull, removePost } from "./helpers";
import { canSeeLive, hasLink, isBlockedBetween, tripOn } from "./access";
import { addDays, istDate, now } from "./clock";
import { allTrains, routeFor } from "./data";
import type { Ctx } from "./ctx";
import { bad, conflict, json, noContent, notFound } from "./http";
import { hashPnr } from "./store";
import { computeStatus } from "./status";
import { dateParam, int, oneOf, str } from "./v";
import { toPost, toTrip } from "./views";

// ---- trains (public) -------------------------------------------------------------------

export function searchTrains(c: Ctx) {
  const q = (c.query.get("q") ?? "").trim().toLowerCase();
  if (q.length < 2) throw bad("Type at least 2 characters to search.");
  const items = allTrains().filter((t) => t.trainNo.startsWith(q) || t.name.toLowerCase().includes(q) || `${t.trainNo} ${t.name}`.toLowerCase().includes(q)).slice(0, 10);
  return json({ items });
}

export function getRoute(c: Ctx) {
  const stops = routeFor(c.params.no);
  if (!stops) throw notFound("We don't have that train.", "TRAIN_NOT_FOUND");
  return json({ trainNo: c.params.no, stops });
}

export function getStatus(c: Ctx) {
  if (!routeFor(c.params.no)) throw notFound("We don't have that train.", "TRAIN_NOT_FOUND");
  const date = dateParam(c.query.get("date"));
  const tracked = !!c.me && !!tripOn(c.store, c.me.id, c.params.no, date);
  return json(computeStatus(c.store, c.params.no, date, tracked));
}

// ---- trips -----------------------------------------------------------------------------

export function myTrips(c: Ctx) {
  const items = [...c.store.trips.values()].filter((t) => t.userId === c.me.id).sort((a, b) => (a.journeyDate < b.journeyDate ? 1 : -1));
  return json({ items: items.map((t) => toTrip(c.store, t)) });
}

export async function createTrip(c: Ctx) {
  const b = await c.body();
  const s = c.store;
  const trainNo = str(b, "trainNo", { max: 5, required: true })!;
  const stops = routeFor(trainNo);
  if (!stops) throw notFound("We don't have that train.", "TRAIN_NOT_FOUND");
  const journeyDate = dateParam(typeof b.journeyDate === "string" ? b.journeyDate : null, "journeyDate");
  const today = istDate(now());
  if (journeyDate < addDays(today, -1) || journeyDate > addDays(today, 120)) throw bad("journeyDate must be from yesterday to 120 days ahead.");

  const boarding = str(b, "boardingStn", { max: 6, required: true })!;
  const dest = str(b, "destStn", { max: 6, required: true })!;
  const bi = stops.findIndex((x) => x.station.code === boarding);
  const di = stops.findIndex((x) => x.station.code === dest);
  if (bi < 0 || di < 0) throw bad("Both stations must be stops on this train's route.", "STATION_NOT_ON_ROUTE");
  if (bi >= di) throw bad("Your destination must come after your boarding station.", "STATION_ORDER");

  const travelClass = oneOf(b, "travelClass", TRAVEL_CLASSES, true)!;
  const coach = str(b, "coach", { max: 3, required: true })!.toUpperCase();
  if (!/^[A-Z]{1,2}\d{1,2}$/.test(coach)) throw bad("Coach looks like B3, S5 or A1.");
  const seatNo = int(b, "seatNo", { min: 1, max: 200 });
  const berthType = oneOf(b, "berthType", BERTH_TYPES);
  const pnr = str(b, "pnr", { max: 10, min: 10 });
  if (pnr !== undefined && !/^\d{10}$/.test(pnr)) throw bad("PNR is 10 digits.");

  if (tripOn(s, c.me.id, trainNo, journeyDate)) throw conflict("You've already added this train for that date.", "TRIP_EXISTS");

  const id = s.nextId("trip");
  const rec = { id, userId: c.me.id, trainNo, journeyDate, boardingStn: boarding, destStn: dest, travelClass, coach, seatNo: seatNo ?? null, berthType: berthType ?? null, pnrHash: pnr ? hashPnr(pnr) : null, createdAt: now() };
  s.trips.set(id, rec);
  return json(toTrip(s, rec), 201);
}

export function deleteTrip(c: Ctx) {
  const t = c.store.trips.get(c.params.id);
  if (!t || t.userId !== c.me.id) throw notFound("Trip not found.", "TRIP_NOT_FOUND");
  for (const p of [...c.store.posts.values()]) if (p.tripId === t.id) removePost(c.store, p.id);
  c.store.trips.delete(t.id);
  return noContent();
}

// ---- feed ------------------------------------------------------------------------------

export function aroundYou(c: Ctx) {
  const s = c.store;
  const trip = activeTripOrNull(s, c.me.id);
  if (!trip) return json({ trip: null, items: [] });
  const limit = Math.min(Math.max(Number(c.query.get("limit") ?? 5) || 5, 1), 20);
  const items = [...s.posts.values()]
    .filter((p) => p.trainNo === trip.trainNo && p.journeyDate === trip.journeyDate && p.userId !== c.me.id)
    .filter((p) => canSeeLive(s, c.me.id, p))
    .sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? 1 : -1))
    .slice(0, limit)
    .map((p) => toPost(s, p, c.me));
  return json({ trip: toTrip(s, trip), items });
}


// ---- account ---------------------------------------------------------------------------

const meDto = (c: Ctx) => ({ id: c.me.id, displayName: c.me.displayName, email: c.me.email, trustScore: c.me.trustScore, createdAt: new Date(c.me.createdAt).toISOString() });

export const getMe = (c: Ctx) => json(meDto(c));

export async function patchMe(c: Ctx) {
  const b = await c.body();
  const name = str(b, "displayName", { max: 40 });
  if (name === undefined) throw bad("displayName is required.");
  if (hasLink(name)) throw bad("Names can't contain links.", "LINKS_NOT_ALLOWED");
  c.me.displayName = name;
  return json(meDto(c));
}

export function deleteMe(c: Ctx) {
  const s = c.store;
  const id = c.me.id;
  for (const p of [...s.posts.values()]) if (p.userId === id) removePost(s, p.id);
  for (const t of [...s.trips.values()]) if (t.userId === id) s.trips.delete(t.id);
  for (const cm of [...s.comments.values()]) if (cm.userId === id) s.comments.delete(cm.id);
  s.reactions = s.reactions.filter((r) => r.userId !== id);
  for (const cv of [...s.convs.values()]) if (cv.requesterId === id || cv.helperId === id) {
    s.convs.delete(cv.id);
    s.messages = s.messages.filter((m) => m.convId !== cv.id);
  }
  c.me.deleted = true;
  return noContent();
}

// ---- trust & safety --------------------------------------------------------------------

export async function report(c: Ctx) {
  const b = await c.body();
  const s = c.store;
  const targetUserId = str(b, "targetUserId", { max: 40, required: true })!;
  const reason = str(b, "reason", { max: 300, required: true })!;
  const postId = str(b, "requestId", { max: 40 }) ?? null;
  const commentId = str(b, "commentId", { max: 40 }) ?? null;
  if (targetUserId === c.me.id) throw bad("You can't report yourself.");
  if (!s.users.has(targetUserId)) throw notFound("That person doesn't exist.", "USER_NOT_FOUND");
  const reportedPost = postId ? s.posts.get(postId) : undefined;
  const reportedComment = commentId ? s.comments.get(commentId) : undefined;
  if (postId && !reportedPost) throw notFound("That post doesn't exist.");
  if (commentId && !reportedComment) throw notFound("That comment doesn't exist.");
  // The reported person must be the author of what is being reported.
  const author = reportedComment?.userId ?? reportedPost?.userId;
  if (author && author !== targetUserId) throw bad("targetUserId must be the author of the reported post or comment.", "TARGET_MISMATCH");

  const dup = s.reports.some((r) => r.reporterId === c.me.id && r.postId === postId && r.commentId === commentId && r.targetUserId === targetUserId);
  if (!dup) s.reports.push({ reporterId: c.me.id, targetUserId, postId, commentId, reason, createdAt: now() });

  // 3 distinct reporters hide the content for review.
  const distinct = (pred: (r: (typeof s.reports)[number]) => boolean) => new Set(s.reports.filter(pred).map((r) => r.reporterId)).size;
  if (commentId && distinct((r) => r.commentId === commentId) >= 3) s.comments.get(commentId)!.hidden = true;
  else if (postId && !commentId && distinct((r) => r.postId === postId && !r.commentId) >= 3) s.posts.get(postId)!.hidden = true;
  return noContent();
}

export function block(c: Ctx) {
  const id = c.params.id;
  if (id === c.me.id) throw bad("You can't block yourself.");
  if (!c.store.users.has(id)) throw notFound("That person doesn't exist.", "USER_NOT_FOUND");
  if (!isBlockedBetween(c.store, c.me.id, id) || !c.store.blocks.some((x) => x.blockerId === c.me.id && x.blockedId === id)) c.store.blocks.push({ blockerId: c.me.id, blockedId: id });
  return noContent();
}

export function unblock(c: Ctx) {
  c.store.blocks = c.store.blocks.filter((x) => !(x.blockerId === c.me.id && x.blockedId === c.params.id));
  return noContent();
}

export async function review(c: Ctx) {
  const b = await c.body();
  const s = c.store;
  const convId = str(b, "conversationId", { max: 40, required: true })!;
  const rating = int(b, "rating", { min: 1, max: 5, required: true })!;
  const conv = s.convs.get(convId);
  if (!conv || (conv.requesterId !== c.me.id && conv.helperId !== c.me.id)) throw notFound("Conversation not found.");
  if (conv.status !== "DONE") throw conflict("You can rate once the swap or help is marked done.", "NOT_DONE");
  if (s.reviews.some((r) => r.convId === convId && r.reviewerId === c.me.id)) throw conflict("You've already rated this.", "ALREADY_REVIEWED");
  s.reviews.push({ convId, reviewerId: c.me.id, rating });
  const other = s.users.get(conv.requesterId === c.me.id ? conv.helperId : conv.requesterId);
  if (other) other.trustScore += rating >= 4 ? 2 : rating <= 2 ? -3 : 0;
  return noContent();
}
