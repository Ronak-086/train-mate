"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/components/providers/Providers";
import { Button, Field, LinkButton, Notice, Skeleton, TextInput } from "@/components/ui";
import { api } from "@/lib/api";
import type { Trip } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { formatDay } from "@/lib/format";
import { useMe } from "@/lib/queries";

function NameForm({ name }: { name: string }) {
  const qc = useQueryClient();
  const [value, setValue] = useState(name);
  const save = useMutation({
    mutationFn: () => api.patchMe({ displayName: value.trim() }),
    onSuccess: (me) => qc.setQueryData(["me"], me),
  });
  const changed = value.trim() !== name && value.trim().length > 0;
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (changed) save.mutate();
      }}
    >
      <Field label="Your name">
        {(id) => (
          <div className="flex gap-2.5">
            <TextInput id={id} value={value} maxLength={40} onChange={(e) => setValue(e.target.value)} className="flex-1" />
            <Button type="submit" variant="outline" className="h-[51px]" disabled={!changed || save.isPending}>{save.isPending ? "Saving…" : "Save"}</Button>
          </div>
        )}
      </Field>
      <p className="mt-1.5 text-small text-muted">Other travellers see this on your posts. A first name and initial is plenty.</p>
      {save.isError && <Notice tone="error" className="mt-3">{describeError(save.error)}</Notice>}
      {save.isSuccess && !changed && <p className="mt-2 text-small text-muted" role="status">Saved.</p>}
    </form>
  );
}

function TripRow({ trip }: { trip: Trip }) {
  const qc = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const remove = useMutation({
    mutationFn: () => api.deleteTrip(trip.id),
    onSuccess: async () => {
      setConfirming(false);
      await qc.invalidateQueries();
    },
  });
  return (
    <li className="border-b border-line py-4 last:border-b-0">
      <p className="text-title font-semibold">{trip.trainNo} {trip.trainName}</p>
      <p className="mt-[3px] text-body text-muted">
        {formatDay(trip.journeyDate)} · {trip.boardingStn.name} to {trip.destStn.name} · Coach {trip.coach}
        {trip.seatNo ? ` · Seat ${trip.seatNo}` : ""}
      </p>
      {confirming ? (
        <div className="mt-3">
          <p>Remove this trip? Your posts on it will be deleted.</p>
          <div className="mt-3 flex gap-3">
            <Button variant="outline" disabled={remove.isPending} onClick={() => remove.mutate()}>{remove.isPending ? "Removing…" : "Yes, remove"}</Button>
            <Button variant="text" onClick={() => setConfirming(false)}>Cancel</Button>
          </div>
        </div>
      ) : (
        <Button variant="text" className="mt-1 px-0" onClick={() => setConfirming(true)}>Remove</Button>
      )}
      {remove.isError && <Notice tone="error" className="mt-3">{describeError(remove.error)}</Notice>}
    </li>
  );
}

export default function ProfilePage() {
  const { logout } = useAuth();
  const me = useMe();
  const trips = useQuery({ queryKey: ["trips"], queryFn: () => api.myTrips() });
  const [confirmDelete, setConfirmDelete] = useState(false);
  const deleteAccount = useMutation({ mutationFn: () => api.deleteMe(), onSuccess: () => logout() });

  return (
    <main className="flex-1 px-5 pb-8 pt-[25px]">
      <h1 className="text-h1 font-bold">Profile</h1>

      <div className="mt-5">
        {me.isPending ? <Skeleton className="h-[78px]" /> : me.isError ? <Notice tone="error">Couldn&apos;t load your profile.</Notice> : <NameForm name={me.data.displayName} />}
      </div>

      <h2 className="mt-8 text-h4 font-bold">My trips</h2>
      {trips.isPending ? (
        <Skeleton className="mt-3 h-[84px]" />
      ) : trips.isError ? (
        <Notice tone="error" className="mt-3">Couldn&apos;t load your trips.</Notice>
      ) : trips.data.items.length === 0 ? (
        <p className="mt-2 text-muted">No trips yet.</p>
      ) : (
        <ul className="mt-1">{trips.data.items.map((t) => <TripRow key={t.id} trip={t} />)}</ul>
      )}
      <LinkButton href="/trips/new" variant="outline" block className="mt-3">Add a train</LinkButton>

      <div className="mt-10 border-t border-line pt-4">
        <Button variant="outline" block onClick={logout}>Sign out</Button>
        {confirmDelete ? (
          <div className="mt-5">
            <p>Delete your account? Your trips, posts and chats are removed, and this can&apos;t be undone.</p>
            <div className="mt-3 flex gap-3">
              <Button variant="outline" disabled={deleteAccount.isPending} onClick={() => deleteAccount.mutate()}>{deleteAccount.isPending ? "Deleting…" : "Yes, delete everything"}</Button>
              <Button variant="text" onClick={() => setConfirmDelete(false)}>Cancel</Button>
            </div>
          </div>
        ) : (
          <Button variant="text" className="mt-2 px-0" onClick={() => setConfirmDelete(true)}>Delete my account</Button>
        )}
        {deleteAccount.isError && <Notice tone="error" className="mt-3">{describeError(deleteAccount.error)}</Notice>}
      </div>
      <p className="mt-6 text-center text-small text-muted">Emergency? Tell railway staff or call 139.</p>
    </main>
  );
}
