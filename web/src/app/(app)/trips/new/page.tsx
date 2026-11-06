"use client";

import { AddTripForm } from "@/components/trips/AddTripForm";
import { AppShell, BackLink } from "@/components/ui";

export default function NewTripPage() {
  return (
    <AppShell>
      <header className="flex items-center border-b border-line pb-2 pl-1 pr-5 pt-[11px]">
        <BackLink href="/home" label="Back to Home" />
      </header>
      <main className="flex-1 px-5 pb-8 pt-5">
        <h1 className="text-h1 font-bold">Add my train</h1>
        <p className="mb-5 mt-[5px] text-muted">Find your train to see its status and the people around you.</p>
        <AddTripForm />
      </main>
    </AppShell>
  );
}
