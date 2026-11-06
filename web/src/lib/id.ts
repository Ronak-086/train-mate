/** Random id for Idempotency-Key. crypto.randomUUID needs a secure context, so fall back on plain http (LAN testing). */
export function newKey(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
