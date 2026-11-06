import { bad } from "./http";

export type Body = Record<string, unknown>;

export async function readBody(req: Request): Promise<Body> {
  const text = await req.text();
  if (!text) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw bad("Request body is not valid JSON.", "INVALID_JSON");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw bad("Request body must be a JSON object.");
  return parsed as Body;
}

/** Optional trimmed string with length bounds. Returns undefined when absent or empty. */
export function str(b: Body, key: string, o: { max: number; min?: number; required?: boolean }): string | undefined {
  const raw = b[key];
  if (raw === undefined || raw === null || raw === "") {
    if (o.required) throw bad(`${key} is required.`);
    return undefined;
  }
  if (typeof raw !== "string") throw bad(`${key} must be text.`);
  const s = raw.trim();
  if (!s) {
    if (o.required) throw bad(`${key} is required.`);
    return undefined;
  }
  if (s.length < (o.min ?? 1)) throw bad(`${key} is too short.`);
  if (s.length > o.max) throw bad(`${key} must be at most ${o.max} characters.`);
  return s;
}

export function int(b: Body, key: string, o: { min: number; max: number; required?: boolean }): number | undefined {
  const raw = b[key];
  if (raw === undefined || raw === null) {
    if (o.required) throw bad(`${key} is required.`);
    return undefined;
  }
  if (typeof raw !== "number" || !Number.isInteger(raw) || raw < o.min || raw > o.max) throw bad(`${key} must be a whole number from ${o.min} to ${o.max}.`);
  return raw;
}

export function oneOf<T extends string>(b: Body, key: string, allowed: readonly T[], required = false): T | undefined {
  const raw = b[key];
  if (raw === undefined || raw === null) {
    if (required) throw bad(`${key} is required.`);
    return undefined;
  }
  if (typeof raw !== "string" || !(allowed as readonly string[]).includes(raw)) throw bad(`${key} must be one of: ${allowed.join(", ")}.`);
  return raw as T;
}

export function bool(b: Body, key: string): boolean | undefined {
  const raw = b[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw !== "boolean") throw bad(`${key} must be true or false.`);
  return raw;
}

export const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
export function dateParam(value: string | null, name = "date"): string {
  if (!value || !DATE_RE.test(value) || Number.isNaN(Date.parse(`${value}T00:00:00+05:30`))) throw bad(`${name} must be YYYY-MM-DD.`);
  return value;
}
