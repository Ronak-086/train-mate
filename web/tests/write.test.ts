import { beforeEach, describe, expect, it } from "vitest";
import { MINUTE, T0, advance, freshWorld, get, myTripId, patch, post } from "./helpers";

beforeEach(freshWorld);

const swap = (tripId: string, extra = {}) => ({ tripId, kind: "SWAP", offeringBerth: "MIDDLE", wantsBerth: "LOWER", ...extra });

describe("creating posts (spec 11.1)", () => {
  it("auto-titles a swap from its berths and defaults to the coach scope", async () => {
    advance(5 * MINUTE);
    const r = await post("/requests", swap(await myTripId(), { groupSize: 3, note: "Together please" }));
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ title: "Middle berth for lower berth", scope: "COACH", groupSize: 3, coach: "B3", isMine: true, status: "OPEN", replyCount: 0 });
  });

  it("expires at the earlier of 24h and the end of the journey", async () => {
    advance(5 * MINUTE);
    const r = await post("/requests", { tripId: await myTripId(), kind: "ASK", title: "Anyone know the next halt time?" });
    expect(r.body.expiresAt).toBe("2026-10-26T00:00:00+05:30"); // journey ends at midnight, before +24h
    advance(10 * MINUTE); // clear the 2-minute cooldown
    const short = await post("/requests", { tripId: await myTripId(), kind: "HELP", title: "Need a hand", expiresInHours: 2 });
    expect(short.status).toBe(201);
    expect(short.body.expiresAt).toBe("2026-10-25T11:16:00+05:30"); // 09:16 now + 2h
    expect((await post("/requests", { tripId: await myTripId(), kind: "ASK", title: "too long", expiresInHours: 48 })).status).toBe(400);
  });

  it.each([
    ["HELP", "TRAIN"], ["SWAP", "TRAIN"], ["SHARE", "TRAIN"], ["SHARE", "NEARBY"], ["ASK", "NEARBY"], ["ALERT", "NEARBY"],
  ])("rejects %s with scope %s", async (kind, scope) => {
    advance(5 * MINUTE);
    const r = await post("/requests", { tripId: await myTripId(), kind, scope, title: "t", offeringBerth: "UPPER", wantsBerth: "LOWER" });
    expect(r.status).toBe(400);
    expect(r.body.code).toBe("SCOPE_NOT_ALLOWED");
  });

  it("allows the train-wide scope for questions and alerts", async () => {
    advance(5 * MINUTE);
    const r = await post("/requests", { tripId: await myTripId(), kind: "ASK", scope: "TRAIN", title: "Is the pantry car open?" });
    expect(r.status).toBe(201);
  });

  it("validates fields", async () => {
    advance(5 * MINUTE);
    const tripId = await myTripId();
    expect((await post("/requests", { tripId, kind: "ASK" })).body.detail).toMatch(/title/);
    expect((await post("/requests", swap(tripId, { wantsBerth: "MIDDLE" }))).status).toBe(400); // same berth both ways
    expect((await post("/requests", { tripId, kind: "ASK", title: "x".repeat(81) })).status).toBe(400);
    expect((await post("/requests", { tripId, kind: "ASK", title: "ok", note: "x".repeat(501) })).status).toBe(400);
    expect((await post("/requests", { tripId, kind: "NOPE", title: "ok" })).status).toBe(400);
    expect((await post("/requests", { tripId: "t_999", kind: "ASK", title: "ok" })).body.code).toBe("TRIP_NOT_FOUND");
  });

  it("only allows posting while the journey is under way", async () => {
    const t = await post("/trips", { trainNo: "12346", journeyDate: "2026-10-27", boardingStn: "GHY", destStn: "NDLS", travelClass: "3A", coach: "B1" });
    advance(5 * MINUTE);
    const r = await post("/requests", { tripId: t.body.id, kind: "ASK", title: "Early question" });
    expect(r.body.code).toBe("JOURNEY_NOT_ACTIVE");
  });

  it("blocks new accounts from links and wider scopes", async () => {
    const { getStore } = await import("@/server/mock/store");
    getStore().users.get("u_me")!.createdAt = T0 - 60_000; // joined a minute ago
    advance(5 * MINUTE);
    const tripId = await myTripId();
    expect((await post("/requests", { tripId, kind: "ASK", title: "see www.example.com" })).body.code).toBe("LINKS_NOT_ALLOWED");
    expect((await post("/requests", { tripId, kind: "ASK", scope: "TRAIN", title: "hello" })).body.code).toBe("NEW_ACCOUNT_SCOPE");
  });
});

