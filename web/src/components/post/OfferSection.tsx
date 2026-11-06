"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api } from "@/lib/api";
import type { Post } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { timeAgo } from "@/lib/format";
import { CONV_STATUS_LABEL } from "@/lib/labels";
import { POLL, useServerNow } from "@/lib/queries";
import { Button, Field, LinkButton, Notice, TextInput } from "@/components/ui";

/** For swaps and help requests: the poster sees offers; everyone else can make one. */
export function OfferSection({ post }: { post: Post }) {
  return post.isMine ? <Offers post={post} /> : <MakeOffer post={post} />;
}

function Offers({ post }: { post: Post }) {
  const qc = useQueryClient();
  const router = useRouter();
  const now = useServerNow();
  const convs = useQuery({ queryKey: ["convs"], queryFn: () => api.myConversations(), refetchInterval: POLL.chats });
  const offers = (convs.data?.items ?? []).filter((c) => c.requestId === post.id);

  const decide = useMutation({
    mutationFn: ({ id, status }: { id: string; status: "ACCEPTED" | "DECLINED" }) => api.patchConversation(id, { status }),
    onSuccess: async (conv) => {
      await Promise.all([qc.invalidateQueries({ queryKey: ["convs"] }), qc.invalidateQueries({ queryKey: ["post", post.id] })]);
      if (conv.status === "ACCEPTED") router.push(`/chats/${conv.id}`);
    },
  });

  return (
    <section aria-label="Offers" className="mt-8">
      <h2 className="text-h4 font-bold">{offers.length === 1 ? "1 offer" : `${offers.length} offers`}</h2>
      {convs.isError && <Notice tone="error" className="mt-3">Couldn&apos;t load the offers.</Notice>}
      {!convs.isPending && offers.length === 0 && <p className="mt-2 text-muted">No offers yet. They&apos;ll show up here.</p>}
      <ul className="mt-1">
        {offers.map((c) => (
          <li key={c.id} className="border-b border-line py-4 last:border-b-0">
            <Link href={`/chats/${c.id}`} className="block">
              <p className="text-title font-semibold">
                {c.helper.displayName} <span className="text-body font-normal text-muted">· {CONV_STATUS_LABEL[c.status]}</span>
              </p>
              {c.lastMessage && (
                <p className="mt-[3px] text-body text-muted">
                  {c.lastMessage.body} · {timeAgo(c.lastMessage.createdAt, now)}
                </p>
              )}
            </Link>
            {c.status === "PENDING" && post.status === "OPEN" && (
              <div className="mt-3 flex gap-3">
                <Button variant="outline" disabled={decide.isPending} onClick={() => decide.mutate({ id: c.id, status: "ACCEPTED" })}>Accept</Button>
                <Button variant="text" disabled={decide.isPending} onClick={() => decide.mutate({ id: c.id, status: "DECLINED" })}>Decline</Button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {decide.isError && <Notice tone="error" className="mt-3">{describeError(decide.error)}</Notice>}
    </section>
  );
}

function MakeOffer({ post }: { post: Post }) {
  const qc = useQueryClient();
  const router = useRouter();
  const [message, setMessage] = useState("");
  const [error, setError] = useState<string | null>(null);

  const offer = useMutation({
    mutationFn: () => api.respond(post.id, { message: message.trim() || undefined }),
    onSuccess: async (conv) => {
      await Promise.all([qc.invalidateQueries({ queryKey: ["convs"] }), qc.invalidateQueries({ queryKey: ["post", post.id] })]);
      router.push(`/chats/${conv.id}`);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === "CONVERSATION_EXISTS" && typeof e.problem.conversationId === "string") {
        router.push(`/chats/${e.problem.conversationId}`);
        return;
      }
      setError(describeError(e));
    },
  });

  if (post.myConversationId) {
    return (
      <section className="mt-8">
        <p className="text-muted">You&apos;ve made an offer on this post.</p>
        <LinkButton href={`/chats/${post.myConversationId}`} variant="outline" className="mt-3">Open chat</LinkButton>
      </section>
    );
  }
  if (post.status !== "OPEN") return null;

  const verb = post.kind === "SWAP" ? "Offer to swap" : "Offer to help";
  return (
    <form
      className="mt-8"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        offer.mutate();
      }}
    >
      <Field label="Message (optional)">
        {(id) => <TextInput id={id} value={message} maxLength={200} onChange={(e) => setMessage(e.target.value)} placeholder={post.kind === "SWAP" ? "I have a lower berth in seat 7" : "I can help at the next stop"} />}
      </Field>
      <Button type="submit" block className="mt-3" disabled={offer.isPending}>{offer.isPending ? "Sending…" : verb}</Button>
      <p className="mt-2 text-small text-muted">{post.kind === "SWAP" ? "Swaps only, no payments. Railway staff have the final say." : "Chat opens once they accept."}</p>
      {error && <Notice tone="error" className="mt-3">{error}</Notice>}
    </form>
  );
}
