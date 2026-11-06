/**
 * Mock clock. Starts at 09:06 IST on 25 Oct 2026 (so the seeded data matches the supplied
 * designs) and then runs in real time. Tests can freeze or jump it with setNow().
 */
const IST_OFFSET_MIN = 330;
const BASE = Date.parse("2026-10-25T09:06:00+05:30");

let startedAt = Date.now();
let fixed: number | null = null;

export const now = (): number => fixed ?? BASE + (Date.now() - startedAt);
export const setNow = (ms: number | null) => {
  fixed = ms;
};
export const resetClock = () => {
  fixed = null;
  startedAt = Date.now();
};

const p2 = (n: number) => String(n).padStart(2, "0");

/** ms to "YYYY-MM-DDTHH:mm:ss+05:30". */
export function iso(ms: number): string {
  const d = new Date(ms + IST_OFFSET_MIN * 60_000);
  return `${d.getUTCFullYear()}-${p2(d.getUTCMonth() + 1)}-${p2(d.getUTCDate())}T${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())}:${p2(d.getUTCSeconds())}+05:30`;
}

/** ms to "YYYY-MM-DD" in IST. */
export const istDate = (ms: number): string => iso(ms).slice(0, 10);

/** Start of an IST calendar day ("YYYY-MM-DD") as ms. */
export const startOfDay = (date: string): number => Date.parse(`${date}T00:00:00+05:30`);

/** Journey date + day offset + "HH:mm" as ms. */
export const atIst = (date: string, hhmm: string, dayOffset = 0): number =>
  startOfDay(date) + dayOffset * 86_400_000 + (Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5))) * 60_000;

export const addDays = (date: string, days: number): string => istDate(startOfDay(date) + days * 86_400_000 + 12 * 3_600_000);
export const MIN = 60_000;
export const HOUR = 3_600_000;
