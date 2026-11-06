"use client";

import { PostRow } from "@/components/board/PostRow";
import { StatusCard } from "@/components/home/StatusCard";
import { EmptyState, LinkButton, Notice, Button, Skeleton } from "@/components/ui";
import { KIND_LABEL, postMeta } from "@/lib/labels";
import { useAroundYou, useServerNow, useStatus } from "@/lib/queries";

export default function HomePage() {
  const around = useAroundYou(2);
  const trip = around.data?.trip ?? null;
  const status = useStatus(trip);
  const now = useServerNow();

  return (
    <main className="flex-1 px-5 pb-6 pt-5">
      {around.isPending ? (
        <>
          <Skeleton className="h-5 w-20" />
          <Skeleton className="mt-2 h-[284px]" />
        </>
      ) : around.isError ? (
        <Notice tone="error" action={<Button variant="text" className="h-auto shrink-0" onClick={() => void around.refetch()}>Retry</Button>}>
          Couldn&apos;t load your trip.
        </Notice>
      ) : !trip ? (
        <EmptyState title="Where are you travelling?">
          <p>Add your train to see its status and the people around you.</p>
          <LinkButton href="/trips/new" className="mt-5" block>
            Add my train
          </LinkButton>
        </EmptyState>
      ) : (
        <>
          <p className="font-semibold">My train</p>
          {status.data ? (
            <StatusCard trip={trip} status={status.data} nowMs={now} />
          ) : status.isError ? (
            <Notice tone="error" className="mt-2" action={<Button variant="text" className="h-auto shrink-0" onClick={() => void status.refetch()}>Retry</Button>}>
              Couldn&apos;t load the train status.
            </Notice>
          ) : (
            <Skeleton className="mt-2 h-[284px]" />
          )}

          <div className="mt-[19.5px] flex items-baseline justify-between">
            <h2 className="text-h4 font-bold">Around you</h2>
            <span className="text-muted">Coach {trip.coach}</span>
          </div>
          {around.data!.items.length === 0 ? (
            <p className="py-6 text-muted">Nothing yet. Post something and your coach will see it.</p>
          ) : (
            <ul className="-mt-px">
              {around.data!.items.map((p) => (
                <PostRow
                  key={p.id}
                  href={`/post/${p.id}`}
                  label={KIND_LABEL[p.kind]}
                  tone={p.kind === "ALERT" ? "alert" : "default"}
                  title={p.title}
                  meta={postMeta(p, now, true)}
                  dimmed={p.dimmed}
                />
              ))}
            </ul>
          )}
          <LinkButton href="/post/new" block className="mt-[3px]">
            New post
          </LinkButton>
          <p className="mt-2.5 text-center text-small text-muted">Emergency? Tell railway staff or call 139.</p>
        </>
      )}
    </main>
  );
}
