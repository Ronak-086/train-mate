"use client";

import { useCallback, useSyncExternalStore } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, serverNow, subscribeServerClock } from "./api";
import type { Trip } from "./api";

/** Polling intervals from the spec. */
export const POLL = { status: 60_000, feed: 30_000, post: 15_000, chat: 5_000, chats: 15_000 } as const;

/**
 * Server time, so "4 min ago" is right even when the device clock is off. Re-reads every
 * `tickMs`, and immediately when a response teaches us the server's clock differs.
 */
export function useServerNow(tickMs = 15_000): number {
  const subscribe = useCallback(
    (notify: () => void) => {
      const off = subscribeServerClock(notify);
      const timer = setInterval(notify, tickMs);
      return () => {
        off();
        clearInterval(timer);
      };
    },
    [tickMs],
  );
  // Quantised so the snapshot is stable between ticks.
  return useSyncExternalStore(subscribe, () => Math.floor(serverNow() / tickMs) * tickMs, () => 0);
}

/** The home feed. Also tells us which trip is "active", so most screens read their trip from here. */
export function useAroundYou(limit = 2) {
  return useQuery({ queryKey: ["aroundYou", limit], queryFn: () => api.aroundYou(limit), refetchInterval: POLL.feed });
}

export function useActiveTrip(): { trip: Trip | null; isPending: boolean; isError: boolean; refetch: () => void } {
  const q = useAroundYou(2);
  return { trip: q.data?.trip ?? null, isPending: q.isPending, isError: q.isError, refetch: () => void q.refetch() };
}

export function useStatus(trip: Trip | null) {
  return useQuery({
    queryKey: ["status", trip?.trainNo, trip?.journeyDate],
    queryFn: () => api.getStatus(trip!.trainNo, trip!.journeyDate),
    enabled: !!trip,
    refetchInterval: POLL.status,
  });
}

export function useMe() {
  return useQuery({ queryKey: ["me"], queryFn: () => api.me() });
}
