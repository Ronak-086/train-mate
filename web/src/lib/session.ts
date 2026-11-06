import { setAuthToken } from "./api";
import type { AuthSnapshot } from "./auth/types";

/** Dev sign-in store: remembers "dev:<userId>" in localStorage. Only used when AUTH_MODE is "dev". */
const KEY = "trainmate.token";
let memory: string | null | undefined;
let snapshot: AuthSnapshot | undefined;
const listeners = new Set<() => void>();

function read(): string | null {
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

function toSnapshot(token: string | null): AuthSnapshot {
  return { ready: true, signedIn: !!token, userId: token?.startsWith("dev:") ? token.slice(4) : null };
}

/** Current dev sign-in. Safe to call during render on the client. */
export function getDevSnapshot(): AuthSnapshot {
  if (memory === undefined) {
    memory = read();
    setAuthToken(memory);
    snapshot = toSnapshot(memory);
  }
  return snapshot!;
}

export function saveToken(token: string | null) {
  memory = token;
  snapshot = toSnapshot(token);
  setAuthToken(token);
  try {
    if (token) window.localStorage.setItem(KEY, token);
    else window.localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable (private mode): the session lasts until the tab closes */
  }
  listeners.forEach((l) => l());
}

export function subscribeDev(listener: () => void) {
  listeners.add(listener);
  return () => void listeners.delete(listener);
}
