import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, setAuthToken } from "@/lib/api";
import { handle } from "@/server/mock/router";
import { MINUTE, advance, freshWorld } from "./helpers";

/** Route the real client's fetch calls straight into the mock server. */
beforeEach(() => {
  freshWorld();
  setAuthToken("dev:u_me");
  vi.stubGlobal("fetch", (url: string, init?: RequestInit) => handle(new Request(`http://localhost${url}`, init)));
});
afterEach(() => vi.unstubAllGlobals());

describe("typed API client", () => {
  it("fetches typed data with the auth token attached", async () => {
    const status = await api.getStatus("12345", "2026-10-25");
    expect(status.delayLabel).toBe("10–15 min late");
    const board = await api.board({ coach: "B3" });
    expect(board.items).toHaveLength(4);
    expect(board.items[0].reactions.CONFIRMED).toBe(5);
  });

  it("sends query params and skips empty ones", async () => {
    const page = await api.board({ kind: "ALERT", cursor: undefined, limit: 1 });
    expect(page.items).toHaveLength(1);
    expect(page.nextCursor).toBeNull();
  });

  it("throws ApiError with the server's code, detail and retry time", async () => {
    advance(5 * MINUTE);
    const tripId = (await api.myTrips()).items[0].id;
    await api.createPost({ tripId, kind: "ASK", title: "First" });
    const err = await api.createPost({ tripId, kind: "ASK", title: "Second" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(ApiError.is(err, "POST_COOLDOWN")).toBe(true);
    expect(err.status).toBe(429);
    expect(err.retryAfterSeconds).toBeGreaterThan(0);
    expect(err.message).toMatch(/wait/i);
  });

  it("returns undefined for 204 responses", async () => {
    expect(await api.block("u_arun")).toBeUndefined();
    expect(await api.unblock("u_arun")).toBeUndefined();
  });

  it("reports 401 when there is no token", async () => {
    setAuthToken(null);
    expect(await api.me().catch((e) => e)).toMatchObject({ status: 401, code: "UNAUTHENTICATED" });
  });

  it("maps a dropped connection to NETWORK_ERROR", async () => {
    vi.stubGlobal("fetch", () => Promise.reject(new TypeError("fetch failed")));
    const err = await api.me().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ code: "NETWORK_ERROR", status: 0 });
    expect(err.message).toMatch(/connection/i);
  });

  it("runs the whole swap conversation end to end through the client", async () => {
    const conv = await api.respond("p_1", { message: "Lower berth in seat 7" }).catch(() => null);
    expect(conv).toBeNull(); // I own p_1, so responding must fail
    setAuthToken("dev:u_kavya");
    const offer = await api.respond("p_1", { message: "Lower berth in seat 7" });
    expect(offer.status).toBe("PENDING");
    setAuthToken("dev:u_me");
    expect((await api.patchConversation(offer.id, { status: "ACCEPTED" })).status).toBe("ACCEPTED");
    await api.sendMessage(offer.id, "Great, see you at seat 7");
    expect((await api.messages(offer.id)).items.map((m) => m.body)).toEqual(["Lower berth in seat 7", "Great, see you at seat 7"]);
  });
});
