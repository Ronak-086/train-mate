"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, serverNow } from "@/lib/api";
import type { BerthType, Train, TravelClass } from "@/lib/api";
import { BERTH_TYPES, TRAVEL_CLASSES } from "@/lib/api";
import { describeError } from "@/lib/errors-ui";
import { istDateOf } from "@/lib/format";
import { BERTH_LABEL } from "@/lib/labels";
import { useDebounced } from "@/lib/use-debounced";
import { Button, Field, Notice, Select, TextInput } from "@/components/ui";

const COACH_RE = /^[A-Za-z]{1,2}\d{1,2}$/;

function TrainPicker({ onPick }: { onPick: (t: Train) => void }) {
  const [q, setQ] = useState("");
  const debounced = useDebounced(q.trim(), 300);
  const search = useQuery({
    queryKey: ["trainSearch", debounced],
    queryFn: ({ signal }) => api.searchTrains(debounced, signal),
    enabled: debounced.length >= 2,
  });
  return (
    <div>
      <Field label="Train number or name">
        {(id) => <TextInput id={id} value={q} onChange={(e) => setQ(e.target.value)} placeholder="12345 or Express" autoComplete="off" inputMode="search" />}
      </Field>
      {debounced.length >= 2 && search.isPending && <p className="mt-3 text-muted">Searching…</p>}
      {search.isError && <Notice tone="error" className="mt-3">Couldn&apos;t search right now.</Notice>}
      {search.data && search.data.items.length === 0 && <p className="mt-3 text-muted">No train matches &ldquo;{debounced}&rdquo;.</p>}
      <ul className="mt-2">
        {search.data?.items.map((t) => (
          <li key={t.trainNo} className="border-b border-line last:border-b-0">
            <button type="button" onClick={() => onPick(t)} className="block w-full py-3 text-left transition-colors hover:bg-surface active:bg-surface">
              <span className="block text-title font-semibold">{t.trainNo} {t.name}</span>
              <span className="block text-body text-muted">{t.from.name} to {t.to.name}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function TripDetails({ train, onChange }: { train: Train; onChange: () => void }) {
  const router = useRouter();
  const qc = useQueryClient();
  const route = useQuery({ queryKey: ["route", train.trainNo], queryFn: () => api.getRoute(train.trainNo) });
  const stops = route.data?.stops ?? [];

  const [date, setDate] = useState(() => istDateOf(serverNow()));
  const [boarding, setBoarding] = useState("");
  const [dest, setDest] = useState("");
  const [travelClass, setTravelClass] = useState<TravelClass>("3A");
  const [coach, setCoach] = useState("");
  const [seat, setSeat] = useState("");
  const [berth, setBerth] = useState<BerthType | "">("");
  const [error, setError] = useState<string | null>(null);

  // Until the person chooses, boarding is the first stop and destination the last.
  const boardingCode = boarding || stops[0]?.station.code || "";
  const destCode = dest || stops[stops.length - 1]?.station.code || "";
  const boardingIdx = stops.findIndex((s) => s.station.code === boardingCode);

  const create = useMutation({
    mutationFn: () =>
      api.createTrip({
        trainNo: train.trainNo, journeyDate: date, boardingStn: boardingCode, destStn: destCode, travelClass,
        coach: coach.trim().toUpperCase(), seatNo: seat ? Number(seat) : undefined, berthType: berth || undefined,
      }),
    onSuccess: async () => {
      await qc.invalidateQueries();
      router.replace("/home");
    },
    onError: (e) => setError(describeError(e)),
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!COACH_RE.test(coach.trim())) return setError("Coach looks like B3, S5 or A1.");
    if (seat && (!/^\d{1,3}$/.test(seat) || Number(seat) < 1)) return setError("Seat number should be a number, like 34.");
    create.mutate();
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-title font-semibold">{train.trainNo} {train.name}</p>
          <p className="text-muted">{train.from.name} to {train.to.name}</p>
        </div>
        <Button variant="text" className="shrink-0 px-0" onClick={onChange}>Change</Button>
      </div>

      {route.isPending ? (
        <p className="mt-4 text-muted">Loading the route…</p>
      ) : route.isError ? (
        <Notice tone="error" className="mt-4">Couldn&apos;t load this train&apos;s route.</Notice>
      ) : (
        <>
          <Field label="Journey date" className="mt-4">
            {(id) => <TextInput id={id} type="date" value={date} onChange={(e) => setDate(e.target.value)} />}
          </Field>
          <Field label="Boarding station" className="mt-4">
            {(id) => (
              <Select id={id} value={boardingCode} onChange={(e) => { setBoarding(e.target.value); setDest(""); }}>
                {stops.slice(0, -1).map((s) => <option key={s.station.code} value={s.station.code}>{s.station.name}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Getting off at" className="mt-4">
            {(id) => (
              <Select id={id} value={destCode} onChange={(e) => setDest(e.target.value)}>
                {stops.slice(boardingIdx + 1).map((s) => <option key={s.station.code} value={s.station.code}>{s.station.name}</option>)}
              </Select>
            )}
          </Field>
          <Field label="Class" className="mt-4">
            {(id) => <Select id={id} value={travelClass} onChange={(e) => setTravelClass(e.target.value as TravelClass)}>{TRAVEL_CLASSES.map((c) => <option key={c} value={c}>{c}</option>)}</Select>}
          </Field>
          <Field label="Coach" className="mt-4">
            {(id) => <TextInput id={id} value={coach} onChange={(e) => setCoach(e.target.value)} placeholder="B3" maxLength={3} autoCapitalize="characters" autoComplete="off" />}
          </Field>
          <Field label="Seat number (optional)" className="mt-4">
            {(id) => <TextInput id={id} value={seat} onChange={(e) => setSeat(e.target.value)} placeholder="34" inputMode="numeric" maxLength={3} />}
          </Field>
          <Field label="Berth (optional)" className="mt-4">
            {(id) => (
              <Select id={id} value={berth} onChange={(e) => setBerth(e.target.value as BerthType | "")}>
                <option value="">Not sure</option>
                {BERTH_TYPES.map((b) => <option key={b} value={b}>{BERTH_LABEL[b]}</option>)}
              </Select>
            )}
          </Field>
          {error && <Notice tone="error" className="mt-4">{error}</Notice>}
          <Button type="submit" block className="mt-6" disabled={create.isPending}>{create.isPending ? "Adding…" : "Add my train"}</Button>
          <p className="mt-3 text-center text-small text-muted">Your seat number isn&apos;t shown on your posts.</p>
        </>
      )}
    </form>
  );
}

export function AddTripForm() {
  const [train, setTrain] = useState<Train | null>(null);
  return train ? <TripDetails train={train} onChange={() => setTrain(null)} /> : <TrainPicker onPick={setTrain} />;
}
