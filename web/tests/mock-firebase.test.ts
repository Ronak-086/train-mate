import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { call, freshWorld, get } from "./helpers";

const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
const PROJECT = "demo-project";

/** A Google-shaped token with a made-up signature. The mock's trust mode doesn't check signatures (that is why it is dev-only). */
function token(over: Record<string, unknown> = {}) {
  const claims = {
    iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, sub: "google-uid-1", name: "Asha Kumar", email: "asha@gmail.com",
    exp: Math.floor(Date.now() / 1000) + 3600, iat: Math.floor(Date.now() / 1000) - 5, ...over,
  };
  return `${b64({ alg: "RS256", kid: "x" })}.${b64(claims)}.c2lnbmF0dXJl`;
}

const me = (t: string) => call("GET", "/me", { as: null, headers: { Authorization: `Bearer ${t}` } });

beforeEach(() => {
  freshWorld();
  process.env.MOCK_TRUST_FIREBASE = "1";
  process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID = PROJECT;
});
afterEach(() => {
  delete process.env.MOCK_TRUST_FIREBASE;
  delete process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID;
});

describe("mock API: opt-in Google sign-in for development", () => {
  it("is off unless MOCK_TRUST_FIREBASE=1", async () => {
    delete process.env.MOCK_TRUST_FIREBASE;
    expect((await me(token())).status).toBe(401);
  });

  it("is off without a Firebase project id", async () => {
    delete process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID;
    expect((await me(token())).status).toBe(401);
  });

  it("signs a Google user in, with a first name and initial, and the same person next time", async () => {
    const first = await me(token());
    expect(first.status).toBe(200);
    expect(first.body.displayName).toBe("Asha K.");
    expect(first.body.email).toBe("asha@gmail.com");
    const again = await me(token({ name: "Totally Different" }));
    expect(again.body.id).toBe(first.body.id);
    expect(again.body.displayName).toBe("Asha K.");
  });

  it("starts a new Google user with no trip, so the app invites them to add one", async () => {
    const t = token();
    const auth = { as: null, headers: { Authorization: `Bearer ${t}` } };
    expect((await call("GET", "/trips/mine", auth)).body.items).toEqual([]);
    expect((await call("GET", "/feed/around-me", auth)).body.trip).toBeNull();
  });

  it("rejects the wrong audience, issuer, expiry or subject", async () => {
    for (const bad of [{ aud: "other" }, { iss: "https://securetoken.google.com/other" }, { exp: Math.floor(Date.now() / 1000) - 1 }, { sub: "" }, { sub: undefined }, { exp: "soon" }]) {
      expect((await me(token(bad))).status, JSON.stringify(bad)).toBe(401);
    }
  });

  it("rejects malformed tokens", async () => {
    for (const junk of ["garbage", "a.b", "a.b.c", "!!!.@@@.###", `${b64({})}.${Buffer.from("not json").toString("base64url")}.c2ln`]) {
      expect((await me(junk)).status, junk).toBe(401);
    }
  });

  it("leaves the dev picker working", async () => {
    expect((await get("/me", "u_me")).body.displayName).toBe("Asha K.");
  });

  it("lets a Google user who deleted their account sign in again, as a fresh person", async () => {
    const t = token();
    const id = (await me(t)).body.id;
    expect((await call("DELETE", "/me", { as: null, headers: { Authorization: `Bearer ${t}` } })).status).toBe(204);
    const back = await me(t);
    expect(back.status).toBe(200);
    expect(back.body.id).toBe(id);
  });
});
