import { http } from "./client";
import type {
  AroundYou, Comment, Conversation, CreatePostBody, CreateTripBody, Me, Message, Page, PatchConversationBody,
  PatchMeBody, PatchPostBody, Post, PostKind, ReactionType, ReportBody, RespondBody, ReviewBody, RouteStop, Scope,
  Train, TrainStatus, Trip,
} from "./types";

const enc = encodeURIComponent;

export const api = {
  // trains (public)
  searchTrains: (q: string, signal?: AbortSignal) => http.get<{ items: Train[] }>("/trains/search", { query: { q }, signal }),
  getRoute: (trainNo: string) => http.get<{ trainNo: string; stops: RouteStop[] }>(`/trains/${enc(trainNo)}/route`),
  getStatus: (trainNo: string, date: string, signal?: AbortSignal) =>
    http.get<TrainStatus>(`/trains/${enc(trainNo)}/status`, { query: { date }, signal }),

  // trips
  myTrips: () => http.get<{ items: Trip[] }>("/trips/mine"),
  createTrip: (body: CreateTripBody) => http.post<Trip>("/trips", body),
  deleteTrip: (id: string) => http.delete<void>(`/trips/${enc(id)}`),

  // board and posts
  aroundYou: (limit = 5) => http.get<AroundYou>("/feed/around-me", { query: { limit } }),
  board: (q: { train?: string; date?: string; coach?: string; kind?: PostKind; scope?: Scope; cursor?: string; limit?: number }) =>
    http.get<Page<Post>>("/board", { query: q }),
  createPost: (body: CreatePostBody, idempotencyKey?: string) => http.post<Post>("/requests", body, { idempotencyKey }),
  getPost: (id: string) => http.get<Post>(`/requests/${enc(id)}`),
  patchPost: (id: string, body: PatchPostBody) => http.patch<Post>(`/requests/${enc(id)}`, body),
  deletePost: (id: string) => http.delete<void>(`/requests/${enc(id)}`),

  // comments and reactions (public kinds)
  comments: (id: string, after?: string) => http.get<{ items: Comment[] }>(`/requests/${enc(id)}/comments`, { query: { after } }),
  addComment: (id: string, body: string) => http.post<Comment>(`/requests/${enc(id)}/comments`, { body }),
  react: (id: string, type: ReactionType) => http.post<Post>(`/requests/${enc(id)}/reactions`, { type }),
  unreact: (id: string, type: ReactionType) => http.delete<Post>(`/requests/${enc(id)}/reactions`, { query: { type } }),

  // private conversations
  respond: (id: string, body: RespondBody = {}) => http.post<Conversation>(`/requests/${enc(id)}/respond`, body),
  myConversations: () => http.get<{ items: Conversation[] }>("/conversations/mine"),
  getConversation: (id: string) => http.get<Conversation>(`/conversations/${enc(id)}`),
  patchConversation: (id: string, body: PatchConversationBody) => http.patch<Conversation>(`/conversations/${enc(id)}`, body),
  messages: (id: string, after?: number, signal?: AbortSignal) =>
    http.get<{ items: Message[] }>(`/conversations/${enc(id)}/messages`, { query: { after }, signal }),
  sendMessage: (id: string, body: string) => http.post<Message>(`/conversations/${enc(id)}/messages`, { body }),

  // trust & safety
  report: (body: ReportBody) => http.post<void>("/reports", body),
  block: (userId: string) => http.post<void>(`/users/${enc(userId)}/block`),
  unblock: (userId: string) => http.delete<void>(`/users/${enc(userId)}/block`),
  review: (body: ReviewBody) => http.post<void>("/reviews", body),

  // account
  me: () => http.get<Me>("/me"),
  patchMe: (body: PatchMeBody) => http.patch<Me>("/me", body),
  deleteMe: () => http.delete<void>("/me"),
};
