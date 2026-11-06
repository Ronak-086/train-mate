"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "./Providers";

/** Renders its children only for a signed-in person; everyone else goes to the sign-in screen. */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const { ready, signedIn } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (ready && !signedIn) router.replace("/login");
  }, [ready, signedIn, router]);
  if (!ready || !signedIn) return <div className="min-h-dvh bg-bg" aria-busy="true" />;
  return <>{children}</>;
}
