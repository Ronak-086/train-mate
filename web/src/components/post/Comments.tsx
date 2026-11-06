"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { timeAgo } from "@/lib/format";
import { POLL, useServerNow } from "@/lib/queries";
import { Button, Notice, TextInput } from "@/components/ui";

export function Comments({ postId, closed }: { postId: string; closed: boolean }) {
  const qc = useQueryClient();
  const now = useServerNow();
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  const list = useQuery({ queryKey: ["comments", postId], queryFn: () => api.comments(postId), refetchInterval: POLL.post });
  const add = useMutation({
    mutationFn: (body: string) => api.addComment(postId, body),
    onSuccess: async () => {
      setText("");
      setError(null);
      await Promise.all([qc.invalidateQueries({ queryKey: ["comments", postId] }), qc.invalidateQueries({ queryKey: ["post", postId] })]);
    },
    onError: (e) => setError(describeError(e)),
  });

  const items = list.data?.items ?? [];
  return (
    <section aria-label="Replies" className="mt-8">
      <h2 className="text-h4 font-bold">{items.length === 1 ? "1 reply" : `${items.length} replies`}</h2>
      {list.isError && <Notice tone="error" className="mt-3">Couldn&apos;t load the replies.</Notice>}
      <ul className="mt-1">
        {items.map((c) => (
          <li key={c.id} className="border-b border-line py-3 last:border-b-0">
            <p className="text-small text-muted">
              <span className="font-semibold text-ink">{c.isMine ? "You" : c.author.displayName}</span> · {timeAgo(c.createdAt, now)}
            </p>
            <p className="mt-0.5">{c.body}</p>
          </li>
        ))}
      </ul>
      {!list.isPending && items.length === 0 && <p className="mt-2 text-muted">No replies yet.</p>}

      {closed ? (
        <p className="mt-4 text-muted">This post is closed, so replies are off.</p>
      ) : (
        <form
          className="mt-4 flex gap-2.5"
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) add.mutate(text.trim());
          }}
        >
          <TextInput aria-label="Write a reply" placeholder="Write a reply" value={text} maxLength={500} onChange={(e) => setText(e.target.value)} className="h-[55px] flex-1" />
          <Button type="submit" className="w-[88px]" disabled={!text.trim() || add.isPending}>Send</Button>
        </form>
      )}
      {error && <Notice tone="error" className="mt-3">{error}</Notice>}
    </section>
  );
}
