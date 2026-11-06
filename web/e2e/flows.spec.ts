import { expect, test } from "@playwright/test";
import { pageAs, resetWorld, signIn, tab } from "./helpers";

test.beforeEach(async ({ request }) => resetWorld(request));

test("relative times use the server's clock, even when the device clock is far off", async ({ page }) => {
  await page.clock.setFixedTime(new Date("2026-10-24T12:00:00Z")); // a day behind the mock server
  await signIn(page, "Asha K.");
  await expect(page.getByText("Last seen at Nagaon, 8:42 am · updated 4 min ago")).toBeVisible();
  await expect(page.getByRole("link", { name: /AC is weak in B3/ })).toContainText("10 min ago");
});

test.describe("Post detail", () => {
  test("questions: react and reply", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.getByRole("link", { name: /Which platform at the next stop\?/ }).click();
    await expect(page.getByRole("heading", { name: "Which platform at the next stop?" })).toBeVisible();
    await expect(page.getByText("Kavya N. · Coach B3")).toBeVisible();
    await expect(page.getByRole("heading", { name: "3 replies" })).toBeVisible();
    await expect(page.getByText("Usually platform 2 here.")).toBeVisible();

    const helpful = page.getByRole("button", { name: /Helpful/ });
    await expect(helpful).toHaveAttribute("aria-pressed", "false");
    await helpful.click();
    await expect(helpful).toHaveAttribute("aria-pressed", "true");
    await expect(helpful).toContainText("Helpful · 1");
    await helpful.click();
    await expect(helpful).toContainText("Helpful · 0");

    await page.getByLabel("Write a reply").fill("Thanks, going with platform 2");
    await page.getByRole("button", { name: "Send" }).click();
    await expect(page.getByText("Thanks, going with platform 2")).toBeVisible();
    await expect(page.getByRole("heading", { name: "4 replies" })).toBeVisible();
    await expect(page.getByLabel("Write a reply")).toHaveValue("");
  });

  test("alerts: show agreement and let people confirm or call it outdated", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.getByRole("link", { name: /AC is weak in B3/ }).click();
    await expect(page.getByText("5 passengers say this is still true.")).toBeVisible();
    await page.getByRole("button", { name: /Still true/ }).click();
    await expect(page.getByText("6 passengers say this is still true.")).toBeVisible();
    await expect(page.getByRole("button", { name: /Outdated/ })).toContainText("Outdated · 0");
  });

  test("a deleted or hidden post says so instead of breaking", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/p_999");
    await expect(page.getByText("We couldn't find that post.")).toBeVisible();
  });

  test("the author can close a post, which takes it off the board", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/p_1");
    await expect(page.getByRole("heading", { name: "Upper berth for lower berth" })).toBeVisible();
    await expect(page.getByText("Has")).toBeVisible();
    await expect(page.getByText("Upper berth", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Close post" }).click();
    await expect(page.getByText("The poster closed this post.")).toBeVisible();
    await page.getByRole("link", { name: "Back to Board" }).click();
    await expect(page.getByRole("link", { name: /Upper berth for lower berth/ })).toHaveCount(0);
  });

  test("deleting asks first", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/p_1");
    await page.getByRole("button", { name: "Delete" }).click();
    await expect(page.getByText("Delete this post?")).toBeVisible();
    await page.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByRole("heading", { name: "Upper berth for lower berth" })).toBeVisible();
    await page.getByRole("button", { name: "Delete" }).click();
    await page.getByRole("button", { name: "Yes, delete" }).click();
    await expect(page).toHaveURL(/\/board$/);
    await expect(page.getByRole("link", { name: /Upper berth for lower berth/ })).toHaveCount(0);
  });
});

