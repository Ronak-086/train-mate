"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Post, ReactionType } from "@/lib/api";
import { Chip, Notice } from "@/components/ui";
import { describeError } from "@/lib/errors-ui";

const OPTIONS: Record<string, Array<{ type: ReactionType; label: string }>> = {
  ALERT: [{ type: "CONFIRMED", label: "Still true" }, { type: "OUTDATED", label: "Outdated" }],
  ASK: [{ type: "HELPFUL", label: "Helpful" }],
  SHARE: [{ type: "HELPFUL", label: "Helpful" }],
};

export function Reactions({ post, disabled }: { post: Post; disabled: boolean }) {
  const qc = useQueryClient();
  const toggle = useMutation({
    mutationFn: ({ type, on }: { type: ReactionType; on: boolean }) => (on ? api.unreact(post.id, type) : api.react(post.id, type)),
    onSuccess: (updated) => {
      qc.setQueryData(["post", post.id], updated);
      void qc.invalidateQueries({ queryKey: ["board"] });
      void qc.invalidateQueries({ queryKey: ["aroundYou"] });
    },
  });
  const options = OPTIONS[post.kind] ?? [];
  if (options.length === 0) return null;

  return (
    <div className="mt-5">
      <div className="flex flex-wrap gap-2" role="group" aria-label="Your reaction">
        {options.map(({ type, label }) => {
          const on = post.myReactions.includes(type);
          return (
            <Chip key={type} selected={on} disabled={disabled || toggle.isPending} onClick={() => toggle.mutate({ type, on })}>
              {label} · {post.reactions[type]}
            </Chip>
          );
        })}
      </div>
      {toggle.isError && <Notice tone="error" className="mt-3">{describeError(toggle.error)}</Notice>}
    </div>
  );
}
