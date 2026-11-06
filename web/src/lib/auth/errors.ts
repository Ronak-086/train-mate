/** Codes that just mean "the person changed their mind". Nothing to show. */
const QUIET = new Set(["auth/popup-closed-by-user", "auth/cancelled-popup-request", "auth/user-cancelled"]);

/** A sentence for a failed sign-in, or null when the person simply closed the window. */
export function describeAuthError(code: string | undefined): string | null {
  if (code && QUIET.has(code)) return null;
  switch (code) {
    case "auth/network-request-failed":
      return "No connection. Check your network and try again.";
    case "auth/unauthorized-domain":
      return "Sign-in isn't set up for this web address yet.";
    case "auth/operation-not-allowed":
      return "Google sign-in isn't turned on for this app yet.";
    case "auth/user-disabled":
      return "This account has been disabled.";
    case "auth/too-many-requests":
      return "Too many attempts. Wait a minute and try again.";
    case "auth/internal-error":
    case "auth/invalid-api-key":
    case "auth/missing-config":
      return "Sign-in isn't configured correctly. Please tell the app's owner.";
    default:
      return "Couldn't sign you in. Please try again.";
  }
}
