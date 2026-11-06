"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { timeAgo } from "@/lib/format";
import { BERTH_LABEL, CATEGORY_LABEL, KIND_LABEL } from "@/lib/labels";
import { POLL, useServerNow } from "@/lib/queries";
import { AppShell, BackLink, Badge, Button, Notice, Skeleton } from "@/components/ui";
import { Comments } from "./Comments";
import { OfferSection } from "./OfferSection";
import { Reactions } from "./Reactions";
import { SafetyPanel } from "./SafetyPanel";

const STATUS_NOTE = { CLOSED: "The poster closed this post.", MATCHED: "This one has been sorted out.", EXPIRED: "This post has expired." } as const;

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <AppShell>
      <header className="flex items-center border-b border-line pb-2 pl-1 pr-5 pt-[11px]">
        <BackLink href="/board" label="Back to Board" />
      </header>
      <main className="flex-1 px-5 pb-8 pt-5">{children}</main>
    </AppShell>
  );
}

export function PostDetail({ id }: { id: string }) {
  const qc = useQueryClient();
  const router = useRouter();
  const now = useServerNow();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const q = useQuery({ queryKey: ["post", id], queryFn: () => api.getPost(id), refetchInterval: POLL.post });

  const close = useMutation({
    mutationFn: () => api.patchPost(id, { status: "CLOSED" }),
    onSuccess: async (p) => {
      qc.setQueryData(["post", id], p);
      await Promise.all([qc.invalidateQueries({ queryKey: ["board"] }), qc.invalidateQueries({ queryKey: ["aroundYou"] })]);
    },
  });
  const remove = useMutation({
    mutationFn: () => api.deletePost(id),
    onSuccess: async () => {
      await qc.invalidateQueries();
      router.replace("/board");
    },
  });

  if (q.isPending) return <Frame><Skeleton className="h-6 w-24" /><Skeleton className="mt-4 h-10" /><Skeleton className="mt-4 h-24" /></Frame>;
  if (q.isError) {
    const gone = q.error instanceof ApiError && (q.error.status === 410 || q.error.status === 404);
    return (
      <Frame>
        <Notice tone={gone ? "info" : "error"} action={gone ? undefined : <Button variant="text" className="h-auto shrink-0" onClick={() => void q.refetch()}>Retry</Button>}>
          {q.error instanceof ApiError && q.error.status === 410 ? "This post is no longer available." : gone ? "We couldn't find that post." : describeError(q.error)}
        </Notice>
      </Frame>
    );
  }

  const p = q.data;
  const open = p.status === "OPEN";
  const isPublic = p.kind === "ASK" || p.kind === "ALERT" || p.kind === "SHARE";
  const confirmed = p.reactions.CONFIRMED;

  return (
    <Frame>
      <Badge tone={p.kind === "ALERT" ? "alert" : "default"}>{KIND_LABEL[p.kind]}</Badge>
      <h1 className="mt-2 text-h3 font-bold">{p.title}</h1>
      <p className="mt-1 text-muted">
        {p.isMine ? "You" : p.author.displayName} · Coach {p.coach} · {timeAgo(p.createdAt, now)}
      </p>

      {p.kind === "ALERT" && confirmed > 0 && <p className="mt-3 font-semibold">{confirmed === 1 ? "1 passenger says" : `${confirmed} passengers say`} this is still true.</p>}
      {p.dimmed && <Notice className="mt-3">Several passengers say this is out of date.</Notice>}
      {!open && <Notice className="mt-3">{STATUS_NOTE[p.status as keyof typeof STATUS_NOTE]}</Notice>}

      {p.kind === "SWAP" && p.offeringBerth && p.wantsBerth && (
        <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
          <dt className="text-muted">Has</dt><dd className="font-semibold">{BERTH_LABEL[p.offeringBerth]}</dd>
          <dt className="text-muted">Wants</dt><dd className="font-semibold">{BERTH_LABEL[p.wantsBerth]}</dd>
          {p.groupSize > 1 && (<><dt className="text-muted">Group</dt><dd className="font-semibold">{p.groupSize} people</dd></>)}
        </dl>
      )}
      {p.kind === "HELP" && (p.category || p.isUrgent) && (
        <p className="mt-3 text-muted">{[p.category && CATEGORY_LABEL[p.category], p.isUrgent && "Urgent"].filter(Boolean).join(" · ")}</p>
      )}
      {p.note && <p className="mt-4">{p.note}</p>}

      {isPublic ? (
        <>
          <Reactions post={p} disabled={!open} />
          <Comments postId={p.id} closed={!open} />
        </>
      ) : (
        <OfferSection post={p} />
      )}

      {p.isMine ? (
        <div className="mt-8 border-t border-line pt-4">
          {confirmDelete ? (
            <div>
              <p>Delete this post? Its replies and offers go with it.</p>
              <div className="mt-3 flex gap-3">
                <Button variant="outline" disabled={remove.isPending} onClick={() => remove.mutate()}>{remove.isPending ? "Deleting…" : "Yes, delete"}</Button>
                <Button variant="text" onClick={() => setConfirmDelete(false)}>Cancel</Button>
              </div>
            </div>
          ) : (
            <div className="flex gap-4">
              {open && <Button variant="outline" disabled={close.isPending} onClick={() => close.mutate()}>{close.isPending ? "Closing…" : "Close post"}</Button>}
              <Button variant="text" onClick={() => setConfirmDelete(true)}>Delete</Button>
            </div>
          )}
        </div>
      ) : (
        <SafetyPanel user={p.author} requestId={p.id} />
      )}
      {(close.isError || remove.isError) && <Notice tone="error" className="mt-3">{describeError(close.error ?? remove.error)}</Notice>}
    </Frame>
  );
}
