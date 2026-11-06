import { resetClock, iso, now, setNow } from "./clock";
import type { Ctx } from "./ctx";
import { ApiFail, json, noContent, notFound, problem } from "./http";
import * as chat from "./handlers-chat";
import * as core from "./handlers-core";
import * as posts from "./handlers-posts";
import { getStore, resetStore } from "./store";
import { readBody } from "./v";

type Handler = (c: Ctx) => Response | Promise<Response>;
type Route = { method: string; re: RegExp; keys: string[]; handler: Handler; auth: "required" | "optional" | "none" };

const routes: Route[] = [];
function add(method: string, path: string, handler: Handler, auth: Route["auth"] = "required") {
  const keys: string[] = [];
  const re = new RegExp(`^${path.replace(/:([a-z]+)/g, (_, k) => (keys.push(k), "([^/]+)"))}$`);
  routes.push({ method, re, keys, handler, auth });
}

add("GET", "/health", () => json({ status: "ok", now: iso(now()) }), "none");

// trains: public, auth only sharpens staleness
add("GET", "/trains/search", core.searchTrains, "none");
add("GET", "/trains/:no/route", core.getRoute, "none");
add("GET", "/trains/:no/status", core.getStatus, "optional");

add("GET", "/trips/mine", core.myTrips);
add("POST", "/trips", core.createTrip);
add("DELETE", "/trips/:id", core.deleteTrip);

add("GET", "/feed/around-me", core.aroundYou);
add("GET", "/board", posts.board);
add("POST", "/requests", posts.createPost);
add("GET", "/requests/:id", posts.getPost);
add("PATCH", "/requests/:id", posts.patchPost);
add("DELETE", "/requests/:id", posts.deletePost);
add("GET", "/requests/:id/comments", posts.listComments);
add("POST", "/requests/:id/comments", posts.addComment);
add("POST", "/requests/:id/reactions", posts.addReaction);
add("DELETE", "/requests/:id/reactions", posts.removeReaction);
add("POST", "/requests/:id/respond", chat.respond);

add("GET", "/conversations/mine", chat.myConversations);
add("GET", "/conversations/:id", chat.getConversation);
add("PATCH", "/conversations/:id", chat.patchConversation);
add("GET", "/conversations/:id/messages", chat.listMessages);
add("POST", "/conversations/:id/messages", chat.sendMessage);

add("POST", "/reports", core.report);
add("POST", "/users/:id/block", core.block);
add("DELETE", "/users/:id/block", core.unblock);
add("POST", "/reviews", core.review);

add("GET", "/me", core.getMe);
add("PATCH", "/me", core.patchMe);
add("DELETE", "/me", core.deleteMe);

// Development helpers: reset the data, move the clock.
if (process.env.NODE_ENV !== "production" || process.env.MOCK_DEV_ROUTES === "1") {
  add("POST", "/dev/reset", () => (resetClock(), resetStore(), noContent()), "none");
  add("POST", "/dev/clock", async (c) => {
    const b = await c.body();
    setNow(typeof b.iso === "string" ? Date.parse(b.iso) : null);
    return json({ now: iso(now()) });
  }, "none");
}

/** Dev auth: "Authorization: Bearer dev:<userId>". Replaced by Firebase ID-token verification later. */
/** "Asha Kumar" becomes "Asha K.": other travellers never see a full name. */
function shortName(full: unknown): string {
  const words = typeof full === "string" ? full.trim().split(/\s+/).filter(Boolean) : [];
  if (words.length === 0) return "Traveller";
  const first = words[0].charAt(0).toUpperCase() + words[0].slice(1);
  return words.length === 1 ? first : `${first} ${words[words.length - 1].charAt(0).toUpperCase()}.`;
}

/**
 * DEVELOPMENT ONLY. Lets the built-in mock accept a real Google/Firebase sign-in so the whole app can be tried with Google
 * before the real backend exists. It checks the token's issuer, audience and expiry but does NOT verify its signature, so
 * anyone could forge one. It is off unless MOCK_TRUST_FIREBASE=1, and this mock never runs in production (the real backend
 * verifies signatures properly).
 */
function firebaseUser(token: string) {
  const project = process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID;
  if (process.env.MOCK_TRUST_FIREBASE !== "1" || !project) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  let c: Record<string, unknown>;
  try {
    c = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  } catch {
    return null;
  }
  const nowSeconds = Math.floor(Date.now() / 1000); // a real Google token, so judge it by the real clock
  const sub = c.sub;
  if (c.iss !== `https://securetoken.google.com/${project}` || c.aud !== project) return null;
  if (typeof sub !== "string" || !sub || sub.length > 128) return null;
  if (typeof c.exp !== "number" || c.exp <= nowSeconds) return null;

  const store = getStore();
  const id = `fb_${sub}`;
  let u = store.users.get(id);
  if (!u || u.deleted) {
    u = { id, displayName: shortName(c.name), email: typeof c.email === "string" ? c.email : null, trustScore: 0, createdAt: now() - 30 * 86_400_000, blocked: false, deleted: false };
    store.users.set(id, u);
  }
  return u;
}

function authenticate(req: Request) {
  const h = req.headers.get("Authorization") ?? "";
  const dev = /^Bearer dev:([A-Za-z0-9_-]+)$/.exec(h);
  if (dev) {
    const u = getStore().users.get(dev[1]);
    return u && !u.deleted ? u : null;
  }
  const jwt = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(h);
  return jwt ? firebaseUser(jwt[1]) : null;
}

async function dispatch(req: Request): Promise<Response> {
  try {
    const url = new URL(req.url);
    const path = url.pathname.replace(/^\/api/, "").replace(/\/+$/, "") || "/";
    let pathMatched = false;
    for (const r of routes) {
      const m = r.re.exec(path);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== req.method) continue;

      const me = authenticate(req);
      if (r.auth === "required" && !me) throw new ApiFail(401, "UNAUTHENTICATED", "Sign in to continue.");
      if (me?.blocked) throw new ApiFail(403, "ACCOUNT_BLOCKED", "This account has been blocked.");

      const params: Record<string, string> = {};
      r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
      let cached: Awaited<ReturnType<typeof readBody>> | undefined;
      const ctx: Ctx = {
        req, store: getStore(), params, query: url.searchParams, me: me as Ctx["me"],
        body: async () => (cached ??= await readBody(req)),
      };
      return await r.handler(ctx);
    }
    if (pathMatched) throw new ApiFail(405, "METHOD_NOT_ALLOWED", "That method isn't supported here.");
    throw notFound("There's no such endpoint.", "ENDPOINT_NOT_FOUND");
  } catch (e) {
    if (e instanceof ApiFail) return problem(e);
    console.error("[mock api] unexpected error", e);
    return problem(new ApiFail(500, "INTERNAL_ERROR", "Something went wrong on our side."));
  }
}

/** Entry point. Stamps every response with the (mock) server time in the standard Date header, so clients can work out "4 min ago" against the server's clock. */
export async function handle(req: Request): Promise<Response> {
  const res = await dispatch(req);
  res.headers.set("Date", new Date(now()).toUTCString());
  return res;
}
