"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api } from "@/lib/api";
import type { Conversation } from "@/lib/api";
import { SafetyPanel } from "@/components/post/SafetyPanel";
import { AppShell, BackLink, Button, ChatBubble, LinkButton, Notice, Skeleton, TextInput } from "@/components/ui";
import { describeError } from "@/lib/errors-ui";
import { formatClock } from "@/lib/format";
import { KIND_LABEL } from "@/lib/labels";
import { POLL } from "@/lib/queries";

function systemLine(c: Conversation): string {
  const other = (c.iAmRequester ? c.helper : c.requester).displayName;
  switch (c.status) {
    case "PENDING":
      return c.iAmRequester ? `${other} made an offer. Accept to start chatting.` : `Waiting for ${other} to accept.`;
    case "ACCEPTED":
      return `${c.kind === "SWAP" ? "Swap" : "Offer"} accepted. Chat stays in the app.`;
    case "DECLINED":
      return "This offer was declined.";
    case "DONE":
      return "Marked done by both of you.";
  }
}

function Rating({ conversationId }: { conversationId: string }) {
  const [state, setState] = useState<"idle" | "thanks" | "already">("idle");
  const rate = useMutation({
    mutationFn: (rating: number) => api.review({ conversationId, rating }),
    onSuccess: () => setState("thanks"),
    onError: (e) => {
      if (ApiError.is(e, "ALREADY_REVIEWED")) setState("already");
    },
  });
  if (state !== "idle") return <p className="text-center text-muted">{state === "thanks" ? "Thanks for rating." : "You've already rated this."}</p>;
  return (
    <div>
      <p id="rate-label" className="text-center font-medium">How did it go?</p>
      <div className="mt-2 flex justify-center gap-2" role="group" aria-labelledby="rate-label">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            aria-label={`${n} out of 5`}
            disabled={rate.isPending}
            onClick={() => rate.mutate(n)}
            className="size-12 rounded-control border-[1.5px] border-ink bg-surface text-lead font-semibold transition-colors enabled:hover:bg-raised active:bg-raised disabled:opacity-40"
          >
            {n}
          </button>
        ))}
      </div>
      {rate.isError && !ApiError.is(rate.error, "ALREADY_REVIEWED") && <Notice tone="error" className="mt-3">{describeError(rate.error)}</Notice>}
    </div>
  );
}

