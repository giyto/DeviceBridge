import { expect, test } from "@playwright/test";

test("editable draft supports multiple, cancel, remove, clear and reselect", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto("http://127.0.0.1:4174/integration/file-draft-harness.html");
  const input = page.locator("#file-input");
  await input.setInputFiles([
    { name: "one.txt", mimeType: "text/plain", buffer: Buffer.from("one") },
    { name: "two.txt", mimeType: "text/plain", buffer: Buffer.from("two") },
  ]);
  await expect(page.getByRole("list", { name: "Выбранные файлы" }).getByRole("listitem"))
    .toHaveCount(2);
  await expect(page.getByRole("button", { name: "Подтвердить отправку" })).toBeEnabled();

  await input.setInputFiles([]);
  await expect(page.getByRole("list", { name: "Выбранные файлы" }).getByRole("listitem"))
    .toHaveCount(2);
  await page.getByRole("button", { name: "Удалить one.txt из выбранных" }).click();
  await expect(page.getByText("one.txt", { exact: true })).toHaveCount(0);

  await input.setInputFiles({ name: "one.txt", mimeType: "text/plain", buffer: Buffer.from("one") });
  await expect(page.getByText("one.txt", { exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: "Очистить" }).click();
  await expect(page.getByRole("list", { name: "Выбранные файлы" }).getByRole("listitem"))
    .toHaveCount(0);
  await expect(page.getByRole("button", { name: "Подтвердить отправку" })).toBeDisabled();

  const bodyWidth = await page.locator("body").evaluate((node) => node.scrollWidth);
  expect(bodyWidth).toBeLessThanOrEqual(360);
});

test("draft controls remain usable on tablet and desktop widths", async ({ page }) => {
  for (const width of [768, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("http://127.0.0.1:4174/integration/file-draft-harness.html");
    await page.locator("#file-input").setInputFiles({
      name: `viewport-${width}.txt`,
      mimeType: "text/plain",
      buffer: Buffer.from("file"),
    });
    await expect(page.getByRole("button", { name: `Удалить viewport-${width}.txt из выбранных` }))
      .toBeVisible();
    await expect(page.getByRole("button", { name: "Очистить" })).toBeVisible();
  }
});
