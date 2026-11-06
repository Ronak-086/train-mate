import { getApp, getApps, initializeApp, type FirebaseOptions } from "@firebase/app";
import {
  GoogleAuthProvider, getAuth, onAuthStateChanged, signInWithPopup, signInWithRedirect, signOut, type Auth,
} from "@firebase/auth";
import { setTokenProvider } from "../api";
import type { AuthSnapshot } from "./types";

/** Firebase's web config is public by design. Access is controlled by the backend verifying the ID token. */
function config(): FirebaseOptions {
  const cfg = {
    apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
    authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
    projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
    appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
    storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
    messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
    measurementId: process.env.NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID,
  };
  const required = {
    apiKey: cfg.apiKey,
    authDomain: cfg.authDomain,
    projectId: cfg.projectId,
    appId: cfg.appId,
  };
  const missing = Object.entries(required).filter(([, v]) => !v).map(([k]) => k);
  if (missing.length) {
    const err = new Error(`Missing Firebase settings: ${missing.join(", ")}`) as Error & { code: string };
    err.code = "auth/missing-config";
    throw err;
  }
  return cfg;
}

export function getFirebaseAuth(): Auth {
  const app = getApps().length ? getApp() : initializeApp(config());
  return getAuth(app);
}

let snapshot: AuthSnapshot = { ready: false, signedIn: false, userId: null };
let started = false;
const listeners = new Set<() => void>();

function start() {
  if (started) return;
  started = true;
  let auth: Auth;
  try {
    auth = getFirebaseAuth();
  } catch {
    snapshot = { ready: true, signedIn: false, userId: null }; // misconfigured: the sign-in screen will explain
    return;
  }
  // A fresh ID token for every request. The SDK caches it and refreshes it a few minutes before it expires.
  setTokenProvider(async () => (auth.currentUser ? auth.currentUser.getIdToken() : null));
  onAuthStateChanged(auth, (user) => {
    snapshot = { ready: true, signedIn: !!user, userId: user?.uid ?? null };
    listeners.forEach((l) => l());
  });
}

export function subscribeFirebase(listener: () => void) {
  start();
  listeners.add(listener);
  return () => void listeners.delete(listener);
}
export const getFirebaseSnapshot = (): AuthSnapshot => snapshot;

/** Popup first. Some mobile browsers block popups, so fall back to a full-page redirect. */
export async function signInWithGoogle(): Promise<void> {
  const auth = getFirebaseAuth();
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  try {
    await signInWithPopup(auth, provider);
  } catch (e) {
    const code = (e as { code?: string }).code;
    if (code === "auth/popup-blocked" || code === "auth/operation-not-supported-in-this-environment") {
      await signInWithRedirect(auth, provider);
      return;
    }
    throw e;
  }
}

export async function signOutFirebase(): Promise<void> {
  await signOut(getFirebaseAuth());
}
