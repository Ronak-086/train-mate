import type { BerthType, ConversationStatus, Post, PostCategory, PostKind, Scope } from "./api";
import { firstClause, timeAgo } from "./format";

export const KIND_LABEL: Record<PostKind, string> = { SWAP: "Seat swap", HELP: "Need help", ASK: "Question", SHARE: "Share", ALERT: "Alert" };

/** Chip and tab labels, as in the designs ("Help", not "Need help"). */
export const KIND_SHORT: Record<PostKind, string> = { SWAP: "Seat swap", HELP: "Help", ASK: "Question", SHARE: "Share", ALERT: "Alert" };

/** The four kinds the designs expose. SHARE exists in the contract but isn't offered yet. */
export const POSTABLE_KINDS: PostKind[] = ["SWAP", "HELP", "ASK", "ALERT"];

export const BERTH_LABEL: Record<BerthType, string> = {
  LOWER: "Lower berth", MIDDLE: "Middle berth", UPPER: "Upper berth", SIDE_LOWER: "Side lower berth", SIDE_UPPER: "Side upper berth", WINDOW: "Window seat",
};

export const CATEGORY_LABEL: Record<PostCategory, string> = {
  LUGGAGE: "Luggage", MEDICAL: "Medical", FOOD: "Food", TRANSLATION: "Translation", CHARGER: "Charger", OTHER: "Other",
};

export const CONV_STATUS_LABEL: Record<ConversationStatus, string> = { PENDING: "Pending", ACCEPTED: "Accepted", DECLINED: "Declined", DONE: "Done" };

/** Audience options for the "Who can see this" field. */
export function scopeOptions(kind: PostKind, coach: string): Array<{ value: Scope; label: string }> {
  const mine = { value: "COACH" as const, label: `My coach (${coach})` };
  if (kind === "SWAP" || kind === "HELP") return [mine, { value: "NEARBY", label: "Nearby coaches" }];
  if (kind === "ASK" || kind === "ALERT") return [mine, { value: "TRAIN", label: "Whole train" }];
  return [mine];
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** The grey line under a post title: "5 agree · 10 min ago". `long` gives the Home wording. */
export function postMeta(p: Post, nowMs: number, long = false): string {
  const parts: string[] = [];
  if (p.kind === "ALERT") {
    const c = p.reactions.CONFIRMED;
    if (c > 0) parts.push(long ? `${plural(c, "passenger agrees", "passengers agree")}` : `${c} agree`);
  } else if (p.kind === "SWAP") {
    const lead = firstClause(p.note) ?? (p.groupSize > 1 ? `Group of ${p.groupSize}` : null);
    if (lead) parts.push(lead);
  } else if (p.replyCount > 0) {
    parts.push(plural(p.replyCount, "reply", "replies"));
  }
  parts.push(timeAgo(p.createdAt, nowMs));
  return parts.join(" · ");
}
