import { ApiError, networkError } from "./errors";
import type { ProblemDetails } from "./types";

/** Base path. Mock and real backend both live under /api (the real one via a Next rewrite). */
const BASE = "/api";

/** Supplies the Bearer token for each request. Firebase ID tokens expire hourly, so this is asked every time. */
type TokenProvider = () => Promise<string | null> | string | null;
let tokenProvider: TokenProvider = () => null;
/** Server clock minus device clock, learned from the Date header of every response. */
let serverOffsetMs = 0;

const clockListeners = new Set<() => void>();

/** "Now" according to the server. Use this for "4 min ago", never Date.now(). */
export const serverNow = () => Date.now() + serverOffsetMs;

/** Called whenever we learn the server's clock differs from what we assumed. */
export function subscribeServerClock(listener: () => void) {
  clockListeners.add(listener);
  return () => void clockListeners.delete(listener);
}

export function setTokenProvider(provider: TokenProvider) {
  tokenProvider = provider;
}

/** Convenience for a fixed token, e.g. the dev sign-in ("dev:<userId>"). */
export function setAuthToken(token: string | null) {
  tokenProvider = () => token;
}

type Query = Record<string, string | number | boolean | undefined | null>;

type RequestOptions = {
  query?: Query;
  body?: unknown;
  signal?: AbortSignal;
  /** Makes a retried POST safe to repeat (sent as Idempotency-Key). */
  idempotencyKey?: string;
};

function buildUrl(path: string, query?: Query) {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) if (v !== undefined && v !== null && v !== "") qs.set(k, String(v));
  const s = qs.toString();
  return `${BASE}${path}${s ? `?${s}` : ""}`;
}

async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const token = await tokenProvider();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(buildUrl(path, opts.query), {
      method,
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
      cache: "no-store",
    });
  } catch (e) {
    throw networkError(e);
  }

  // X-Server-Time is sent by development servers whose clock can be moved; real servers just send Date.
  const dateHeader = res.headers.get("x-server-time") ?? res.headers.get("date");
  if (dateHeader && !Number.isNaN(Date.parse(dateHeader))) {
    const offset = Date.parse(dateHeader) - Date.now();
    // The header has whole-second precision, so ignore sub-second jitter.
    if (Math.abs(offset - serverOffsetMs) > 1500) {
      serverOffsetMs = offset;
      clockListeners.forEach((l) => l());
    }
  }

  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    const p = (data ?? {}) as Partial<ProblemDetails>;
    throw new ApiError({
      type: p.type ?? "about:blank",
      title: p.title ?? res.statusText,
      status: res.status,
      code: p.code ?? "UNKNOWN",
      detail: p.detail ?? "Something went wrong. Please try again.",
      ...(p.retryAfterSeconds !== undefined ? { retryAfterSeconds: p.retryAfterSeconds } : {}),
      ...p,
    } as ProblemDetails);
  }
  return data as T;
}

export const http = {
  get: <T>(path: string, o?: RequestOptions) => request<T>("GET", path, o),
  post: <T>(path: string, body?: unknown, o?: RequestOptions) => request<T>("POST", path, { ...o, body: body ?? {} }),
  patch: <T>(path: string, body: unknown, o?: RequestOptions) => request<T>("PATCH", path, { ...o, body }),
  delete: <T>(path: string, o?: RequestOptions) => request<T>("DELETE", path, o),
};
