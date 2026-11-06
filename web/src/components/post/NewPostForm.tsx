"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { BerthType, CreatePostBody, PostCategory, PostKind, Scope, Trip } from "@/lib/api";
import { BERTH_TYPES } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { newKey } from "@/lib/id";
import { BERTH_LABEL, CATEGORY_LABEL, KIND_SHORT, POSTABLE_KINDS, scopeOptions } from "@/lib/labels";
import { Button, Chip, Field, Notice, Select, TextInput } from "@/components/ui";

const TITLE_COPY: Record<string, { label: string; placeholder: string }> = {
  HELP: { label: "What do you need help with?", placeholder: "Help with luggage at Lumding" },
  ASK: { label: "Your question", placeholder: "Which platform at the next stop?" },
  ALERT: { label: "What's happening?", placeholder: "AC is weak in B3" },
};
const CATEGORIES: PostCategory[] = ["LUGGAGE", "MEDICAL", "FOOD", "TRANSLATION", "OTHER"];

export function NewPostForm({ trip }: { trip: Trip }) {
  const router = useRouter();
  const qc = useQueryClient();
  const startBerth: BerthType = trip.berthType ?? "UPPER";

  const [kind, setKind] = useState<PostKind>("SWAP");
  const [have, setHave] = useState<BerthType>(startBerth);
  const [want, setWant] = useState<BerthType>(startBerth === "LOWER" ? "UPPER" : "LOWER");
  const [title, setTitle] = useState("");
  const [category, setCategory] = useState<PostCategory | "">("");
  const [urgent, setUrgent] = useState(false);
  const [note, setNote] = useState("");
  const [scope, setScope] = useState<Scope>("COACH");
  const [error, setError] = useState<string | null>(null);
  const [idempotencyKey] = useState(newKey);

  const create = useMutation({
    mutationFn: (body: CreatePostBody) => api.createPost(body, idempotencyKey),
    onSuccess: async () => {
      await Promise.all([qc.invalidateQueries({ queryKey: ["board"] }), qc.invalidateQueries({ queryKey: ["aroundYou"] })]);
      router.replace("/board");
    },
    onError: (e) => setError(describeError(e)),
  });

  const pickKind = (k: PostKind) => {
    setKind(k);
    setScope("COACH");
    setError(null);
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (kind === "SWAP" && have === want) return setError("Pick two different berth types to swap.");
    if (kind !== "SWAP" && !title.trim()) return setError(`Add ${kind === "ASK" ? "your question" : "a short summary"} first.`);
    const body: CreatePostBody = { tripId: trip.id, kind, scope, note: note.trim() || undefined };
    if (kind === "SWAP") Object.assign(body, { offeringBerth: have, wantsBerth: want });
    else Object.assign(body, { title: title.trim() });
    if (kind === "HELP") Object.assign(body, { category: category || undefined, isUrgent: urgent || undefined });
    create.mutate(body);
  };

  const copy = TITLE_COPY[kind];
  const berthOptions = BERTH_TYPES.map((b) => (
    <option key={b} value={b}>
      {BERTH_LABEL[b]}
    </option>
  ));

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" noValidate>
      <main className="flex-1 px-5 pt-7">
        <div className="flex items-baseline justify-between">
          <h1 className="text-h1 font-bold">New post</h1>
          <Link href="/board" className="text-tab text-muted underline transition-colors hover:text-ink">
            Cancel
          </Link>
        </div>

        <p id="kind-label" className="mt-3 text-body font-medium">What do you need?</p>
        <div className="mt-1.5 flex flex-wrap gap-2" role="group" aria-labelledby="kind-label">
          {POSTABLE_KINDS.map((k) => (
            <Chip key={k} selected={kind === k} onClick={() => pickKind(k)}>
              {KIND_SHORT[k]}
            </Chip>
          ))}
        </div>

        {kind === "SWAP" ? (
          <>
            <Field label="I have" className="mt-4">
              {(id) => <Select id={id} value={have} onChange={(e) => setHave(e.target.value as BerthType)}>{berthOptions}</Select>}
            </Field>
            <Field label="I want" className="mt-4">
              {(id) => <Select id={id} value={want} onChange={(e) => setWant(e.target.value as BerthType)}>{berthOptions}</Select>}
            </Field>
          </>
        ) : (
          <>
            <Field label={copy.label} className="mt-4">
              {(id) => <TextInput id={id} value={title} maxLength={80} placeholder={copy.placeholder} onChange={(e) => setTitle(e.target.value)} />}
            </Field>
            {kind === "HELP" && (
              <>
                <Field label="Type of help (optional)" className="mt-4">
                  {(id) => (
                    <Select id={id} value={category} onChange={(e) => setCategory(e.target.value as PostCategory | "")}>
                      <option value="">Choose one</option>
                      {CATEGORIES.map((c) => (
                        <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>
                      ))}
                    </Select>
                  )}
                </Field>
                <label className="mt-4 flex items-center gap-3 text-body font-medium">
                  <input type="checkbox" checked={urgent} onChange={(e) => setUrgent(e.target.checked)} className="size-5 accent-ink" />
                  This is urgent
                </label>
              </>
            )}
          </>
        )}

        <Field label="Note (optional)" className="mt-4">
          {(id) => (
            <TextInput
              id={id}
              value={note}
              maxLength={500}
              placeholder={kind === "SWAP" ? "Family of 2, want to sit together" : "Anything that helps"}
              onChange={(e) => setNote(e.target.value)}
            />
          )}
        </Field>

        <Field label="Who can see this" className="mt-4">
          {(id) => (
            <Select id={id} value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
              {scopeOptions(kind, trip.coach).map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </Select>
          )}
        </Field>

        <p className="mt-4 text-small leading-[1.35] text-muted">
          {kind === "SWAP" ? "Swaps only, no payments. Railway staff have the final say." : "Emergency? Tell railway staff or call 139."}
        </p>
        {error && <Notice tone="error" className="mt-4">{error}</Notice>}
      </main>
      <div className="px-5 pb-5 pt-4">
        <Button type="submit" block disabled={create.isPending}>
          {create.isPending ? "Posting…" : "Post"}
        </Button>
      </div>
    </form>
  );
}
