import { createHash } from "node:crypto";
import type {
  BerthType, ConversationStatus, PostCategory, PostKind, PostStatus, ReactionType, Scope, TravelClass,
} from "@/lib/api/types";
import { HOUR, MIN, now } from "./clock";
import { trainName } from "./data";

export type UserRec = { id: string; displayName: string; email: string | null; trustScore: number; createdAt: number; blocked: boolean; deleted: boolean };
export type TripRec = {
  id: string; userId: string; trainNo: string; journeyDate: string; boardingStn: string; destStn: string;
  travelClass: TravelClass; coach: string; seatNo: number | null; berthType: BerthType | null; pnrHash: string | null; createdAt: number;
};
export type PostRec = {
  id: string; userId: string; tripId: string; kind: PostKind; scope: Scope; category: PostCategory | null; title: string;
  note: string | null; isUrgent: boolean; offeringBerth: BerthType | null; wantsBerth: BerthType | null; groupSize: number;
  status: PostStatus; trainNo: string; journeyDate: string; coach: string; createdAt: number; expiresAt: number; hidden: boolean;
};
export type CommentRec = { seq: number; id: string; postId: string; userId: string; body: string; createdAt: number; hidden: boolean };
export type ReactionRec = { postId: string; userId: string; type: ReactionType };
export type ConvRec = {
  id: string; postId: string; requesterId: string; helperId: string; status: ConversationStatus; doneBy: Set<string>; createdAt: number; updatedAt: number;
};
export type MsgRec = { id: number; convId: string; senderId: string; body: string; createdAt: number };
export type ReportRec = { reporterId: string; targetUserId: string; postId: string | null; commentId: string | null; reason: string; createdAt: number };
export type ReviewRec = { convId: string; reviewerId: string; rating: number };

export class Store {
  users = new Map<string, UserRec>();
  trips = new Map<string, TripRec>();
  posts = new Map<string, PostRec>();
  comments = new Map<string, CommentRec>();
  reactions: ReactionRec[] = [];
  convs = new Map<string, ConvRec>();
  messages: MsgRec[] = [];
  reports: ReportRec[] = [];
  blocks: Array<{ blockerId: string; blockedId: string }> = [];
  reviews: ReviewRec[] = [];
  idempotency = new Map<string, { at: number; status: number; body: unknown }>();
  /** Last time each train's live data was "refreshed" (drives staleness in the status mock). */
  statusFetchedAt = new Map<string, number>();
  private seq = { post: 0, trip: 0, comment: 0, conv: 0, msg: 0 };

  nextId(kind: "post" | "trip" | "conv"): string {
    const prefix = { post: "p", trip: "t", conv: "cv" }[kind];
    return `${prefix}_${++this.seq[kind]}`;
  }
  nextCommentSeq = () => ++this.seq.comment;
  nextMsgId = () => ++this.seq.msg;
}

export const hashPnr = (pnr: string) => createHash("sha256").update(`trainmate-mock:${pnr}`).digest("hex");