export function ChatView({ id }: { id: string }) {
  const qc = useQueryClient();
  const [text, setText] = useState("");
  const [sendError, setSendError] = useState<string | null>(null);
  const [safetyOpen, setSafetyOpen] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  const conv = useQuery({ queryKey: ["conv", id], queryFn: () => api.getConversation(id), refetchInterval: POLL.chat });
  const msgs = useQuery({ queryKey: ["messages", id], queryFn: () => api.messages(id), refetchInterval: POLL.chat });
  const items = msgs.data?.items ?? [];

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [items.length]);

  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ["conv", id] }), qc.invalidateQueries({ queryKey: ["messages", id] }), qc.invalidateQueries({ queryKey: ["convs"] })]);

  const send = useMutation({
    mutationFn: (body: string) => api.sendMessage(id, body),
    onSuccess: async () => {
      setText("");
      setSendError(null);
      await refresh();
    },
    onError: (e) => {
      setSendError(describeError(e));
      void refresh(); // the chat may have just closed or been declined
    },
  });
  const change = useMutation({
    mutationFn: (status: "ACCEPTED" | "DECLINED" | "DONE") => api.patchConversation(id, { status }),
    onSuccess: async () => {
      await refresh();
      await qc.invalidateQueries({ queryKey: ["post"] });
    },
  });

  if (conv.isPending) return <AppShell><div className="space-y-4 px-5 pt-6"><Skeleton className="h-16" /><Skeleton className="h-24" /></div></AppShell>;
  if (conv.isError) {
    return (
      <AppShell>
        <header className="flex items-center border-b border-line pb-2 pl-1 pr-5 pt-[11px]"><BackLink href="/chats" label="Back to Chats" /></header>
        <main className="flex-1 px-5 pt-5"><Notice tone={conv.error instanceof ApiError && conv.error.status === 404 ? "info" : "error"}>{conv.error instanceof ApiError && conv.error.status === 404 ? "We couldn't find that chat." : describeError(conv.error)}</Notice></main>
      </AppShell>
    );
  }

  const c = conv.data;
  const other = c.iAmRequester ? c.helper : c.requester;
  const canMarkDone = c.status === "ACCEPTED";

  return (
    <AppShell>
      <header className="flex items-start gap-1 border-b border-line pb-[14px] pl-1 pr-5 pt-[11px]">
        <BackLink href="/chats" label="Back to Chats" className="mt-[3px]" />
        <div className="min-w-0 flex-1 pt-[9px]">
          <p className="text-muted">
            {KIND_LABEL[c.kind]} · Coach {c.coach}
          </p>
          <h1 className="mt-0.5 text-h3 font-bold leading-[1.35]">{c.requestTitle}</h1>
        </div>
        {canMarkDone && (
          <Button variant="outline" className="ml-2 mt-[28px] shrink-0" disabled={c.myDone || change.isPending} onClick={() => change.mutate("DONE")}>
            {c.myDone ? "Waiting…" : "Mark done"}
          </Button>
        )}
      </header>

      <main className="flex-1 px-5 pt-[17.5px]">
        <p className="text-center text-small text-muted">{systemLine(c)}</p>
        {canMarkDone && c.otherDone && !c.myDone && <p className="mt-1 text-center text-small text-muted">{other.displayName} marked this done.</p>}
        {canMarkDone && c.myDone && <p className="mt-1 text-center text-small text-muted">Waiting for {other.displayName} to mark it done too.</p>}

        <div className="mt-3 flex flex-col gap-[13.5px]" aria-live="polite">
          {items.map((m) => (
            <ChatBubble key={m.id} side={m.isMine ? "out" : "in"} time={formatClock(m.createdAt)}>
              {m.body}
            </ChatBubble>
          ))}
        </div>
        {msgs.isError && <Notice tone="error" className="mt-3">Couldn&apos;t refresh the messages. We&apos;ll keep trying.</Notice>}

        <div className="mt-6">
          <Button variant="text" className="h-auto px-0 text-small" onClick={() => setSafetyOpen((v) => !v)}>
            Report or block {other.displayName}
          </Button>
          {safetyOpen && <SafetyPanel user={other} redirectAfterBlock="/chats" />}
        </div>
        <div ref={endRef} />
      </main>

      {c.kind === "SWAP" && <p className="pb-[5px] pt-2 text-center text-small text-muted">Confirm swaps with railway staff or the TTE.</p>}

      {c.status === "ACCEPTED" && (
        <form
          className="flex gap-2.5 border-t border-line px-5 pb-5 pt-[11px]"
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) send.mutate(text.trim());
          }}
        >
          <TextInput aria-label="Message" placeholder="Write a message" value={text} maxLength={1000} onChange={(e) => setText(e.target.value)} className="h-[55px] flex-1" />
          <Button type="submit" className="w-[88px]" disabled={!text.trim() || send.isPending}>Send</Button>
        </form>
      )}
      {c.status === "PENDING" && c.iAmRequester && (
        <div className="flex gap-3 border-t border-line px-5 pb-5 pt-[11px]">
          <Button className="flex-1" disabled={change.isPending} onClick={() => change.mutate("ACCEPTED")}>Accept</Button>
          <Button variant="outline" className="h-14 flex-1" disabled={change.isPending} onClick={() => change.mutate("DECLINED")}>Decline</Button>
        </div>
      )}
      {c.status === "DONE" && <div className="border-t border-line px-5 pb-5 pt-4"><Rating conversationId={c.id} /></div>}
      {(c.status === "DECLINED" || (c.status === "PENDING" && !c.iAmRequester)) && (
        <div className="border-t border-line px-5 pb-5 pt-4"><LinkButton href="/chats" variant="outline" block>Back to chats</LinkButton></div>
      )}
      {(sendError || change.isError) && <div className="px-5 pb-3"><Notice tone="error">{sendError ?? describeError(change.error)}</Notice></div>}
    </AppShell>
  );
}
