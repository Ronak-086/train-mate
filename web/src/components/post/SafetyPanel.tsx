"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PublicUser } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { Button, Field, Notice, Textarea } from "@/components/ui";

type Mode = "idle" | "report" | "block";

/** Report a post or person, or block them. Always inline, always confirmable, never one tap from a mistake. */
export function SafetyPanel({ user, requestId, redirectAfterBlock = "/board" }: { user: PublicUser; requestId?: string; redirectAfterBlock?: string }) {
  const router = useRouter();
  const qc = useQueryClient();
  const [mode, setMode] = useState<Mode>("idle");
  const [reported, setReported] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const report = useMutation({
    mutationFn: () => api.report({ targetUserId: user.id, requestId, reason: reason.trim() }),
    onSuccess: () => {
      setReported(true);
      setMode("idle");
      setReason("");
    },
    onError: (e) => setError(describeError(e)),
  });
  const block = useMutation({
    mutationFn: () => api.block(user.id),
    onSuccess: async () => {
      await qc.invalidateQueries();
      router.replace(redirectAfterBlock);
    },
    onError: (e) => setError(describeError(e)),
  });

  const reset = () => {
    setMode("idle");
    setError(null);
  };

  return (
    <section aria-label="Report or block" className="mt-8 border-t border-line pt-4">
      {mode === "idle" && (
        <>
          {reported && <Notice className="mb-3">Thanks. We&apos;ll take a look. You can also block {user.displayName}.</Notice>}
          <div className="flex gap-4">
            {!reported && <Button variant="text" className="px-0" onClick={() => setMode("report")}>Report</Button>}
            <Button variant="text" className="px-0" onClick={() => setMode("block")}>Block {user.displayName}</Button>
          </div>
        </>
      )}

      {mode === "report" && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setError(null);
            if (!reason.trim()) return setError("Tell us what's wrong first.");
            report.mutate();
          }}
        >
          <Field label={`What's wrong with this ${requestId ? "post" : "person"}?`}>
            {(id) => <Textarea id={id} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} placeholder="Spam, rude, unsafe, or untrue" />}
          </Field>
          <div className="mt-3 flex gap-3">
            <Button type="submit" variant="outline" disabled={report.isPending}>{report.isPending ? "Sending…" : "Send report"}</Button>
            <Button variant="text" onClick={reset}>Cancel</Button>
          </div>
        </form>
      )}

      {mode === "block" && (
        <div>
          <p>Block {user.displayName}? You won&apos;t see each other&apos;s posts or messages.</p>
          <div className="mt-3 flex gap-3">
            <Button variant="outline" disabled={block.isPending} onClick={() => block.mutate()}>{block.isPending ? "Blocking…" : "Yes, block"}</Button>
            <Button variant="text" onClick={reset}>Cancel</Button>
          </div>
        </div>
      )}
      {error && <Notice tone="error" className="mt-3">{error}</Notice>}
    </section>
  );
}
