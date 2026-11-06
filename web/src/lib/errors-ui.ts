import { ApiError } from "./api";

/** A sentence a person can read for any failure. */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === "POST_COOLDOWN" && e.retryAfterSeconds) return `${e.message} Try again in ${e.retryAfterSeconds} seconds.`;
    return e.message;
  }
  return "Something went wrong. Please try again.";
}