test.describe("Chats and Chat (match the design)", () => {
  test("lists the conversation and opens the accepted swap chat", async ({ page }) => {
    await signIn(page, "Asha K.");
    await tab(page, "Chats").click();
    await expect(page.getByRole("heading", { name: "Chats" })).toBeVisible();
    const row = page.getByRole("link", { name: /Upper berth for lower berth/ });
    await expect(row).toContainText("Priya S. · Accepted");
    await expect(row).toContainText("Okay. I will check with the TTE first.");
    await row.click();

    await expect(page.getByRole("heading", { name: "Upper berth for lower berth" })).toBeVisible();
    await expect(page.getByText("Seat swap · Coach B3")).toBeVisible();
    await expect(page.getByText("Swap accepted. Chat stays in the app.")).toBeVisible();
    await expect(page.getByText("Hi, I have a lower berth in B3. Happy to swap.")).toBeVisible();
    await expect(page.getByText("9:02 am")).toBeVisible();
    await expect(page.getByText("Great, thanks. I am in seat 34.")).toBeVisible();
    await expect(page.getByText("9:03 am")).toBeVisible();
    await expect(page.getByText("Okay. I will check with the TTE first.").last()).toBeVisible();
    await expect(page.getByText("Confirm swaps with railway staff or the TTE.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Mark done" })).toBeVisible();
  });

  test("sends a message, which the other person receives", async ({ page, browser }) => {
    await signIn(page, "Asha K.");
    await page.goto("/chats/cv_1");
    await page.getByLabel("Message").fill("I'm walking over now");
    await page.getByRole("button", { name: "Send" }).click();
    await expect(page.getByText("I'm walking over now")).toBeVisible();
    await expect(page.getByLabel("Message")).toHaveValue("");

    const priya = await pageAs(browser, "Priya S.");
    await priya.goto("/chats/cv_1");
    await expect(priya.getByText("I'm walking over now")).toBeVisible();
    await priya.getByLabel("Message").fill("See you at seat 33");
    await priya.getByRole("button", { name: "Send" }).click();
    await expect(page.getByText("See you at seat 33")).toBeVisible({ timeout: 15_000 }); // arrives by polling
  });

  test("both people mark done, the chat closes, and they can rate it", async ({ page, browser }) => {
    await signIn(page, "Asha K.");
    await page.goto("/chats/cv_1");
    await page.getByRole("button", { name: "Mark done" }).click();
    await expect(page.getByRole("button", { name: "Waiting…" })).toBeDisabled();
    await expect(page.getByText("Waiting for Priya S. to mark it done too.")).toBeVisible();

    const priya = await pageAs(browser, "Priya S.");
    await priya.goto("/chats/cv_1");
    await expect(priya.getByText("Asha K. marked this done.")).toBeVisible();
    await priya.getByRole("button", { name: "Mark done" }).click();
    await expect(priya.getByText("Marked done by both of you.")).toBeVisible();
    await expect(priya.getByLabel("Message")).toHaveCount(0);

    await expect(page.getByText("Marked done by both of you.")).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "5 out of 5" }).click();
    await expect(page.getByText("Thanks for rating.")).toBeVisible();
    await page.goto("/board");
    await expect(page.getByRole("link", { name: /Upper berth for lower berth/ })).toHaveCount(0); // matched, so off the board
  });

  test("the back arrow returns to the Chats list", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/chats/cv_1");
    await page.getByRole("link", { name: "Back to Chats" }).click();
    await expect(page).toHaveURL(/\/chats$/);
  });

  test("someone outside the conversation cannot open it", async ({ browser }) => {
    const kavya = await pageAs(browser, "Kavya N.");
    await kavya.goto("/chats/cv_1");
    await expect(kavya.getByText("We couldn't find that chat.")).toBeVisible();
  });
});

test("offering a swap end to end: offer, accept, chat, done, rate", async ({ browser }) => {
  const kavya = await pageAs(browser, "Kavya N.");
  await tab(kavya, "Board").click();
  await kavya.getByRole("link", { name: /Upper berth for lower berth/ }).click();
  await expect(kavya.getByText("Asha K. · Coach B3")).toBeVisible();
  await kavya.getByLabel("Message (optional)").fill("I have a lower berth in seat 7");
  await kavya.getByRole("button", { name: "Offer to swap" }).click();
  await expect(kavya).toHaveURL(/\/chats\/cv_3$/);
  await expect(kavya.getByText("Waiting for Asha K. to accept.")).toBeVisible();
  await expect(kavya.getByText("I have a lower berth in seat 7")).toBeVisible();
  await expect(kavya.getByLabel("Message")).toHaveCount(0); // no chatting before acceptance

  const asha = await pageAs(browser, "Asha K.");
  await asha.goto("/post/p_1");
  await expect(asha.getByRole("heading", { name: "2 offers" })).toBeVisible();
  await asha.getByRole("listitem").filter({ hasText: "Kavya N." }).getByRole("button", { name: "Accept" }).click();
  await expect(asha).toHaveURL(/\/chats\/cv_3$/);
  await expect(asha.getByText("Swap accepted. Chat stays in the app.")).toBeVisible();

  await asha.getByLabel("Message").fill("Great, I'm in seat 34");
  await asha.getByRole("button", { name: "Send" }).click();
  await expect(kavya.getByText("Great, I'm in seat 34")).toBeVisible({ timeout: 15_000 });
  await kavya.getByRole("button", { name: "Mark done" }).click();
  await asha.getByRole("button", { name: "Mark done" }).click();
  await expect(asha.getByText("Marked done by both of you.")).toBeVisible();
  await asha.getByRole("button", { name: "4 out of 5" }).click();
  await expect(asha.getByText("Thanks for rating.")).toBeVisible();
});

