const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-10-25T09:02:00+05:30" to "9:02 am". Reads the wall-clock digits, so it is correct on any device timezone. */
export function formatClock(iso: string): string {
  const h = Number(iso.slice(11, 13));
  return `${h % 12 || 12}:${iso.slice(14, 16)} ${h >= 12 ? "pm" : "am"}`;
}

/** "9:20–9:35 am", or "11:50 am–12:05 pm" when the window crosses noon. */
export function formatRange(fromIso: string, toIso: string): string {
  const a = formatClock(fromIso);
  const b = formatClock(toIso);
  return a.slice(-2) === b.slice(-2) ? `${a.slice(0, -3)}–${b}` : `${a}–${b}`;
}

/** "2026-10-25" to "25 Oct". */
export function formatDay(date: string): string {
  return `${Number(date.slice(8, 10))} ${MONTHS[Number(date.slice(5, 7)) - 1]}`;
}

/** Today's date in IST for a given instant. */
export const istDateOf = (ms: number): string => new Date(ms + 330 * 60_000).toISOString().slice(0, 10);

/** Relative time measured against the server's clock. */
export function timeAgo(iso: string, nowMs: number): string {
  const sec = Math.max(0, Math.round((nowMs - Date.parse(iso)) / 1000));
  if (sec < 45) return "just now";
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} min ago`;
  const hours = Math.round(min / 60);
  if (hours < 24) return `${hours} ${hours === 1 ? "hour" : "hours"} ago`;
  const days = Math.round(hours / 24);
  return `${days} ${days === 1 ? "day" : "days"} ago`;
}

/** "Family of 2, want to sit together" to "Family of 2". */
export function firstClause(note: string | null, max = 28): string | null {
  if (!note) return null;
  const clause = note.split(/[,.\n;]/)[0].trim();
  if (!clause) return null;
  return clause.length > max ? `${clause.slice(0, max - 1).trimEnd()}…` : clause;
}

/** "09:10" to "9:10 am". */
export function formatHHmm(hhmm: string): string {
  const h = Number(hhmm.slice(0, 2));
  return `${h % 12 || 12}:${hhmm.slice(3, 5)} ${h >= 12 ? "pm" : "am"}`;
}

/** "09:10" plus 12 minutes is "09:22". Wraps past midnight. */
export function addMinutesHHmm(hhmm: string, minutes: number): string {
  const total = (((Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5)) + minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}
