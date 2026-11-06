import type { Comment, Conversation, Message, Post, PostStatus, PublicUser, ReactionType, Trip } from "@/lib/api/types";
import { MIN, iso, now } from "./clock";
import { trainName } from "./data";
import { STATIONS } from "./data";
import { PRIVATE_KINDS } from "./access";
import type { CommentRec, ConvRec, MsgRec, PostRec, Store, TripRec, UserRec } from "./store";

export const publicUser = (s: Store, id: string): PublicUser => ({ id, displayName: s.users.get(id)?.displayName ?? "Traveller" });

export function toTrip(s: Store, t: TripRec): Trip {
  void s;
  return {
    id: t.id, trainNo: t.trainNo, trainName: trainName(t.trainNo), journeyDate: t.journeyDate,
    boardingStn: STATIONS[t.boardingStn], destStn: STATIONS[t.destStn], travelClass: t.travelClass, coach: t.coach,
    seatNo: t.seatNo, berthType: t.berthType, createdAt: iso(t.createdAt),
  };
}

export const effectiveStatus = (p: PostRec): PostStatus => (p.status === "OPEN" && p.expiresAt <= now() ? "EXPIRED" : p.status);

export function reactionCounts(s: Store, postId: string): Record<ReactionType, number> {
  const c: Record<ReactionType, number> = { CONFIRMED: 0, HELPFUL: 0, OUTDATED: 0 };
  for (const r of s.reactions) if (r.postId === postId) c[r.type]++;
  return c;
}

export const visibleComments = (s: Store, postId: string): CommentRec[] =>
  [...s.comments.values()].filter((c) => c.postId === postId && !c.hidden).sort((a, b) => a.seq - b.seq);

export function replyCount(s: Store, p: PostRec): number {
  if (PRIVATE_KINDS.includes(p.kind) && p.kind !== "SHARE") return [...s.convs.values()].filter((c) => c.postId === p.id && c.status !== "DECLINED").length;
  return visibleComments(s, p.id).length;
}

export function toPost(s: Store, p: PostRec, viewer: UserRec): Post {
  const counts = reactionCounts(s, p.id);
  const mine = s.reactions.filter((r) => r.postId === p.id && r.userId === viewer.id).map((r) => r.type);
  const myConv = [...s.convs.values()].find((c) => c.postId === p.id && c.helperId === viewer.id);
  return {
    id: p.id, kind: p.kind, scope: p.scope, category: p.category, title: p.title, note: p.note, isUrgent: p.isUrgent,
    offeringBerth: p.offeringBerth, wantsBerth: p.wantsBerth, groupSize: p.groupSize, status: effectiveStatus(p),
    trainNo: p.trainNo, journeyDate: p.journeyDate, coach: p.coach, createdAt: iso(p.createdAt), expiresAt: iso(p.expiresAt),
    author: publicUser(s, p.userId), isMine: p.userId === viewer.id, replyCount: replyCount(s, p), reactions: counts, myReactions: mine,
    highlighted: counts.CONFIRMED >= 3, dimmed: counts.OUTDATED >= 3, myConversationId: myConv?.id ?? null,
  };
}

export function toComment(s: Store, c: CommentRec, viewer: UserRec): Comment {
  return { id: c.id, requestId: c.postId, author: publicUser(s, c.userId), body: c.body, createdAt: iso(c.createdAt), isMine: c.userId === viewer.id };
}

export function lastMessage(s: Store, convId: string): MsgRec | undefined {
  let last: MsgRec | undefined;
  for (const m of s.messages) if (m.convId === convId) last = m;
  return last;
}

export function toConversation(s: Store, c: ConvRec, viewer: UserRec): Conversation {
  const post = s.posts.get(c.postId)!;
  const iAmRequester = c.requesterId === viewer.id;
  const other = iAmRequester ? c.helperId : c.requesterId;
  const lm = lastMessage(s, c.id);
  return {
    id: c.id, requestId: c.postId, requestTitle: post.title, kind: post.kind, coach: post.coach,
    requester: publicUser(s, c.requesterId), helper: publicUser(s, c.helperId), iAmRequester, status: c.status,
    myDone: c.doneBy.has(viewer.id), otherDone: c.doneBy.has(other), createdAt: iso(c.createdAt),
    lastMessage: lm ? { body: lm.body, createdAt: iso(lm.createdAt), senderId: lm.senderId } : null,
  };
}

export const toMessage = (m: MsgRec, viewer: UserRec): Message => ({
  id: m.id, conversationId: m.convId, senderId: m.senderId, body: m.body, createdAt: iso(m.createdAt), isMine: m.senderId === viewer.id,
});

export { MIN };
