import { expect, test } from "@playwright/test";

// Runs against a build made with NEXT_PUBLIC_AUTH_MODE=firebase and placeholder Firebase settings.
// Real Google sign-in needs a real Firebase project, so it can't run here. These tests cover
// everything around it: the right screen, the guard, and what happens when the popup is closed.

test("signed-out visitors land on a Google sign-in screen, not the dev picker", async ({ page }) => {
  await page.goto("/home");
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
  await expect(page.getByText("Sign in as")).toHaveCount(0);
  await expect(page.getByText("Asha K.")).toHaveCount(0);
  await expect(page.getByText(/first name and an initial/)).toBeVisible();
});

test("the app is closed to signed-out visitors", async ({ page }) => {
  for (const path of ["/home", "/board", "/chats", "/profile", "/post/new", "/status"]) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/login$/);
  }
});

test("a failed sign-in explains itself in plain language and the button works again", async ({ page }) => {
  // The placeholder Firebase settings are rejected, which is what a misconfigured app looks like.
  await page.goto("/login");
  await page.getByRole("button", { name: "Continue with Google" }).click();
  const error = page.locator("main").getByRole("alert");
  await expect(error).toContainText(/Sign-in isn't configured correctly|Couldn't sign you in|No connection/);
  await expect(error).not.toContainText("auth/"); // never a raw error code
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeEnabled();
  await expect(page).toHaveURL(/\/login$/);
});

test("on a bad connection the screen says it is taking long and lets the person try again", async ({ page }) => {
  // Google's servers accept the connection but never answer.
  await page.route(/^https?:\/\/(?!localhost)/, () => {});
  await page.goto("/login");
  await page.getByRole("button", { name: "Continue with Google" }).click();
  const button = page.getByRole("button", { name: "Opening Google…" });
  await expect(button).toBeDisabled();
  await expect(page.getByText(/Taking longer than usual/)).toBeVisible({ timeout: 15_000 });
  await expect(button).toBeEnabled();
  await expect(page).toHaveURL(/\/login$/);
});
