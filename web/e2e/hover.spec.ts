import { expect, test, type Locator } from "@playwright/test";
import { resetWorld, signIn } from "./helpers";

test.beforeEach(async ({ request }) => resetWorld(request));

/** Perceived brightness (0 to 255) of an "rgb(r, g, b)" colour. */
const brightness = (rgb: string) => {
  const [r, g, b] = rgb.match(/\d+/g)!.map(Number);
  return 0.299 * r + 0.587 * g + 0.114 * b;
};
const bg = (l: Locator) => l.evaluate((el) => getComputedStyle(el).backgroundColor);
const fg = (l: Locator) => l.evaluate((el) => getComputedStyle(el).color);

// Hover only exists on devices that have it, so these run with a mouse-capable context.
test.use({ hasTouch: false });

test.describe("hover makes interactive things lighter", () => {
  test("primary buttons and links get lighter", async ({ page }) => {
    await signIn(page, "Asha K.");
    const newPost = page.getByRole("link", { name: "New post" });
    const before = await bg(newPost);
    await newPost.hover();
    await expect.poll(async () => brightness(await bg(newPost))).toBeGreaterThan(brightness(before));
  });

  test("outline buttons get lighter", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/chats/cv_1");
    const markDone = page.getByRole("button", { name: "Mark done" });
    const before = await bg(markDone);
    await markDone.hover();
    await expect.poll(async () => brightness(await bg(markDone))).toBeGreaterThan(brightness(before));
  });

  test("kind chips get lighter, selected and not", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/post/new");
    const help = page.getByRole("button", { name: "Help" });
    const b1 = await bg(help);
    await help.hover();
    await expect.poll(async () => brightness(await bg(help))).toBeGreaterThan(brightness(b1));
    const swap = page.getByRole("button", { name: "Seat swap" });
    const b2 = await bg(swap);
    await swap.hover();
    await expect.poll(async () => brightness(await bg(swap))).toBeGreaterThan(brightness(b2));
  });

  test("list rows, tabs, nav and text buttons get lighter", async ({ page }) => {
    await signIn(page, "Asha K.");
    const row = page.getByRole("link", { name: /AC is weak in B3/ });
    const r0 = await bg(row);
    await row.hover();
    await expect.poll(async () => brightness(await bg(row))).toBeGreaterThan(brightness(r0));

    const board = page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Board" });
    const n0 = await fg(board);
    await board.hover();
    await expect.poll(async () => brightness(await fg(board))).toBeGreaterThan(brightness(n0));

    await board.click();
    const tab = page.getByRole("tab", { name: "Whole train" });
    const t0 = await fg(tab);
    await tab.hover();
    await expect.poll(async () => brightness(await fg(tab))).toBeGreaterThan(brightness(t0));
  });

  test("a disabled button does not react to hover", async ({ page }) => {
    await signIn(page, "Asha K.");
    await page.goto("/chats/cv_1");
    const send = page.getByRole("button", { name: "Send" }); // disabled while the box is empty
    await expect(send).toBeDisabled();
    const before = await bg(send);
    await send.hover({ force: true });
    await page.waitForTimeout(300);
    expect(await bg(send)).toBe(before);
  });
});
