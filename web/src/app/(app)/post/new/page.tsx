"use client";

import { NewPostForm } from "@/components/post/NewPostForm";
import { AppShell, EmptyState, LinkButton, Notice, Skeleton } from "@/components/ui";
import { useActiveTrip } from "@/lib/queries";

export default function NewPostPage() {
  const { trip, isPending, isError } = useActiveTrip();
  return (
    <AppShell>
      {isPending ? (
        <div className="space-y-4 px-5 pt-7">
          <Skeleton className="h-9 w-40" />
          <Skeleton className="h-48" />
        </div>
      ) : isError ? (
        <div className="px-5 pt-7"><Notice tone="error">Couldn&apos;t load your trip.</Notice></div>
      ) : !trip ? (
        <div className="px-5 pt-7">
          <EmptyState title="Add your train first">
            <p>You need a train to post to.</p>
            <LinkButton href="/trips/new" block className="mt-5">Add my train</LinkButton>
          </EmptyState>
        </div>
      ) : (
        <NewPostForm trip={trip} />
      )}
    </AppShell>
  );
}
