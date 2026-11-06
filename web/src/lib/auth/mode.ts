/** "dev" shows a picker of test travellers. "firebase" is real Google sign-in. Chosen at build time. */
export const AUTH_MODE: "dev" | "firebase" = process.env.NEXT_PUBLIC_AUTH_MODE === "firebase" ? "firebase" : "dev";