describe("limits", () => {
  it("enforces the 2-minute cooldown and reports when to retry", async () => {
    advance(5 * MINUTE);
    const tripId = await myTripId();
    expect((await post("/requests", { tripId, kind: "ASK", title: "first" })).status).toBe(201);
    const r = await post("/requests", { tripId, kind: "ASK", title: "second" });
    expect(r.status).toBe(429);
    expect(r.body).toMatchObject({ code: "POST_COOLDOWN" });
    expect(r.body.retryAfterSeconds).toBeGreaterThan(0);
    expect(r.body.retryAfterSeconds).toBeLessThanOrEqual(120);
    expect(r.headers.get("retry-after")).toBe(String(r.body.retryAfterSeconds));
    advance(8 * MINUTE);
    expect((await post("/requests", { tripId, kind: "ASK", title: "second" })).status).toBe(201);
  });

  it("allows 3 alerts or train-wide posts per journey, then stops", async () => {
    const tripId = await myTripId();
    for (let i = 0; i < 3; i++) {
      advance((5 + i * 3) * MINUTE);
      expect((await post("/requests", { tripId, kind: "ALERT", title: `Alert ${i}` })).status).toBe(201);
    }
    advance(20 * MINUTE);
    const r = await post("/requests", { tripId, kind: "ALERT", title: "One too many" });
    expect(r.status).toBe(429);
    expect(r.body.code).toBe("BROADCAST_LIMIT");
    expect((await post("/requests", { tripId, kind: "ASK", scope: "TRAIN", title: "Also train-wide" })).body.code).toBe("BROADCAST_LIMIT");
    expect((await post("/requests", { tripId, kind: "ASK", title: "Coach only is fine" })).status).toBe(201);
  });

  it("allows 10 posts a day", async () => {
    const tripId = await myTripId(); // already has 1 seeded post today
    for (let i = 0; i < 9; i++) {
      advance((5 + i * 3) * MINUTE);
      expect((await post("/requests", { tripId, kind: "ASK", title: `Q${i}` })).status).toBe(201);
    }
    advance(40 * MINUTE);
    const r = await post("/requests", { tripId, kind: "ASK", title: "Q11" });
    expect(r.status).toBe(429);
    expect(r.body.code).toBe("DAILY_LIMIT");
  });

  it("is idempotent with an Idempotency-Key", async () => {
    advance(5 * MINUTE);
    const tripId = await myTripId();
    const h = { "Idempotency-Key": "abc-123" };
    const a = await post("/requests", { tripId, kind: "ASK", title: "Once only" }, "u_me", h);
    const b = await post("/requests", { tripId, kind: "ASK", title: "Once only" }, "u_me", h);
    expect(b.status).toBe(201);
    expect(b.body.id).toBe(a.body.id);
    expect((await get("/board")).body.items.filter((p: { title: string }) => p.title === "Once only")).toHaveLength(1);
  });
});

describe("editing and closing", () => {
  it("only the author can edit, close or delete", async () => {
    expect((await patch("/requests/p_1", { note: "Updated" })).body.note).toBe("Updated");
    expect((await patch("/requests/p_1", { note: "Hack" }, "u_priya")).status).toBe(404);
    expect((await patch("/requests/p_1", { title: "New title" })).body.detail).toMatch(/berths/); // swap title is derived
    expect((await patch("/requests/p_1", { status: "CLOSED" })).body.status).toBe("CLOSED");
    expect((await get("/board")).body.items.map((p: { id: string }) => p.id)).not.toContain("p_1");
    expect((await get("/requests/p_1", "u_kavya")).status).toBe(410); // others get "no longer available"
    expect((await get("/requests/p_1")).status).toBe(200); // the author still can
  });
});

describe("comments and reactions", () => {
  it("lists the 3 seeded replies and lets me add one", async () => {
    expect((await get("/requests/p_4/comments")).body.items).toHaveLength(3);
    const r = await post("/requests/p_4/comments", { body: "Thanks all" });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ isMine: true, body: "Thanks all" });
    const after = (await get(`/requests/p_4/comments?after=${r.body.id}`)).body.items;
    expect(after).toHaveLength(0);
    expect((await get("/board")).body.items.find((p: { id: string }) => p.id === "p_4").replyCount).toBe(4);
  });

  it("keeps swaps and help private: no comments", async () => {
    expect((await post("/requests/p_1/comments", { body: "hi" })).body.code).toBe("COMMENTS_NOT_ALLOWED");
    expect((await post("/requests/p_2/reactions", { type: "HELPFUL" })).body.code).toBe("REACTIONS_NOT_ALLOWED");
  });

  it("limits comments to 20 an hour", async () => {
    for (let i = 0; i < 20; i++) expect((await post("/requests/p_4/comments", { body: `c${i}` })).status).toBe(201);
    const r = await post("/requests/p_4/comments", { body: "21st" });
    expect(r.status).toBe(429);
    expect(r.body.code).toBe("COMMENT_LIMIT");
    advance(61 * MINUTE);
    expect((await post("/requests/p_4/comments", { body: "later" })).status).toBe(201); // the hourly window has rolled over
  });

  it("reacts idempotently and can undo", async () => {
    const a = await post("/requests/p_3/reactions", { type: "CONFIRMED" });
    expect(a.body.reactions.CONFIRMED).toBe(6);
    expect((await post("/requests/p_3/reactions", { type: "CONFIRMED" })).body.reactions.CONFIRMED).toBe(6);
    expect(a.body.myReactions).toEqual(["CONFIRMED"]);
    const { call } = await import("./helpers");
    expect((await call("DELETE", "/requests/p_3/reactions?type=CONFIRMED")).body.reactions.CONFIRMED).toBe(5);
  });

  it("dims an alert after 3 'outdated' votes and expires it after a short grace period", async () => {
    for (const u of ["u_priya", "u_meena", "u_kavya"]) await post("/requests/p_3/reactions", { type: "OUTDATED" }, u);
    const p = (await get("/requests/p_3")).body;
    expect(p.dimmed).toBe(true);
    expect(p.highlighted).toBe(true); // 5 confirmations still count
    expect(Date.parse(p.expiresAt)).toBe(T0 + 10 * MINUTE);
    advance(11 * MINUTE);
    expect((await get("/board")).body.items.map((x: { id: string }) => x.id)).not.toContain("p_3");
  });
});
