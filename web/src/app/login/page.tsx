"use client";

import { useRef, useState } from "react";
import { AppShell, Button, Notice } from "@/components/ui";
import { useAuth } from "@/components/providers/Providers";
import { describeAuthError } from "@/lib/auth/errors";
import { DEV_USERS } from "@/lib/dev-users";

function GoogleButton() {
  const { signInWithGoogle } = useAuth();
  const [busy, setBusy] = useState(false);
  const [slow, setSlow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const slowTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const go = async () => {
    setBusy(true);
    setSlow(false);
    setError(null);
    // If Google's window never opens (offline, blocked), don't leave the person stuck on a dead button.
    clearTimeout(slowTimer.current);
    slowTimer.current = setTimeout(() => setSlow(true), 10_000);
    try {
      await signInWithGoogle();
    } catch (e) {
      setError(describeAuthError((e as { code?: string }).code)); // null when they just closed the window
    } finally {
      clearTimeout(slowTimer.current);
      setBusy(false);
      setSlow(false);
    }
  };

  return (
    <div className="mt-8">
      <Button block onClick={go} disabled={busy && !slow}>
        {busy ? "Opening Google…" : "Continue with Google"}
      </Button>
      {slow && (
        <Notice className="mt-3">
          Taking longer than usual. If no Google window opened, check your connection and tap the button again.
        </Notice>
      )}
      {error && <Notice tone="error" className="mt-3">{error}</Notice>}
      <p className="mt-4 text-center text-small text-muted">
        We use Google only to know who you are. Other travellers see your first name and an initial.
      </p>
    </div>
  );
}

function DevPicker() {
  const { login } = useAuth();
  return (
    <>
      <h2 className="mt-8 text-h4 font-bold">Sign in as</h2>
      <p className="mt-1 text-small text-muted">
        Development sign-in. Pick a test traveller to try the app from their seat.
      </p>
      <p className="mt-1 text-small text-muted">Want Google sign-in? See docs/firebase-setup.md.</p>
      <ul className="mt-3">
        {DEV_USERS.map((u) => (
          <li key={u.id} className="border-b border-line last:border-b-0">
            <button type="button" onClick={() => login(u.id)} className="block w-full py-4 text-left transition-colors hover:bg-surface active:bg-surface">
              <span className="block text-title font-semibold">{u.name}</span>
              <span className="mt-[3px] block text-body text-muted">{u.note}</span>
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}

export default function LoginPage() {
  const { mode } = useAuth();
  return (
    <AppShell>
      <main className="flex-1 px-5 pb-8 pt-[25px]">
        <h1 className="text-h1 font-bold">Train Mate</h1>
        <p className="mt-[5px] text-muted">Travel together, even as strangers.</p>
        {mode === "firebase" ? <GoogleButton /> : <DevPicker />}
      </main>
    </AppShell>
  );
}
