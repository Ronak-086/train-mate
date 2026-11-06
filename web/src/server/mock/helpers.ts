import { activeTrip } from "./access";
import type { Store, TripRec } from "./store";

export const activeTripOrNull = (s: Store, userId: string): TripRec | null => activeTrip(s, userId) ?? null;

/** Remove a post and everything hanging off it. */
export function removePost(s: Store, postId: string) {
  s.posts.delete(postId);
  for (const c of [...s.comments.values()]) if (c.postId === postId) s.comments.delete(c.id);
  s.reactions = s.reactions.filter((r) => r.postId !== postId);
  for (const cv of [...s.convs.values()]) if (cv.postId === postId) {
    s.convs.delete(cv.id);
    s.messages = s.messages.filter((m) => m.convId !== cv.id);
  }
}
