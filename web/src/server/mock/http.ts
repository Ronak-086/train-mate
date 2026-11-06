/** Error carrying everything needed to build an RFC 9457 problem response. */
export class ApiFail extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(detail);
  }
}

const TITLES: Record<number, string> = {
  400: "Bad request", 401: "Not signed in", 403: "Not allowed", 404: "Not found", 409: "Conflict",
  410: "Gone", 429: "Too many requests", 500: "Server error",
};

const NO_STORE = { "Cache-Control": "no-store" };

export const json = (data: unknown, status = 200) => Response.json(data, { status, headers: NO_STORE });
export const noContent = () => new Response(null, { status: 204, headers: NO_STORE });

export function problem(f: ApiFail): Response {
  const body = {
    type: `https://trainmate.example/problems/${f.code.toLowerCase().replace(/_/g, "-")}`,
    title: TITLES[f.status] ?? "Error",
    status: f.status,
    detail: f.detail,
    code: f.code,
    ...f.extra,
  };
  return new Response(JSON.stringify(body), {
    status: f.status,
    headers: { "Content-Type": "application/problem+json", ...NO_STORE, ...(f.status === 429 && f.extra.retryAfterSeconds ? { "Retry-After": String(f.extra.retryAfterSeconds) } : {}) },
  });
}

export const bad = (detail: string, code = "VALIDATION_ERROR") => new ApiFail(400, code, detail);
export const forbidden = (detail: string, code = "FORBIDDEN") => new ApiFail(403, code, detail);
export const notFound = (detail = "That doesn't exist or you can't see it.", code = "NOT_FOUND") => new ApiFail(404, code, detail);
export const conflict = (detail: string, code: string, extra?: Record<string, unknown>) => new ApiFail(409, code, detail, extra);
export const tooMany = (detail: string, code: string, retryAfterSeconds: number) =>
  new ApiFail(429, code, detail, { retryAfterSeconds: Math.max(1, Math.ceil(retryAfterSeconds)) });
