import { beforeEach, describe, expect, it } from "vitest";
import { MINUTE, T0, advance, call, del, freshWorld, get, post } from "./helpers";

beforeEach(freshWorld);

describe("auth and errors", () => {
  it("rejects calls without a token, with a problem+json body", async () => {
    const r = await get("/me", null);
    expect(r.status).toBe(401);
    expect(r.headers.get("content-type")).toContain("application/problem+json");
    expect(r.body).toMatchObject({ status: 401, code: "UNAUTHENTICATED", title: expect.any(String), detail: expect.any(String), type: expect.any(String) });
  });
  it("rejects an unknown dev user", async () => {
    expect((await get("/me", "u_nobody")).status).toBe(401);
  });
  it("health and train search are public", async () => {
    expect((await get("/health", null)).status).toBe(200);
    expect((await get("/trains/search?q=12345", null)).status).toBe(200);
  });
  it("404 for unknown endpoints and 405 for wrong methods", async () => {
    expect((await get("/nope")).body.code).toBe("ENDPOINT_NOT_FOUND");
    expect((await call("PUT", "/me")).status).toBe(405);
  });
  it("400 INVALID_JSON for a malformed body", async () => {
    const res = await (await import("@/server/mock/router")).handle(
      new Request("http://localhost/api/trips", { method: "POST", headers: { Authorization: "Bearer dev:u_me", "Content-Type": "application/json" }, body: "{oops" }),
    );
    expect(res.status).toBe(400);
    expect((await res.json()).code).toBe("INVALID_JSON");
  });
});

describe("trains", () => {
  it("searches by number and by name, and needs 2+ characters", async () => {
    expect((await get("/trains/search?q=12345", null)).body.items).toHaveLength(1);
    expect((await get("/trains/search?q=sample", null)).body.items).toHaveLength(2);
    expect((await get("/trains/search?q=x", null)).status).toBe(400);
  });
  it("returns a route, or 404 for an unknown train", async () => {
    expect((await get("/trains/12345/route", null)).body.stops).toHaveLength(5);
    expect((await get("/trains/99999/route", null)).body.code).toBe("TRAIN_NOT_FOUND");
  });
});

describe("train status (matches the Home design)", () => {
  it("reports 12 min late, 10–15 range, Nagaon, next Lumding 9:20–9:35, Delhi 2:00–2:15", async () => {
    const s = (await get("/trains/12345/status?date=2026-10-25", "u_me")).body;
    expect(s).toMatchObject({
      source: "PROVIDER", delayMinutes: 12, delayLabel: "10–15 min late", stale: false,
      lastStation: { code: "NGC", name: "Nagaon" }, lastSeenAt: "2026-10-25T08:42:00+05:30", fetchedAt: "2026-10-25T09:02:00+05:30",
    });
    expect(s.nextStation).toMatchObject({ station: { name: "Lumding Jn" }, etaFrom: "2026-10-25T09:20:00+05:30", etaTo: "2026-10-25T09:35:00+05:30" });
    expect(s.destination).toMatchObject({ station: { name: "New Delhi" }, etaFrom: "2026-10-25T14:00:00+05:30", etaTo: "2026-10-25T14:15:00+05:30" });
  });
  it("moves on as the clock moves, and flags stale data once", async () => {
    advance(20 * MINUTE); // 09:26, past Lumding (9:10 + 12 = 9:22)
    const s = (await get("/trains/12345/status?date=2026-10-25", "u_me")).body;
    expect(s.lastStation.name).toBe("Lumding Jn");
    expect(s.nextStation.station.code).toBe("NJP");
    expect(s.stale).toBe(true); // last fetched 24 min ago, limit 5 for a tracked trip
    expect((await get("/trains/12345/status?date=2026-10-25", "u_me")).body.stale).toBe(false);
  });
  it("is timetable-only for trains it doesn't track, and says so", async () => {
    const s = (await get("/trains/12346/status?date=2026-10-25", null)).body;
    expect(s).toMatchObject({ source: "SCHEDULE", delayMinutes: null, lastStation: null });
    expect(s.notice).toMatch(/timetable only/i);
  });
  it("marks the train arrived at the end of the route", async () => {
    advance(6 * 3_600_000);
    const s = (await get("/trains/12345/status?date=2026-10-25", "u_me")).body;
    expect(s.nextStation).toBeNull();
    expect(s.notice).toMatch(/arrived/i);
  });
  it("validates the date", async () => {
    expect((await get("/trains/12345/status?date=tomorrow", null)).status).toBe(400);
  });
});

describe("trips", () => {
  const body = { trainNo: "12346", journeyDate: "2026-10-25", boardingStn: "GHY", destStn: "NDLS", travelClass: "3A", coach: "b2", seatNo: 5, berthType: "LOWER", pnr: "1234567890" };
  it("creates a trip, upper-cases the coach and never returns the PNR", async () => {
    const r = await post("/trips", body);
    expect(r.status).toBe(201);
    expect(r.body.coach).toBe("B2");
    expect(JSON.stringify(r.body)).not.toMatch(/1234567890|pnr/i);
  });
  it("rejects duplicates, bad order, bad coach, bad PNR and far-future dates", async () => {
    expect((await post("/trips", { ...body, trainNo: "12345" })).body.code).toBe("TRIP_EXISTS");
    expect((await post("/trips", { ...body, boardingStn: "NDLS", destStn: "GHY" })).body.code).toBe("STATION_ORDER");
    expect((await post("/trips", { ...body, coach: "BOGIE" })).status).toBe(400);
    expect((await post("/trips", { ...body, pnr: "12" })).status).toBe(400);
    expect((await post("/trips", { ...body, journeyDate: "2027-06-01" })).status).toBe(400);
    expect((await post("/trips", { ...body, trainNo: "00000" })).body.code).toBe("TRAIN_NOT_FOUND");
  });
  it("deleting a trip removes its posts", async () => {
    const tripId = (await get("/trips/mine")).body.items[0].id;
    expect((await del(`/trips/${tripId}`)).status).toBe(204);
    expect((await get("/requests/p_1")).status).toBe(404);
  });
});

