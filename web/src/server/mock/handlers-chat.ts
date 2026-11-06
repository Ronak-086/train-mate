import { canSeeLive, hasLink, isBlockedBetween, isNewAccount, inScope } from "./access";
import { now } from "./clock";
import type { Ctx } from "./ctx";
import { ApiFail, bad, conflict, forbidden, json, notFound } from "./http";
import type { ConvRec } from "./store";
import { oneOf, str } from "./v";
import { lastMessage, toConversation, toMessage } from "./views";

function loadConv(c: Ctx): ConvRec {
  const cv = c.store.convs.get(c.params.id);
  if (!cv || (cv.requesterId !== c.me.id && cv.helperId !== c.me.id)) throw notFound("Conversation not found.", "CONVERSATION_NOT_FOUND");
  return cv;
}

/** A helper offers to swap or help. The optional first message is capped at 200 characters. */
export async function respond(c: Ctx) {
  const s = c.store;
  const post = s.posts.get(c.params.id);
  if (!post || post.userId === c.me.id || !inScope(s, c.me.id, post)) throw notFound();
  if (!["SWAP", "HELP", "SHARE"].includes(post.kind)) throw bad("This kind of post uses public comments, not private offers.", "RESPOND_NOT_ALLOWED");
  if (!canSeeLive(s, c.me.id, post)) throw new ApiFail(410, "POST_UNAVAILABLE", "This post is no longer available.");
  if (isBlockedBetween(s, c.me.id, post.userId)) throw notFound();

  const existing = [...s.convs.values()].find((x) => x.postId === post.id && x.helperId === c.me.id);
  if (existing) throw conflict("You've already responded to this post.", "CONVERSATION_EXISTS", { conversationId: existing.id });

  const first = str(await c.body(), "message", { max: 200 });
  if (first && isNewAccount(c.me) && hasLink(first)) throw forbidden("New accounts can't post links yet.", "LINKS_NOT_ALLOWED");

  const id = s.nextId("conv");
  const conv: ConvRec = { id, postId: post.id, requesterId: post.userId, helperId: c.me.id, status: "PENDING", doneBy: new Set(), createdAt: now(), updatedAt: now() };
  s.convs.set(id, conv);
  if (first) s.messages.push({ id: s.nextMsgId(), convId: id, senderId: c.me.id, body: first, createdAt: now() });
  return json(toConversation(s, conv, c.me), 201);
}

export function myConversations(c: Ctx) {
  const items = [...c.store.convs.values()]
    .filter((x) => x.requesterId === c.me.id || x.helperId === c.me.id)
    .sort((a, b) => (lastMessage(c.store, b.id)?.createdAt ?? b.createdAt) - (lastMessage(c.store, a.id)?.createdAt ?? a.createdAt))
    .map((x) => toConversation(c.store, x, c.me));
  return json({ items });
}

export const getConversation = (c: Ctx) => json(toConversation(c.store, loadConv(c), c.me));

export async function patchConversation(c: Ctx) {
  const cv = loadConv(c);
  const status = oneOf(await c.body(), "status", ["ACCEPTED", "DECLINED", "DONE"] as const, true)!;
  const post = c.store.posts.get(cv.postId)!;

  if (status === "ACCEPTED" || status === "DECLINED") {
    if (cv.requesterId !== c.me.id) throw forbidden("Only the person who posted can accept or decline.", "NOT_REQUESTER");
    if (cv.status !== "PENDING") throw conflict(`This offer is already ${cv.status.toLowerCase()}.`, "INVALID_TRANSITION");
    cv.status = status;
  } else {
    if (cv.status !== "ACCEPTED" && cv.status !== "DONE") throw conflict("Accept the offer before marking it done.", "INVALID_TRANSITION");
    cv.doneBy.add(c.me.id);
    if (cv.doneBy.has(cv.requesterId) && cv.doneBy.has(cv.helperId)) {
      cv.status = "DONE";
      if (post.status === "OPEN") post.status = "MATCHED";
    }
  }
  cv.updatedAt = now();
  return json(toConversation(c.store, cv, c.me));
}

export function listMessages(c: Ctx) {
  const cv = loadConv(c);
  const after = Number(c.query.get("after") ?? 0) || 0;
  const items = c.store.messages.filter((m) => m.convId === cv.id && m.id > after).slice(0, 100).map((m) => toMessage(m, c.me));
  return json({ items });
}

export async function sendMessage(c: Ctx) {
  const cv = loadConv(c);
  const other = cv.requesterId === c.me.id ? cv.helperId : cv.requesterId;
  if (isBlockedBetween(c.store, c.me.id, other)) throw forbidden("You can't message this person.", "BLOCKED");
  if (cv.status === "PENDING" || cv.status === "DECLINED") throw conflict("Chat opens once the offer is accepted.", "CHAT_NOT_ACCEPTED");
  if (cv.status === "DONE") throw conflict("This chat is closed because the swap is done.", "CHAT_CLOSED");
  const body = str(await c.body(), "body", { max: 1000, required: true })!;
  if (isNewAccount(c.me) && hasLink(body)) throw forbidden("New accounts can't post links yet.", "LINKS_NOT_ALLOWED");
  const m = { id: c.store.nextMsgId(), convId: cv.id, senderId: c.me.id, body, createdAt: now() };
  c.store.messages.push(m);
  cv.updatedAt = now();
  return json(toMessage(m, c.me), 201);
}
