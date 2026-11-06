/**
 * API contract shared by the web client and the mock server. It follows the project
 * spec (sections 8 to 12); deviations are marked "SPEC GAP" and listed in the README.
 * Timestamps are ISO-8601 strings with an explicit +05:30 offset (IST).
 */

export type PostKind = "SWAP" | "HELP" | "ASK" | "SHARE" | "ALERT";
export type Scope = "COACH" | "NEARBY" | "TRAIN";
export type BerthType = "LOWER" | "MIDDLE" | "UPPER" | "SIDE_LOWER" | "SIDE_UPPER" | "WINDOW";
export type TravelClass = "SL" | "3A" | "2A" | "1A" | "CC" | "2S" | "3E";
export type PostStatus = "OPEN" | "MATCHED" | "CLOSED" | "EXPIRED";
export type PostCategory = "LUGGAGE" | "MEDICAL" | "FOOD" | "TRANSLATION" | "CHARGER" | "OTHER";
export type ReactionType = "CONFIRMED" | "HELPFUL" | "OUTDATED";
export type ConversationStatus = "PENDING" | "ACCEPTED" | "DECLINED" | "DONE";
export type StatusSource = "PROVIDER" | "CROWD" | "SCHEDULE";

export const BERTH_TYPES: readonly BerthType[] = ["LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "WINDOW"];
export const TRAVEL_CLASSES: readonly TravelClass[] = ["SL", "3A", "2A", "1A", "CC", "2S", "3E"];
export const POST_CATEGORIES: readonly PostCategory[] = ["LUGGAGE", "MEDICAL", "FOOD", "TRANSLATION", "CHARGER", "OTHER"];
export const REACTION_TYPES: readonly ReactionType[] = ["CONFIRMED", "HELPFUL", "OUTDATED"];

export type Page<T> = { items: T[]; nextCursor: string | null };

export type ProblemDetails = {
  type: string;
  title: string;
  status: number;
  detail: string;
  /** Stable machine-readable code, e.g. SCOPE_NOT_ALLOWED. */
  code: string;
  retryAfterSeconds?: number;
  /** Extra context, e.g. the id of an existing conversation on CONVERSATION_EXISTS. */
  [extra: string]: unknown;
};

// ---- people -------------------------------------------------------------------------

export type PublicUser = { id: string; displayName: string };
export type Me = PublicUser & { email: string | null; trustScore: number; createdAt: string };

// ---- trains -------------------------------------------------------------------------

export type Station = { code: string; name: string };
export type Train = { trainNo: string; name: string; from: Station; to: Station };
export type RouteStop = {
  seq: number;
  station: Station;
  /** "HH:mm" (null at the origin). */
  schedArr: string | null;
  /** "HH:mm" (null at the terminus). */
  schedDep: string | null;
  /** Days after the journey date on which this stop is reached. */
  dayOffset: number;
};

export type StationEta = { station: Station; scheduledAt: string; etaFrom: string; etaTo: string };

export type TrainStatus = {
  trainNo: string;
  date: string;
  source: StatusSource;
  lastStation: Station | null;
  /** When the train was last seen at lastStation (provider time). */
  lastSeenAt: string | null;
  /** SPEC GAP: when we last confirmed the data. Drives "updated 4 min ago". */
  fetchedAt: string | null;
  delayMinutes: number | null;
  delayLowMin: number | null;
  delayHighMin: number | null;
  /** e.g. "10–15 min late". */
  delayLabel: string | null;
  nextStation: StationEta | null;
  destination: StationEta | null;
  stale: boolean;
  /** Plain-language caveat, e.g. "Timetable only. Live position unavailable." */
  notice: string | null;
};

// ---- trips --------------------------------------------------------------------------

export type Trip = {
  id: string;
  trainNo: string;
  trainName: string;
  journeyDate: string;
  boardingStn: Station;
  destStn: Station;
  travelClass: TravelClass;
  coach: string;
  seatNo: number | null;
  berthType: BerthType | null;
  createdAt: string;
};

export type CreateTripBody = {
  trainNo: string;
  journeyDate: string;
  boardingStn: string;
  destStn: string;
  travelClass: TravelClass;
  coach: string;
  seatNo?: number;
  berthType?: BerthType;
  /** 10 digits. Hashed on arrival, never stored or returned in plain text. */
  pnr?: string;
};

// ---- posts --------------------------------------------------------------------------

export type Post = {
  id: string;
  kind: PostKind;
  scope: Scope;
  category: PostCategory | null;
  /** SPEC GAP: the designs show a headline per post; the spec only has `note`. */
  title: string;
  note: string | null;
  isUrgent: boolean;
  offeringBerth: BerthType | null;
  wantsBerth: BerthType | null;
  groupSize: number;
  status: PostStatus;
  trainNo: string;
  journeyDate: string;
  coach: string;
  createdAt: string;
  expiresAt: string;
  author: PublicUser;
  isMine: boolean;
  /** Comments for public kinds; conversations for private kinds. */
  replyCount: number;
  reactions: Record<ReactionType, number>;
  myReactions: ReactionType[];
  /** 3+ CONFIRMED: show prominently. */
  highlighted: boolean;
  /** 3+ OUTDATED: show dimmed. */
  dimmed: boolean;
  /** The caller's own conversation on this post, if any. */
  myConversationId: string | null;
};

export type CreatePostBody = {
  tripId: string;
  kind: PostKind;
  scope?: Scope;
  category?: PostCategory;
  title?: string;
  note?: string;
  offeringBerth?: BerthType;
  wantsBerth?: BerthType;
  groupSize?: number;
  isUrgent?: boolean;
  /** 1 to 24. Defaults to the earlier of 24h and the end of the journey. */
  expiresInHours?: number;
};

export type PatchPostBody = { title?: string; note?: string; status?: "CLOSED" };

export type Comment = {
  id: string;
  requestId: string;
  author: PublicUser;
  body: string;
  createdAt: string;
  isMine: boolean;
};

export type AroundYou = { trip: Trip | null; items: Post[] };

// ---- conversations ------------------------------------------------------------------

export type ConversationMessagePreview = { body: string; createdAt: string; senderId: string };

export type Conversation = {
  id: string;
  requestId: string;
  requestTitle: string;
  kind: PostKind;
  coach: string;
  requester: PublicUser;
  helper: PublicUser;
  iAmRequester: boolean;
  status: ConversationStatus;
  myDone: boolean;
  otherDone: boolean;
  createdAt: string;
  lastMessage: ConversationMessagePreview | null;
};

export type Message = {
  id: number;
  conversationId: string;
  senderId: string;
  body: string;
  createdAt: string;
  isMine: boolean;
};

export type RespondBody = { message?: string };
export type PatchConversationBody = { status: "ACCEPTED" | "DECLINED" | "DONE" };

// ---- trust & safety, account --------------------------------------------------------

export type ReportBody = { targetUserId: string; requestId?: string; commentId?: string; reason: string };
export type ReviewBody = { conversationId: string; rating: number };
export type PatchMeBody = { displayName?: string };
