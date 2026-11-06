import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, setAuthToken, setTokenProvider } from "@/lib/api";
import { describeAuthError } from "@/lib/auth/errors";

describe("sign-in error wording", () => {
  it("says nothing when the person just closed the Google window", () => {
    expect(describeAuthError("auth/popup-closed-by-user")).toBeNull();
    expect(describeAuthError("auth/cancelled-popup-request")).toBeNull();
  });
  it("explains real problems in plain language", () => {
    expect(describeAuthError("auth/network-request-failed")).toMatch(/connection/i);
    expect(describeAuthError("auth/unauthorized-domain")).toMatch(/web address/i);
    expect(describeAuthError("auth/user-disabled")).toMatch(/disabled/i);
    expect(describeAuthError("auth/too-many-requests")).toMatch(/wait/i);
    expect(describeAuthError("auth/invalid-api-key")).toMatch(/configured/i);
  });
  it("never leaks a raw error code", () => {
    for (const code of ["auth/whatever", undefined, ""]) expect(describeAuthError(code)).toBe("Couldn't sign you in. Please try again.");
  });
});

describe("token provider", () => {
  const seen: Array<string | null> = [];
  beforeEach(() => {
    seen.length = 0;
    vi.stubGlobal("fetch", async (_url: string, init?: RequestInit) => {
      seen.push((init?.headers as Record<string, string>).Authorization ?? null);
      return new Response(JSON.stringify({ items: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    setAuthToken(null);
  });

  it("asks for a token on every request, so a refreshed Firebase token is used straight away", async () => {
    let n = 0;
    setTokenProvider(async () => `id-token-${++n}`);
    await api.myTrips();
    await api.myTrips();
    expect(seen).toEqual(["Bearer id-token-1", "Bearer id-token-2"]);
  });
  it("waits for an asynchronous provider", async () => {
    setTokenProvider(() => new Promise((r) => setTimeout(() => r("slow-token"), 20)));
    await api.myTrips();
    expect(seen).toEqual(["Bearer slow-token"]);
  });
  it("sends no Authorization header when signed out", async () => {
    setTokenProvider(() => null);
    await api.myTrips();
    expect(seen).toEqual([null]);
  });
  it("a fixed token still works (dev sign-in)", async () => {
    setAuthToken("dev:u_me");
    await api.myTrips();
    expect(seen).toEqual(["Bearer dev:u_me"]);
  });
});

describe("server clock", () => {
  afterEach(() => vi.unstubAllGlobals());
  it("prefers X-Server-Time (development servers) over Date, and learns the offset", async () => {
    const { serverNow } = await import("@/lib/api");
    vi.stubGlobal("fetch", async () => new Response(JSON.stringify({ items: [] }), {
      status: 200, headers: { "Content-Type": "application/json", "X-Server-Time": "2030-01-01T09:06:00+05:30", Date: "Mon, 01 Jan 2001 00:00:00 GMT" },
    }));
    await api.myTrips();
    expect(Math.abs(serverNow() - Date.parse("2030-01-01T09:06:00+05:30"))).toBeLessThan(2000);
  });
  it("falls back to the Date header", async () => {
    const { serverNow } = await import("@/lib/api");
    vi.stubGlobal("fetch", async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json", Date: "Wed, 01 Jan 2031 00:00:00 GMT" } }));
    await api.myTrips();
    expect(Math.abs(serverNow() - Date.parse("2031-01-01T00:00:00Z"))).toBeLessThan(2000);
  });
});
