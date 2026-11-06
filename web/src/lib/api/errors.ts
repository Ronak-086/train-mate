import type { ProblemDetails } from "./types";

/** Thrown for every non-2xx response. `code` is stable; `detail` is safe to show to a person. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfterSeconds?: number;
  readonly problem: ProblemDetails;

  constructor(problem: ProblemDetails) {
    super(problem.detail || problem.title);
    this.name = "ApiError";
    this.status = problem.status;
    this.code = problem.code;
    this.retryAfterSeconds = problem.retryAfterSeconds;
    this.problem = problem;
  }

  static is(e: unknown, code?: string): e is ApiError {
    return e instanceof ApiError && (code === undefined || e.code === code);
  }
}

/** Build an ApiError for failures that never produced a response (offline, DNS, aborted). */
export function networkError(cause: unknown): ApiError {
  return new ApiError({
    type: "about:blank",
    title: "Network error",
    status: 0,
    code: "NETWORK_ERROR",
    detail: cause instanceof Error && cause.name === "AbortError" ? "Request cancelled." : "No connection. Check your network and try again.",
  });
}
