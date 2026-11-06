import type { Body } from "./v";
import type { UserRec, Store } from "./store";

export type Ctx = {
  req: Request;
  store: Store;
  params: Record<string, string>;
  query: URLSearchParams;
  /** Authenticated caller. Only set on routes that require auth. */
  me: UserRec;
  body: () => Promise<Body>;
};
