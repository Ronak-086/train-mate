import { expect, test } from "@playwright/test";
import { resetWorld, signIn, tab } from "../e2e/helpers";

// The whole stack: browser, Next.js, the Java API, PostgreSQL and Redis. Nothing here is mocked.
// The API is also called directly (port 8081) to prove what the browser did reached the database.
const API = process.env.E2E_API_URL ?? "http://localhost:8081";
const as = (user: string) => ({ headers: { Authorization: `Bearer dev:${user}` } });

test.beforeEach(async ({ request }) => resetWorld(request));

test("Home shows live train status that the Java backend computed", async ({ page }) => {
  await signIn(page, "Asha K.");
  await expect(page.getByText("12 min late")).toBeVisible();
  await expect(page.getByText("Likely 10–15 min late. Reaches New Delhi around 2:00–2:15 pm.")).toBeVisible();
  await expect(page.getByText("Lumding Jn")).toBeVisible();
  await expect(page.getByText("9:20–9:35 am")).toBeVisible();
  await expect(page.getByText("Last seen at Nagaon, 8:42 am · updated 4 min ago")).toBeVisible();
  await expect(page.getByText("12345 Express · 25 Oct")).toBeVisible();
  await expect(page.getByText("Coach B3")).toBeVisible();
});

test("relative times use the server's clock even when the device clock is far off", async ({ page }) => {
  await page.clock.setFixedTime(new Date("2031-06-01T12:00:00Z"));
  await signIn(page, "Asha K.");
  await expect(page.getByText("Last seen at Nagaon, 8:42 am · updated 4 min ago")).toBeVisible();
});

test("the full route comes from the timetable in PostgreSQL", async ({ page }) => {
  await signIn(page, "Asha K.");
  await page.getByRole("link", { name: /Train 12345 status/ }).click();
  await expect(page.getByRole("listitem").filter({ hasText: "Nagaon" })).toContainText("Passed");
  await expect(page.getByRole("listitem").filter({ hasText: "Lumding Jn" })).toContainText("Expected 9:20–9:35 am");
  await expect(page.getByRole("listitem").filter({ hasText: "New Delhi" })).toContainText("Expected 2:00–2:15 pm");
  await expect(page.getByRole("listitem")).toHaveCount(5);
});

test("a different traveller sees their own coach", async ({ page }) => {
  await signIn(page, "Rahul V.");
  await expect(page.getByText("Coach B4")).toBeVisible();
  await expect(page.getByText("12 min late")).toBeVisible();
});

test("the profile shows trips, and a rename reaches the database", async ({ page, request }) => {
  await signIn(page, "Asha K.");
  await tab(page, "Profile").click();
  await expect(page.getByRole("listitem").filter({ hasText: "12345 Express" })).toContainText("Coach B3 · Seat 34");
  await page.getByLabel("Your name").fill("Asha Kumar");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Saved.")).toBeVisible();
  expect((await (await request.get(`${API}/api/me`, as("u_me"))).json()).displayName).toBe("Asha Kumar");
  await page.reload();
  await expect(page.getByLabel("Your name")).toHaveValue("Asha Kumar");
});

test("adding a train works, the backend's own validation messages reach the screen, and removing it works", async ({ page, request }) => {
  await signIn(page, "Asha K.");
  await page.goto("/trips/new");
  await page.getByLabel("Train number or name").fill("12345");
  await page.getByRole("button", { name: /12345 Express/ }).click();
  await page.getByLabel("Coach").fill("B3");
  await page.getByRole("button", { name: "Add my train" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "already added" })).toBeVisible(); // 409 TRIP_EXISTS from Java

  await page.getByRole("button", { name: "Change" }).click();
  await page.getByLabel("Train number or name").fill("sample");
  await page.getByRole("button", { name: /12346 Sample Mail/ }).click();
  await page.getByLabel("Coach").fill("s2");
  await page.getByLabel("Seat number (optional)").fill("14");
  await page.getByRole("button", { name: "Add my train" }).click();
  await expect(page).toHaveURL(/\/home$/);

  const trips = (await (await request.get(`${API}/api/trips/mine`, as("u_me"))).json()).items;
  expect(trips.map((t: { trainNo: string }) => t.trainNo).sort()).toEqual(["12345", "12346"]);
  expect(trips.find((t: { trainNo: string }) => t.trainNo === "12346")).toMatchObject({ coach: "S2", seatNo: 14 });

  await tab(page, "Profile").click();
  const row = page.getByRole("listitem").filter({ hasText: "12346 Sample Mail" });
  await row.getByRole("button", { name: "Remove" }).click();
  await row.getByRole("button", { name: "Yes, remove" }).click();
  await expect(page.getByText("12346 Sample Mail")).toHaveCount(0);
  expect((await (await request.get(`${API}/api/trips/mine`, as("u_me"))).json()).items).toHaveLength(1);
});

test("a traveller with no trip is invited to add one", async ({ page }) => {
  await signIn(page, "Sana M.");
  await tab(page, "Profile").click();
  await page.getByRole("button", { name: "Remove" }).click();
  await page.getByRole("button", { name: "Yes, remove" }).click();
  await tab(page, "Home").click();
  await expect(page.getByText("Where are you travelling?")).toBeVisible();
});

test("a rejected sign-in is noticed and sends the person back to the sign-in screen", async ({ page }) => {
  await page.goto("/login");
  await page.evaluate(() => localStorage.setItem("trainmate.token", "garbage"));
  await page.goto("/home");
  await expect(page).toHaveURL(/\/login$/); // the Java API answered 401 and the app signed out
});

test("deleting the account removes the data and signs out", async ({ page, request }) => {
  await signIn(page, "Asha K.");
  await tab(page, "Profile").click();
  await page.getByRole("button", { name: "Delete my account" }).click();
  await page.getByRole("button", { name: "Yes, delete everything" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect((await (await request.get(`${API}/api/trips/mine`, as("u_me"))).json()).items).toHaveLength(0);
});
