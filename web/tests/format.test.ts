import { describe, expect, it } from "vitest";
import type { Post } from "@/lib/api";
import { addMinutesHHmm, firstClause, formatClock, formatDay, formatHHmm, formatRange, istDateOf, timeAgo } from "@/lib/format";
import { postMeta, scopeOptions } from "@/lib/labels";

const NOW = Date.parse("2026-10-25T09:06:00+05:30");
const ago = (min: number) => new Date(NOW - min * 60_000).toISOString();

describe("formatting (strings from the designs)", () => {
  it("formats clock times from the wall-clock digits, whatever the device timezone", () => {
    expect(formatClock("2026-10-25T09:02:00+05:30")).toBe("9:02 am");
    expect(formatClock("2026-10-25T14:15:00+05:30")).toBe("2:15 pm");
    expect(formatClock("2026-10-25T00:05:00+05:30")).toBe("12:05 am");
    expect(formatClock("2026-10-25T12:00:00+05:30")).toBe("12:00 pm");
  });
  it("collapses a window that stays inside one half of the day", () => {
    expect(formatRange("2026-10-25T09:20:00+05:30", "2026-10-25T09:35:00+05:30")).toBe("9:20–9:35 am");
    expect(formatRange("2026-10-25T14:00:00+05:30", "2026-10-25T14:15:00+05:30")).toBe("2:00–2:15 pm");
    expect(formatRange("2026-10-25T11:50:00+05:30", "2026-10-25T12:15:00+05:30")).toBe("11:50 am–12:15 pm");
  });
  it("formats dates and schedule times", () => {
    expect(formatDay("2026-10-25")).toBe("25 Oct");
    expect(formatDay("2027-01-03")).toBe("3 Jan");
    expect(formatHHmm("13:50")).toBe("1:50 pm");
    expect(addMinutesHHmm("09:10", 12)).toBe("09:22");
    expect(addMinutesHHmm("23:55", 10)).toBe("00:05");
    expect(addMinutesHHmm("00:05", -10)).toBe("23:55");
  });
  it("finds the IST date of an instant", () => {
    expect(istDateOf(Date.parse("2026-10-25T00:30:00+05:30"))).toBe("2026-10-25");
    expect(istDateOf(Date.parse("2026-10-24T20:00:00Z"))).toBe("2026-10-25"); // 01:30 IST next day
  });
  it("says how long ago, against the server clock", () => {
    expect(timeAgo(ago(0), NOW)).toBe("just now");
    expect(timeAgo(ago(4), NOW)).toBe("4 min ago");
    expect(timeAgo(ago(22), NOW)).toBe("22 min ago");
    expect(timeAgo(ago(40), NOW)).toBe("40 min ago");
    expect(timeAgo(ago(60), NOW)).toBe("1 hour ago");
    expect(timeAgo(ago(180), NOW)).toBe("3 hours ago");
    expect(timeAgo(ago(60 * 24 * 2), NOW)).toBe("2 days ago");
    expect(timeAgo(ago(-5), NOW)).toBe("just now"); // never "in the future"
  });
  it("takes the first clause of a note", () => {
    expect(firstClause("Family of 2, want to sit together")).toBe("Family of 2");
    expect(firstClause(null)).toBeNull();
    expect(firstClause(", oops")).toBeNull();
    expect(firstClause("x".repeat(60))).toHaveLength(28);
  });
});

const post = (over: Partial<Post>): Post => ({
  id: "p", kind: "ASK", scope: "COACH", category: null, title: "t", note: null, isUrgent: false, offeringBerth: null, wantsBerth: null,
  groupSize: 1, status: "OPEN", trainNo: "12345", journeyDate: "2026-10-25", coach: "B3", createdAt: ago(10), expiresAt: ago(-60),
  author: { id: "u", displayName: "A" }, isMine: false, replyCount: 0, reactions: { CONFIRMED: 0, HELPFUL: 0, OUTDATED: 0 }, myReactions: [],
  highlighted: false, dimmed: false, myConversationId: null, ...over,
});

describe("post meta line (matches Home and Board)", () => {
  it("alerts: agreement count, long on Home and short on Board", () => {
    const p = post({ kind: "ALERT", reactions: { CONFIRMED: 5, HELPFUL: 0, OUTDATED: 0 } });
    expect(postMeta(p, NOW, true)).toBe("5 passengers agree · 10 min ago");
    expect(postMeta(p, NOW)).toBe("5 agree · 10 min ago");
    expect(postMeta({ ...p, reactions: { CONFIRMED: 1, HELPFUL: 0, OUTDATED: 0 } }, NOW, true)).toBe("1 passenger agrees · 10 min ago");
    expect(postMeta({ ...p, reactions: { CONFIRMED: 0, HELPFUL: 0, OUTDATED: 0 } }, NOW)).toBe("10 min ago");
  });
  it("questions and help: reply counts", () => {
    expect(postMeta(post({ replyCount: 3, createdAt: ago(22) }), NOW)).toBe("3 replies · 22 min ago");
    expect(postMeta(post({ kind: "HELP", replyCount: 1, createdAt: ago(60) }), NOW)).toBe("1 reply · 1 hour ago");
  });
  it("swaps: the note's first clause, else the group size", () => {
    expect(postMeta(post({ kind: "SWAP", note: "Family of 2, want to sit together", groupSize: 2, createdAt: ago(40) }), NOW)).toBe("Family of 2 · 40 min ago");
    expect(postMeta(post({ kind: "SWAP", groupSize: 3, createdAt: ago(5) }), NOW)).toBe("Group of 3 · 5 min ago");
    expect(postMeta(post({ kind: "SWAP", createdAt: ago(5) }), NOW)).toBe("5 min ago");
  });
});

describe("audience options", () => {
  it("offer only the scopes the API allows for each kind", () => {
    expect(scopeOptions("SWAP", "B3").map((o) => o.value)).toEqual(["COACH", "NEARBY"]);
    expect(scopeOptions("HELP", "B3").map((o) => o.value)).toEqual(["COACH", "NEARBY"]);
    expect(scopeOptions("ASK", "B3").map((o) => o.value)).toEqual(["COACH", "TRAIN"]);
    expect(scopeOptions("ALERT", "B3").map((o) => o.value)).toEqual(["COACH", "TRAIN"]);
    expect(scopeOptions("SWAP", "B3")[0].label).toBe("My coach (B3)");
  });
});