test("a declined offer ends the conversation", async ({ browser }) => {
  const kavya = await pageAs(browser, "Kavya N.");
  await kavya.goto("/post/p_1");
  await kavya.getByRole("button", { name: "Offer to swap" }).click();
  const asha = await pageAs(browser, "Asha K.");
  await asha.goto("/chats/cv_3");
  await asha.getByRole("button", { name: "Decline" }).click();
  await expect(asha.getByText("This offer was declined.")).toBeVisible();
  await kavya.reload();
  await expect(kavya.getByText("This offer was declined.")).toBeVisible();
  await expect(kavya.getByLabel("Message")).toHaveCount(0);
});

test.describe("Trips and profile", () => {
  test("adds a second train and removes it", async ({ page }) => {
    await signIn(page, "Asha K.");
    await tab(page, "Profile").click();
    await page.getByRole("link", { name: "Add a train" }).click();
    await page.getByLabel("Train number or name").fill("sample");
    await page.getByRole("button", { name: /12346 Sample Mail/ }).click();
    await expect(page.getByLabel("Journey date")).toHaveValue("2026-10-25");
    await page.getByLabel("Coach").fill("zzz");
    await page.getByRole("button", { name: "Add my train" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Coach looks like" })).toBeVisible();
    await page.getByLabel("Coach").fill("b2");
    await page.getByLabel("Seat number (optional)").fill("5");
    await page.getByLabel("Berth (optional)").selectOption("LOWER");
    await page.getByRole("button", { name: "Add my train" }).click();
    await expect(page).toHaveURL(/\/home$/);

    await tab(page, "Profile").click();
    const trip = page.getByRole("listitem").filter({ hasText: "12346 Sample Mail" });
    await expect(trip).toContainText("Coach B2 · Seat 5");
    await trip.getByRole("button", { name: "Remove" }).click();
    await trip.getByRole("button", { name: "Yes, remove" }).click();
    await expect(page.getByText("12346 Sample Mail")).toHaveCount(0);
    await expect(page.getByText("12345 Express")).toBeVisible();
  });

  test("won't add the same train twice, and says why", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/trips/new");
    await page.getByLabel("Train number or name").fill("12345");
    await page.getByRole("button", { name: /12345 Express/ }).click();
    await page.getByLabel("Coach").fill("B3");
    await page.getByRole("button", { name: "Add my train" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "already added" })).toBeVisible();
  });

  test("destination choices follow the boarding station", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/trips/new");
    await page.getByLabel("Train number or name").fill("12345");
    await page.getByRole("button", { name: /12345 Express/ }).click();
    await expect(page.getByLabel("Getting off at")).toHaveValue("NDLS");
    await page.getByLabel("Boarding station").selectOption("LMG");
    await expect(page.getByLabel("Getting off at").locator("option")).toHaveText(["New Jalpaiguri", "New Delhi"]);
  });

  test("renames me, and the new name persists", async ({ page }) => {
    await signIn(page, "Asha K.");
    await tab(page, "Profile").click();
    const save = page.getByRole("button", { name: "Save" });
    await expect(save).toBeDisabled();
    await page.getByLabel("Your name").fill("Asha Kumar");
    await save.click();
    await expect(page.getByText("Saved.")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Your name")).toHaveValue("Asha Kumar");
  });

  test("deleting the account signs out and removes the data", async ({ page, browser }) => {
    await signIn(page, "Asha K.");
    await tab(page, "Profile").click();
    await page.getByRole("button", { name: "Delete my account" }).click();
    await page.getByRole("button", { name: "Yes, delete everything" }).click();
    await expect(page).toHaveURL(/\/login$/);
    const priya = await pageAs(browser, "Priya S.");
    await tab(priya, "Chats").click();
    await expect(priya.getByText("No chats yet")).toBeVisible();
  });
});

test.describe("Safety", () => {
  test("report a post, then block its author, which hides their posts", async ({ browser }) => {
    const kavya = await pageAs(browser, "Kavya N.");
    await kavya.getByRole("link", { name: /AC is weak in B3/ }).click();
    await kavya.getByRole("button", { name: "Report" }).click();
    await kavya.getByRole("button", { name: "Send report" }).click();
    await expect(kavya.getByRole("alert").filter({ hasText: "Tell us what's wrong" })).toBeVisible();
    await kavya.getByLabel("What's wrong with this post?").fill("Not true");
    await kavya.getByRole("button", { name: "Send report" }).click();
    await expect(kavya.getByText("Thanks. We'll take a look.")).toBeVisible();

    await kavya.getByRole("button", { name: "Block Arun D." }).click();
    await expect(kavya.getByText("Block Arun D.?")).toBeVisible();
    await kavya.getByRole("button", { name: "Yes, block" }).click();
    await expect(kavya).toHaveURL(/\/board$/);
    await expect(kavya.getByRole("link", { name: /AC is weak in B3/ })).toHaveCount(0);
  });
});