describe("around you and the board (match the Home and Board designs)", () => {
  it("Home: the coach's alert and question, newest first, never my own post", async () => {
    const r = (await get("/feed/around-me")).body;
    expect(r.trip.coach).toBe("B3");
    expect(r.items.map((p: { title: string }) => p.title)).toEqual(["AC is weak in B3", "Which platform at the next stop?", "Help with luggage at Lumding"]);
  });
  it("Board: all four posts with the design's counts", async () => {
    const items = (await get("/board")).body.items;
    expect(items.map((p: { title: string }) => p.title)).toEqual([
      "AC is weak in B3", "Which platform at the next stop?", "Upper berth for lower berth", "Help with luggage at Lumding",
    ]);
    const by = Object.fromEntries(items.map((p: { kind: string }) => [p.kind, p]));
    expect(by.ALERT.reactions.CONFIRMED).toBe(5);
    expect(by.ALERT.highlighted).toBe(true);
    expect(by.ASK.replyCount).toBe(3);
    expect(by.HELP.replyCount).toBe(1);
    expect(by.SWAP).toMatchObject({ replyCount: 1, isMine: true, groupSize: 2, note: "Family of 2, want to sit together", myConversationId: null });
  });
  it("filters by kind, coach and scope", async () => {
    expect((await get("/board?kind=ALERT")).body.items).toHaveLength(1);
    expect((await get("/board?coach=B3")).body.items).toHaveLength(4);
    expect((await get("/board?coach=B9")).body.items).toHaveLength(0);
    expect((await get("/board?scope=TRAIN")).body.items).toHaveLength(0);
    expect((await get("/board?kind=BOGUS")).status).toBe(400);
  });
  it("paginates with an opaque cursor, without gaps or repeats", async () => {
    const p1 = (await get("/board?limit=3")).body;
    expect(p1.items).toHaveLength(3);
    expect(p1.nextCursor).toEqual(expect.any(String));
    const p2 = (await get(`/board?limit=3&cursor=${p1.nextCursor}`)).body;
    expect(p2.items).toHaveLength(1);
    expect(p2.nextCursor).toBeNull();
    const ids = [...p1.items, ...p2.items].map((p: { id: string }) => p.id);
    expect(new Set(ids).size).toBe(4);
    expect((await get("/board?cursor=%%%")).body.code).toBe("INVALID_CURSOR");
  });
  it("hides coach-scoped posts from other coaches", async () => {
    expect((await get("/board", "u_rahul")).body.items).toHaveLength(0); // B4
    expect((await get("/board", "u_sana")).body.items).toHaveLength(0); // B6
    expect((await get("/requests/p_3", "u_rahul")).status).toBe(404);
  });
  it("shows NEARBY posts to adjacent coaches only", async () => {
    const t = (await get("/trips/mine", "u_rahul")).body.items[0].id;
    const made = await post("/requests", { tripId: t, kind: "HELP", scope: "NEARBY", title: "Need a hand" }, "u_rahul");
    expect(made.status).toBe(201);
    expect((await get("/board")).body.items.map((p: { title: string }) => p.title)).toContain("Need a hand"); // me in B3
    expect((await get("/board", "u_sana")).body.items).toHaveLength(0); // B6
  });
  it("requires a trip on that train", async () => {
    const t = (await get("/trips/mine", "u_sana")).body.items[0].id;
    await del(`/trips/${t}`, "u_sana");
    expect((await get("/board", "u_sana")).body.code).toBe("TRIP_REQUIRED");
    expect((await get("/board?train=12346&date=2026-10-25")).body.code).toBe("NO_TRIP_ON_TRAIN");
  });
  it("expired posts disappear", async () => {
    advance(25 * 3_600_000);
    expect((await get("/board")).body.items).toHaveLength(0);
  });
});

describe("account", () => {
  it("reads and renames me, rejecting links in names", async () => {
    expect((await get("/me")).body.displayName).toBe("Asha K.");
    expect((await (await import("./helpers")).patch("/me", { displayName: "Asha" })).body.displayName).toBe("Asha");
    expect((await (await import("./helpers")).patch("/me", { displayName: "see www.x.com" })).body.code).toBe("LINKS_NOT_ALLOWED");
  });
  it("deleting the account removes it and its data", async () => {
    expect((await del("/me")).status).toBe(204);
    expect((await get("/me")).status).toBe(401);
    expect((await get("/board", "u_priya")).body.items.map((p: { title: string }) => p.title)).not.toContain("Upper berth for lower berth");
  });
});

void T0;

describe("delay wording when data is old", () => {
  it("never calls a late train 'on time' just because the range is wide", async () => {
    // 12 min late, but the last fetch was over 30 minutes ago: the range reaches zero.
    advance(40 * MINUTE);
    await get("/trains/12345/status?date=2026-10-25", "u_me"); // flags stale and "refreshes"
    const { getStore } = await import("@/server/mock/store");
    getStore().statusFetchedAt.set("12345:2026-10-25", T0 - 60 * MINUTE + 40 * MINUTE - 31 * MINUTE);
    const s = (await get("/trains/12345/status?date=2026-10-25", "u_me")).body;
    expect(s.delayMinutes).toBe(12);
    expect(s.delayLabel).toBe("up to 20 min late");
  });
});