function seed(s: Store) {
  const t = now();
  const ago = (min: number) => t - min * MIN;
  const date = "2026-10-25";

  const user = (id: string, displayName: string) =>
    s.users.set(id, { id, displayName, email: `${id.slice(2)}@example.test`, trustScore: 0, createdAt: t - 30 * 24 * HOUR, blocked: false, deleted: false });
  user("u_me", "Asha K.");
  user("u_priya", "Priya S.");
  user("u_arun", "Arun D.");
  user("u_meena", "Meena R.");
  user("u_kavya", "Kavya N.");
  user("u_dev", "Dev P.");
  user("u_imran", "Imran Q.");
  user("u_rahul", "Rahul V.");
  user("u_sana", "Sana M.");

  const trip = (uid: string, coach: string, seatNo: number, berthType: BerthType) => {
    const id = s.nextId("trip");
    s.trips.set(id, { id, userId: uid, trainNo: "12345", journeyDate: date, boardingStn: "GHY", destStn: "NDLS", travelClass: "3A", coach, seatNo, berthType, pnrHash: null, createdAt: ago(600) });
    return id;
  };
  const meTrip = trip("u_me", "B3", 34, "UPPER");
  const priyaTrip = trip("u_priya", "B3", 33, "LOWER");
  const arunTrip = trip("u_arun", "B3", 12, "LOWER");
  const meenaTrip = trip("u_meena", "B3", 40, "UPPER");
  const kavyaTrip = trip("u_kavya", "B3", 7, "MIDDLE");
  trip("u_dev", "B3", 51, "SIDE_LOWER");
  trip("u_imran", "B3", 22, "LOWER");
  trip("u_rahul", "B4", 20, "LOWER");
  trip("u_sana", "B6", 9, "UPPER");
  void priyaTrip;

  const post = (p: Partial<PostRec> & Pick<PostRec, "userId" | "tripId" | "kind" | "title">, minutesAgo: number): PostRec => {
    const id = s.nextId("post");
    const rec: PostRec = {
      id, scope: "COACH", category: null, note: null, isUrgent: false, offeringBerth: null, wantsBerth: null, groupSize: 1, status: "OPEN",
      trainNo: "12345", journeyDate: date, coach: "B3", createdAt: ago(minutesAgo), expiresAt: ago(minutesAgo) + 24 * HOUR, hidden: false, ...p,
    };
    s.posts.set(id, rec);
    return rec;
  };

  const swap = post({ userId: "u_me", tripId: meTrip, kind: "SWAP", title: "Upper berth for lower berth", offeringBerth: "UPPER", wantsBerth: "LOWER", groupSize: 2, note: "Family of 2, want to sit together" }, 40);
  const help = post({ userId: "u_meena", tripId: meenaTrip, kind: "HELP", category: "LUGGAGE", title: "Help with luggage at Lumding" }, 60);
  const alert = post({ userId: "u_arun", tripId: arunTrip, kind: "ALERT", title: "AC is weak in B3" }, 10);
  const ask = post({ userId: "u_kavya", tripId: kavyaTrip, kind: "ASK", title: "Which platform at the next stop?" }, 22);

  // 5 passengers confirm the AC alert
  for (const u of ["u_priya", "u_meena", "u_kavya", "u_dev", "u_imran"]) s.reactions.push({ postId: alert.id, userId: u, type: "CONFIRMED" });

  // 3 replies on the question
  const comment = (postId: string, userId: string, body: string, minutesAgo: number) => {
    const seq = s.nextCommentSeq();
    const id = `c_${seq}`;
    s.comments.set(id, { seq, id, postId, userId, body, createdAt: ago(minutesAgo), hidden: false });
  };
  comment(ask.id, "u_priya", "Usually platform 2 here.", 20);
  comment(ask.id, "u_imran", "Platform 2, I think.", 18);
  comment(ask.id, "u_arun", "It was 3 last time. Check the board.", 15);

  // The provider data for 12345 was last fetched 4 minutes ago ("updated 4 min ago").
  s.statusFetchedAt.set("12345:2026-10-25", ago(4));

  // accepted swap chat (9:02 to 9:04)
  const conv = (post: PostRec, requesterId: string, helperId: string, status: ConversationStatus, minutesAgo: number) => {
    const id = s.nextId("conv");
    s.convs.set(id, { id, postId: post.id, requesterId, helperId, status, doneBy: new Set(), createdAt: ago(minutesAgo), updatedAt: ago(minutesAgo) });
    return id;
  };
  const msg = (convId: string, senderId: string, body: string, minutesAgo: number) =>
    s.messages.push({ id: s.nextMsgId(), convId, senderId, body, createdAt: ago(minutesAgo) });

  const c1 = conv(swap, "u_me", "u_priya", "ACCEPTED", 4);
  msg(c1, "u_priya", "Hi, I have a lower berth in B3. Happy to swap.", 4);
  msg(c1, "u_me", "Great, thanks. I am in seat 34.", 3);
  msg(c1, "u_priya", "Okay. I will check with the TTE first.", 2);

  // one pending offer on the luggage request
  const c2 = conv(help, "u_meena", "u_arun", "PENDING", 50);
  msg(c2, "u_arun", "I can help at Lumding.", 50);
}

export function createStore(): Store {
  const s = new Store();
  seed(s);
  return s;
}

const g = globalThis as unknown as { __trainMateStore?: Store };
/** One store per server process, surviving hot reloads in dev. */
export const getStore = (): Store => (g.__trainMateStore ??= createStore());
export const resetStore = (): Store => (g.__trainMateStore = createStore());
export { trainName };
