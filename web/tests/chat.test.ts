import { beforeEach, describe, expect, it } from "vitest";
import { MINUTE, advance, call, del, freshWorld, get, patch, post } from "./helpers";

beforeEach(freshWorld);

describe("seeded chat matches the Chat design", () => {
  it("returns the accepted swap with its three messages in order", async () => {
    const c = (await get("/conversations/cv_1")).body;
    expect(c).toMatchObject({ requestTitle: "Upper berth for lower berth", status: "ACCEPTED", iAmRequester: true, coach: "B3", kind: "SWAP" });
    const m = (await get("/conversations/cv_1/messages")).body.items;
    expect(m.map((x: { body: string; isMine: boolean; createdAt: string }) => [x.body, x.isMine, x.createdAt.slice(11, 16)])).toEqual([
      ["Hi, I have a lower berth in B3. Happy to swap.", false, "09:02"],
      ["Great, thanks. I am in seat 34.", true, "09:03"],
      ["Okay. I will check with the TTE first.", false, "09:04"],
    ]);
  });
  it("lists my conversations with a last-message preview", async () => {
    const items = (await get("/conversations/mine")).body.items;
    expect(items).toHaveLength(1);
    expect(items[0].lastMessage.body).toBe("Okay. I will check with the TTE first.");
  });
  it("fetches only newer messages with ?after", async () => {
    const all = (await get("/conversations/cv_1/messages")).body.items;
    expect((await get(`/conversations/cv_1/messages?after=${all[0].id}`)).body.items).toHaveLength(2);
  });
  it("is private to its two participants", async () => {
    expect((await get("/conversations/cv_1", "u_kavya")).status).toBe(404);
    expect((await get("/conversations/cv_1/messages", "u_kavya")).status).toBe(404);
  });
});

describe("sending messages", () => {
  it("lets either side send once accepted", async () => {
    const sent = await post("/conversations/cv_1/messages", { body: "On my way to seat 33" });
    expect(sent.status).toBe(201);
    expect(sent.body.isMine).toBe(true);
    const theirs = (await get("/conversations/cv_1/messages", "u_priya")).body.items.at(-1);
    expect(theirs).toMatchObject({ body: "On my way to seat 33", isMine: false });
  });
  it("validates the message", async () => {
    expect((await post("/conversations/cv_1/messages", { body: "   " })).status).toBe(400);
    expect((await post("/conversations/cv_1/messages", { body: "x".repeat(1001) })).status).toBe(400);
  });
  it("refuses messages while the offer is only pending", async () => {
    expect((await post("/conversations/cv_2/messages", { body: "hello" }, "u_arun")).body.code).toBe("CHAT_NOT_ACCEPTED");
  });
});

describe("responding to a post", () => {
  it("creates a pending conversation with an optional first message", async () => {
    const r = await post("/requests/p_1/respond", { message: "I have a lower berth in seat 7" }, "u_kavya");
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ status: "PENDING", iAmRequester: false, helper: { id: "u_kavya" } });
    expect(r.body.lastMessage.body).toBe("I have a lower berth in seat 7");
    expect((await get("/board")).body.items.find((p: { id: string }) => p.id === "p_1").replyCount).toBe(2);
    expect((await get("/requests/p_1", "u_kavya")).body.myConversationId).toBe(r.body.id);
  });
  it("allows one response per person, and says where it is", async () => {
    const first = await post("/requests/p_1/respond", {}, "u_kavya");
    const again = await post("/requests/p_1/respond", {}, "u_kavya");
    expect(again.status).toBe(409);
    expect(again.body).toMatchObject({ code: "CONVERSATION_EXISTS", conversationId: first.body.id });
  });
  it("caps the first message at 200 characters", async () => {
    expect((await post("/requests/p_1/respond", { message: "x".repeat(201) }, "u_kavya")).status).toBe(400);
  });
  it("is only for private kinds, never your own post, never out of scope", async () => {
    expect((await post("/requests/p_4/respond", {}, "u_priya")).body.code).toBe("RESPOND_NOT_ALLOWED");
    expect((await post("/requests/p_1/respond", {}, "u_me")).status).toBe(404);
    expect((await post("/requests/p_1/respond", {}, "u_rahul")).status).toBe(404);
  });
});

