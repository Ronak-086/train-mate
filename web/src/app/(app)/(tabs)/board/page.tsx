"use client";

import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { PostRow } from "@/components/board/PostRow";
import { Button, EmptyState, LinkButton, Notice, Skeleton, Tabs } from "@/components/ui";
import { api } from "@/lib/api";
import type { PostKind } from "@/lib/api";
import { formatDay } from "@/lib/format";
import { KIND_LABEL, postMeta } from "@/lib/labels";
import { POLL, useActiveTrip, useServerNow } from "@/lib/queries";

type View = "coach" | "train";
type KindTab = "all" | PostKind;

const KIND_TABS: Array<{ id: KindTab; label: string }> = [
  { id: "all", label: "All" },
  { id: "SWAP", label: "Swap" },
  { id: "HELP", label: "Help" },
  { id: "ASK", label: "Question" },
  { id: "ALERT", label: "Alert" },
];

export default function BoardPage() {
  const { trip, isPending: tripPending, isError: tripError, refetch } = useActiveTrip();
  const [view, setView] = useState<View>("coach");
  const [kind, setKind] = useState<KindTab>("all");
  const now = useServerNow();

  const board = useInfiniteQuery({
    queryKey: ["board", trip?.trainNo, trip?.journeyDate, trip?.coach, view, kind],
    enabled: !!trip,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.board({
        train: trip!.trainNo,
        date: trip!.journeyDate,
        // "My coach" asks for this coach; "Whole train" asks for posts addressed to everyone.
        coach: view === "coach" ? trip!.coach : undefined,
        scope: view === "train" ? "TRAIN" : undefined,
        kind: kind === "all" ? undefined : kind,
        cursor: pageParam,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchInterval: POLL.feed,
  });

  const items = board.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <main className="flex-1 px-5 pb-6 pt-[25px]">
      <h1 className="text-h1 font-bold">Board</h1>
      <p className="mt-[5px] text-muted">{trip ? `${trip.trainNo} ${trip.trainName} · ${formatDay(trip.journeyDate)}` : "\u00a0"}</p>

      <Tabs
        className="mt-3.5"
        label="Where to look"
        value={view}
        onChange={setView}
        items={[
          { id: "coach", label: trip ? `My coach (${trip.coach})` : "My coach" },
          { id: "train", label: "Whole train" },
        ]}
      />
      <Tabs compact className="mt-[3px]" label="Post type" value={kind} onChange={setKind} items={KIND_TABS} />

      {tripPending || (!!trip && board.isPending) ? (
        <div className="mt-4 space-y-4">
          <Skeleton className="h-[116px]" />
          <Skeleton className="h-[116px]" />
        </div>
      ) : tripError || board.isError ? (
        <Notice
          tone="error"
          className="mt-4"
          action={<Button variant="text" className="h-auto shrink-0" onClick={() => (tripError ? refetch() : void board.refetch())}>Retry</Button>}
        >
          Couldn&apos;t load the board.
        </Notice>
      ) : !trip ? (
        <EmptyState title="Add your train first">
          <p>The board shows the people travelling on your train.</p>
          <LinkButton href="/trips/new" block className="mt-5">Add my train</LinkButton>
        </EmptyState>
      ) : items.length === 0 ? (
        <EmptyState title={kind === "all" ? "Nothing here yet" : `No ${KIND_TABS.find((t) => t.id === kind)!.label.toLowerCase()} posts`}>
          <p>{view === "train" ? "Questions and alerts sent to the whole train appear here." : "Be the first to post in your coach."}</p>
        </EmptyState>
      ) : (
        <>
          <ul>
            {items.map((p) => (
              <PostRow
                key={p.id}
                href={`/post/${p.id}`}
                label={KIND_LABEL[p.kind]}
                tone={p.kind === "ALERT" ? "alert" : "default"}
                title={p.title}
                meta={postMeta(p, now)}
                dimmed={p.dimmed}
              />
            ))}
          </ul>
          {board.hasNextPage && (
            <Button variant="outline" block className="mt-4" disabled={board.isFetchingNextPage} onClick={() => void board.fetchNextPage()}>
              {board.isFetchingNextPage ? "Loading…" : "Show more"}
            </Button>
          )}
        </>
      )}
    </main>
  );
}
