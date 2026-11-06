"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Badge, Button, EmptyState, Notice, Skeleton } from "@/components/ui";
import { api } from "@/lib/api";
import { timeAgo } from "@/lib/format";
import { CONV_STATUS_LABEL, KIND_LABEL } from "@/lib/labels";
import { POLL, useMe, useServerNow } from "@/lib/queries";

export default function ChatsPage() {
  const me = useMe();
  const myId = me.data?.id;
  const now = useServerNow();
  const q = useQuery({ queryKey: ["convs"], queryFn: () => api.myConversations(), refetchInterval: POLL.chats });
  const items = q.data?.items ?? [];

  return (
    <main className="flex-1 px-5 pb-6 pt-[25px]">
      <h1 className="text-h1 font-bold">Chats</h1>
      <p className="mt-[5px] text-muted">Offers and conversations about swaps and help</p>

      {q.isPending ? (
        <div className="mt-4 space-y-4"><Skeleton className="h-[96px]" /><Skeleton className="h-[96px]" /></div>
      ) : q.isError ? (
        <Notice tone="error" className="mt-4" action={<Button variant="text" className="h-auto shrink-0" onClick={() => void q.refetch()}>Retry</Button>}>
          Couldn&apos;t load your chats.
        </Notice>
      ) : items.length === 0 ? (
        <EmptyState title="No chats yet">
          <p>When someone offers to swap or help, or you make an offer, the conversation shows up here.</p>
        </EmptyState>
      ) : (
        <ul className="mt-4">
          {items.map((c) => {
            const other = c.iAmRequester ? c.helper : c.requester;
            return (
              <li key={c.id} className="border-b border-line last:border-b-0">
                <Link href={`/chats/${c.id}`} className="block py-4 transition-colors hover:bg-surface">
                  <Badge>{KIND_LABEL[c.kind]}</Badge>
                  <h2 className="mt-2 text-title font-semibold">{c.requestTitle}</h2>
                  <p className="mt-[3px] text-body text-muted">
                    {other.displayName} · {CONV_STATUS_LABEL[c.status]}
                  </p>
                  {c.lastMessage && (
                    <p className="mt-[3px] truncate text-body text-muted">
                      {c.lastMessage.senderId === myId ? "You: " : ""}
                      {c.lastMessage.body} · {timeAgo(c.lastMessage.createdAt, now)}
                    </p>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </main>
  );
}