describe("accept, decline, done", () => {
  it("only the poster can accept or decline", async () => {
    const r = await post("/requests/p_1/respond", {}, "u_kavya");
    expect((await patch(`/conversations/${r.body.id}`, { status: "ACCEPTED" }, "u_kavya")).body.code).toBe("NOT_REQUESTER");
    const ok = await patch(`/conversations/${r.body.id}`, { status: "ACCEPTED" });
    expect(ok.body.status).toBe("ACCEPTED");
    expect((await post(`/conversations/${r.body.id}/messages`, { body: "Great" }, "u_kavya")).status).toBe(201);
    expect((await patch(`/conversations/${r.body.id}`, { status: "DECLINED" })).body.code).toBe("INVALID_TRANSITION");
  });
  it("a declined offer stays closed and drops off the reply count", async () => {
    const r = await post("/requests/p_1/respond", {}, "u_kavya");
    await patch(`/conversations/${r.body.id}`, { status: "DECLINED" });
    expect((await post(`/conversations/${r.body.id}/messages`, { body: "hi" }, "u_kavya")).body.code).toBe("CHAT_NOT_ACCEPTED");
    expect((await get("/requests/p_1")).body.replyCount).toBe(1);
    expect((await post("/requests/p_1/respond", {}, "u_kavya")).body.code).toBe("CONVERSATION_EXISTS");
  });
  it("needs both people to mark done; then the chat closes and the post is matched", async () => {
    const first = (await patch("/conversations/cv_1", { status: "DONE" })).body;
    expect(first).toMatchObject({ status: "ACCEPTED", myDone: true, otherDone: false }); // still waiting on the other side
    const seenByPriya = (await get("/conversations/cv_1", "u_priya")).body;
    expect(seenByPriya).toMatchObject({ myDone: false, otherDone: true });
    expect((await patch("/conversations/cv_1", { status: "DONE" })).body.myDone).toBe(true); // idempotent
    const done = (await patch("/conversations/cv_1", { status: "DONE" }, "u_priya")).body;
    expect(done.status).toBe("DONE");
    expect((await get("/requests/p_1")).body.status).toBe("MATCHED");
    expect((await get("/board")).body.items.map((p: { id: string }) => p.id)).not.toContain("p_1");
    expect((await post("/conversations/cv_1/messages", { body: "late" })).body.code).toBe("CHAT_CLOSED");
  });
  it("cannot mark done before accepting", async () => {
    expect((await patch("/conversations/cv_2", { status: "DONE" }, "u_arun")).body.code).toBe("INVALID_TRANSITION");
  });
  it("reviews only after done, once each, and moves trust", async () => {
    expect((await post("/reviews", { conversationId: "cv_1", rating: 5 })).body.code).toBe("NOT_DONE");
    await patch("/conversations/cv_1", { status: "DONE" });
    await patch("/conversations/cv_1", { status: "DONE" }, "u_priya");
    expect((await post("/reviews", { conversationId: "cv_1", rating: 5 })).status).toBe(204);
    expect((await post("/reviews", { conversationId: "cv_1", rating: 5 })).body.code).toBe("ALREADY_REVIEWED");
    expect((await post("/reviews", { conversationId: "cv_1", rating: 9 }, "u_priya")).status).toBe(400);
    expect((await post("/reviews", { conversationId: "cv_1", rating: 1 }, "u_priya")).status).toBe(204);
    expect((await get("/me", "u_priya")).body.trustScore).toBe(2);
    expect((await get("/me")).body.trustScore).toBe(-3);
  });
});

describe("blocking and reporting", () => {
  it("blocking hides each person from the other, and unblocking restores them", async () => {
    expect((await post("/users/u_arun/block")).status).toBe(204);
    expect((await get("/board")).body.items.map((p: { id: string }) => p.id)).not.toContain("p_3");
    expect((await get("/board", "u_arun")).body.items.map((p: { id: string }) => p.id)).not.toContain("p_1");
    expect((await del("/users/u_arun/block")).status).toBe(204);
    expect((await get("/board")).body.items.map((p: { id: string }) => p.id)).toContain("p_3");
  });
  it("a blocked pair can no longer message", async () => {
    await post("/users/u_priya/block");
    expect((await post("/conversations/cv_1/messages", { body: "hi" })).body.code).toBe("BLOCKED");
  });
  it("rejects a report that names the wrong person", async () => {
    expect((await post("/reports", { targetUserId: "u_kavya", requestId: "p_3", reason: "x" }, "u_priya")).body.code).toBe("TARGET_MISMATCH");
  });
  it("can't block or report yourself", async () => {
    expect((await post("/users/u_me/block")).status).toBe(400);
    expect((await post("/reports", { targetUserId: "u_me", reason: "x" })).status).toBe(400);
  });
  it("three different reporters hide a post; repeats don't count", async () => {
    const rep = (as: string) => post("/reports", { targetUserId: "u_arun", requestId: "p_3", reason: "Not true" }, as);
    expect((await rep("u_priya")).status).toBe(204);
    expect((await rep("u_priya")).status).toBe(204); // duplicate
    expect((await rep("u_meena")).status).toBe(204);
    expect((await get("/board")).body.items.map((p: { id: string }) => p.id)).toContain("p_3");
    await rep("u_kavya");
    expect((await get("/board")).body.items.map((p: { id: string }) => p.id)).not.toContain("p_3");
    expect((await get("/requests/p_3")).status).toBe(410);
    expect((await get("/requests/p_3", "u_arun")).status).toBe(200); // the author can still see it
  });
  it("three reporters hide a comment", async () => {
    const id = (await get("/requests/p_4/comments")).body.items[0].id;
    for (const u of ["u_meena", "u_kavya", "u_dev"]) expect((await post("/reports", { targetUserId: "u_priya", requestId: "p_4", commentId: id, reason: "Rude" }, u)).status).toBe(204);
    expect((await get("/requests/p_4/comments")).body.items.map((c: { id: string }) => c.id)).not.toContain(id);
  });
});

describe("dev helpers", () => {
  it("can move the clock and reset the data", async () => {
    await post("/requests/p_1/respond", {}, "u_kavya");
    expect((await post("/dev/reset", {}, null)).status).toBe(204);
    expect((await get("/board")).body.items.find((p: { id: string }) => p.id === "p_1").replyCount).toBe(1);
    expect((await call("POST", "/dev/clock", { as: null, body: { iso: "2026-10-25T10:00:00+05:30" } })).body.now).toBe("2026-10-25T10:00:00+05:30");
  });
  it("is deterministic when the clock is frozen", async () => {
    advance(0);
    expect(MINUTE).toBe(60000);
  });
});
