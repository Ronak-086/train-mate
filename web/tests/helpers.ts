import { resetClock, setNow } from "@/server/mock/clock";
import { handle } from "@/server/mock/router";
import { resetStore } from "@/server/mock/store";

/** 09:06 IST on 25 Oct 2026: the moment the supplied designs depict. */
export const T0 = Date.parse("2026-10-25T09:06:00+05:30");

export function freshWorld() {
  resetClock();
  setNow(T0);
  resetStore();
}
export const advance = (ms: number, from = T0) => setNow(from + ms);
export const MINUTE = 60_000;

export type Res<T = any> = { status: number; body: T; headers: Headers }; // eslint-disable-line @typescript-eslint/no-explicit-any

/** Call the API in-process, exactly as the HTTP route would. */
export async function call<T = any>( // eslint-disable-line @typescript-eslint/no-explicit-any
  method: string, path: string, opts: { as?: string | null; body?: unknown; headers?: Record<string, string> } = {},
): Promise<Res<T>> {
  const headers: Record<string, string> = { ...opts.headers };
  const as = opts.as === undefined ? "u_me" : opts.as;
  if (as) headers.Authorization = `Bearer dev:${as}`;
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  const res = await handle(new Request(`http://localhost/api${path}`, { method, headers, body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined }));
  const text = await res.text();
  return { status: res.status, body: (text ? JSON.parse(text) : null) as T, headers: res.headers };
}

export const get = <T = any>(path: string, as?: string | null) => call<T>("GET", path, { as }); // eslint-disable-line @typescript-eslint/no-explicit-any
export const post = <T = any>(path: string, body?: unknown, as?: string | null, headers?: Record<string, string>) => // eslint-disable-line @typescript-eslint/no-explicit-any
  call<T>("POST", path, { as, body: body ?? {}, headers });
export const patch = <T = any>(path: string, body: unknown, as?: string | null) => call<T>("PATCH", path, { as, body }); // eslint-disable-line @typescript-eslint/no-explicit-any
export const del = <T = any>(path: string, as?: string | null) => call<T>("DELETE", path, { as }); // eslint-disable-line @typescript-eslint/no-explicit-any

export async function myTripId(as = "u_me") {
  const r = await get<{ items: { id: string }[] }>("/trips/mine", as);
  return r.body.items[0].id;
}
