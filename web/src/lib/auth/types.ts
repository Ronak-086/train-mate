export type AuthSnapshot = {
  /** False until the browser has told us whether anyone is signed in. */
  ready: boolean;
  signedIn: boolean;
  /** The provider's id for the person (dev user id, or Firebase uid). Not the app's user id; use useMe() for that. */
  userId: string | null;
};
