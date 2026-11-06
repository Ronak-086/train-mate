import { expect, type APIRequestContext, type Browser, type Page } from "@playwright/test";

export const BASE = "http://localhost:3100";

/** Fresh seed data and a frozen clock at 09:06 IST on 25 Oct 2026, the moment the designs show. */
export async function resetWorld(request: APIRequestContext) {
  expect((await request.post("/api/dev/reset")).status()).toBe(204);
  expect((await request.post("/api/dev/clock", { data: { iso: "2026-10-25T09:06:00+05:30" } })).ok()).toBe(true);
}

export async function signIn(page: Page, name: string) {
  await page.goto("/login");
  await page.getByRole("button", { name: new RegExp(name.replace(".", "\\.")) }).click();
  await expect(page).toHaveURL(/\/home$/);
}

/** A separate browser session for another traveller. */
export async function pageAs(browser: Browser, name: string): Promise<Page> {
  const ctx = await browser.newContext({ baseURL: BASE, viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, locale: "en-IN" });
  const page = await ctx.newPage();
  await signIn(page, name);
  return page;
}

export const tab = (page: Page, name: string) => page.getByRole("navigation", { name: "Main" }).getByRole("link", { name });
