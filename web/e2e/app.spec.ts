import { expect, test } from "@playwright/test";
import { pageAs, resetWorld, signIn, tab } from "./helpers";

test.beforeEach(async ({ request }) => resetWorld(request));

test.describe("signing in", () => {
  test("sends a signed-out visitor to the sign-in screen, and keeps them out of the app", async ({ page }) => {
    await page.goto("/home");
    await expect(page).toHaveURL(/\/login$/);
    await page.goto("/board");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("signs in, survives a reload, and signs out", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.reload();
    await expect(page.getByText("My train")).toBeVisible();
    await tab(page, "Profile").click();
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login$/);
    await page.goto("/home");
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("Home (matches the design)", () => {
  test("shows delay, arrival window, next station, last seen, and the coach feed", async ({ page }) => {
    await signIn(page, "Asha K.");
    await expect(page.getByText("12 min late")).toBeVisible();
    await expect(page.getByText("Likely 10–15 min late. Reaches New Delhi around 2:00–2:15 pm.")).toBeVisible();
    await expect(page.getByText("Lumding Jn")).toBeVisible();
    await expect(page.getByText("9:20–9:35 am")).toBeVisible();
    await expect(page.getByText("Last seen at Nagaon, 8:42 am · updated 4 min ago")).toBeVisible();
    await expect(page.getByText("12345 Express · 25 Oct")).toBeVisible();
    await expect(page.getByText("Coach B3")).toBeVisible();
    await expect(page.getByRole("link", { name: /AC is weak in B3/ })).toContainText("5 passengers agree · 10 min ago");
    await expect(page.getByRole("link", { name: /Which platform at the next stop\?/ })).toContainText("3 replies · 22 min ago");
    await expect(page.getByText("Emergency? Tell railway staff or call 139.")).toBeVisible();
  });

  test("opens the full route from the status card", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.getByRole("link", { name: /Train 12345 status/ }).click();
    await expect(page).toHaveURL(/\/status$/);
    const nagaon = page.getByRole("listitem").filter({ hasText: "Nagaon" });
    await expect(nagaon).toContainText("Passed");
    await expect(nagaon).toContainText("Seen 8:42 am");
    const lumding = page.getByRole("listitem").filter({ hasText: "Lumding Jn" });
    await expect(lumding).toContainText("Next");
    await expect(lumding).toContainText("Expected 9:20–9:35 am");
    await expect(page.getByRole("listitem").filter({ hasText: "New Delhi" })).toContainText("Expected 2:00–2:15 pm");
    await page.getByRole("link", { name: "Back to Home" }).click();
    await expect(page).toHaveURL(/\/home$/);
  });

  test("a traveller with no trip is invited to add one", async ({ page, request }) => {
    await signIn(page, "Sana M.");
    await tab(page, "Profile").click();
    await page.getByRole("button", { name: "Remove" }).click();
    await page.getByRole("button", { name: "Yes, remove" }).click();
    await expect(page.getByText("No trips yet.")).toBeVisible();
    await tab(page, "Home").click();
    await expect(page.getByText("Where are you travelling?")).toBeVisible();
    await expect(page.getByRole("link", { name: "Add my train" })).toBeVisible();
    void request;
  });
});

test.describe("Board", () => {
  test("lists the coach's posts with the design's wording", async ({ page }) => {
    await signIn(page, "Asha K.");
    await tab(page, "Board").click();
    await expect(page.getByRole("heading", { name: "Board" })).toBeVisible();
    await expect(page.getByText("12345 Express · 25 Oct")).toBeVisible();
    const rows = page.getByRole("list").getByRole("listitem");
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0)).toContainText("AC is weak in B3");
    await expect(rows.nth(1)).toContainText("Which platform at the next stop?");
    await expect(rows.nth(2)).toContainText("Upper berth for lower berth");
    await expect(rows.nth(2)).toContainText("Family of 2 · 40 min ago");
    await expect(rows.nth(3)).toContainText("Help with luggage at Lumding");
    await expect(rows.nth(3)).toContainText("1 reply · 1 hour ago");
  });

  test("filters by type and switches between my coach and the whole train", async ({ page }) => {
    await signIn(page, "Asha K.");
    await tab(page, "Board").click();
    const rows = page.getByRole("list").getByRole("listitem");
    await page.getByRole("tab", { name: "Alert" }).click();
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText("AC is weak in B3");
    await page.getByRole("tab", { name: "Swap" }).click();
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText("Upper berth for lower berth");
    await page.getByRole("tab", { name: "All" }).click();
    await expect(rows).toHaveCount(4);
    await page.getByRole("tab", { name: "Whole train" }).click();
    await expect(page.getByText("Nothing here yet")).toBeVisible();
    await page.getByRole("tab", { name: /My coach/ }).click();
    await expect(rows).toHaveCount(4);
  });

  test("a different coach does not see this coach's posts", async ({ browser }) => {
    const rahul = await pageAs(browser, "Rahul V.");
    await tab(rahul, "Board").click();
    await expect(rahul.getByText("Nothing here yet")).toBeVisible();
  });
});

test.describe("New post", () => {
  test("posts a swap with the design's defaults and shows it on the board", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.getByRole("link", { name: "New post" }).click();
    await expect(page.getByLabel("I have")).toHaveValue("UPPER");
    await expect(page.getByLabel("I want")).toHaveValue("LOWER");
    await expect(page.getByLabel("Who can see this")).toHaveValue("COACH");
    await expect(page.getByText("Swaps only, no payments. Railway staff have the final say.")).toBeVisible();
    await page.getByLabel("I have").selectOption("MIDDLE");
    await page.getByLabel("Note (optional)").fill("Travelling with my mother");
    await page.getByRole("button", { name: "Post" }).click();
    await expect(page).toHaveURL(/\/board$/);
    await expect(page.getByRole("link", { name: /Middle berth for lower berth/ })).toContainText("Travelling with my mother · just now");
  });

  test("refuses a swap of the same berth", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/new");
    await page.getByLabel("I want").selectOption("UPPER");
    await page.getByRole("button", { name: "Post" }).click();
    await expect(page.locator("main").getByRole("alert")).toContainText("Pick two different berth types");
    await expect(page).toHaveURL(/\/post\/new$/);
  });

  test("question and alert forms ask for a headline and offer the right audiences", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/new");
    await page.getByRole("button", { name: "Question" }).click();
    await expect(page.getByLabel("Who can see this").locator("option")).toHaveText(["My coach (B3)", "Whole train"]);
    await page.getByRole("button", { name: "Post" }).click();
    await expect(page.locator("main").getByRole("alert")).toContainText("Add your question first");
    await page.getByLabel("Your question").fill("Is the pantry car open?");
    await page.getByLabel("Who can see this").selectOption("TRAIN");
    await page.getByRole("button", { name: "Post" }).click();
    await expect(page).toHaveURL(/\/board$/);
    await page.getByRole("tab", { name: "Whole train" }).click();
    await expect(page.getByRole("link", { name: /Is the pantry car open\?/ })).toBeVisible();
  });

  test("explains the cooldown when posting twice in a row", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/new");
    await page.getByRole("button", { name: "Help" }).click();
    await page.getByLabel("What do you need help with?").fill("Carry a bag");
    await page.getByRole("button", { name: "Post" }).click();
    await expect(page).toHaveURL(/\/board$/);
    await page.goto("/post/new");
    await page.getByRole("button", { name: "Alert" }).click();
    await page.getByLabel("What's happening?").fill("Door is stuck");
    await page.getByRole("button", { name: "Post" }).click();
    await expect(page.locator("main").getByRole("alert")).toContainText("Please wait a moment before posting again. Try again in 120 seconds.");
    await expect(page).toHaveURL(/\/post\/new$/);
  });
});
