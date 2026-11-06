"use client";

import { createContext, useCallback, useContext, useMemo, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import { getFirebaseSnapshot, signInWithGoogle as firebaseSignIn, signOutFirebase, subscribeFirebase } from "@/lib/auth/firebase";
import { AUTH_MODE } from "@/lib/auth/mode";
import type { AuthSnapshot } from "@/lib/auth/types";
import { getDevSnapshot, saveToken, subscribeDev } from "@/lib/session";

type Auth = AuthSnapshot & {
  mode: "dev" | "firebase";
  /** Dev mode only: sign in as a test traveller. */
  login: (userId: string) => void;
  /** Firebase mode only: opens Google sign-in. Rejects with the Firebase error if it fails. */
  signInWithGoogle: () => Promise<void>;
  logout: () => void;
};

const AuthContext = createContext<Auth | null>(null);

export function useAuth(): Auth {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <Providers>");
  return ctx;
}

const SERVER_SNAPSHOT: AuthSnapshot = { ready: false, signedIn: false, userId: null };
const subscribe = AUTH_MODE === "firebase" ? subscribeFirebase : subscribeDev;
const getSnapshot = AUTH_MODE === "firebase" ? getFirebaseSnapshot : getDevSnapshot;

/** Signs out wherever the session lives. The AuthGate then sends the person to /login. */
function endSession() {
  if (AUTH_MODE === "firebase") void signOutFirebase();
  else saveToken(null);
}

export function Providers({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const snap = useSyncExternalStore(subscribe, getSnapshot, () => SERVER_SNAPSHOT);

  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 5_000,
            // Retrying a 4xx never helps; only flaky networks and 5xx deserve another go.
            retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
          },
        },
        queryCache: new QueryCache({
          onError: (error) => {
            // The server no longer accepts our token (expired session, deleted account): sign out.
            if (error instanceof ApiError && error.status === 401) endSession();
          },
        }),
      }),
  );

  const logout = useCallback(() => {
    endSession();
    client.clear();
    router.replace("/login");
  }, [client, router]);

  const login = useCallback(
    (userId: string) => {
      client.clear();
      saveToken(`dev:${userId}`);
      router.push("/home");
    },
    [client, router],
  );

  const signInWithGoogle = useCallback(async () => {
    client.clear();
    await firebaseSignIn();
    router.push("/home");
  }, [client, router]);

  const value = useMemo<Auth>(() => ({ ...snap, mode: AUTH_MODE, login, signInWithGoogle, logout }), [snap, login, signInWithGoogle, logout]);

  return (
    <AuthContext.Provider value={value}>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </AuthContext.Provider>
  );
}
