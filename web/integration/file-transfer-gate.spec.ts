import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { expect, type Page, type TestInfo, test } from "@playwright/test";
import {
  type FileTransferGateServer,
  startFileTransferGateServer,
} from "./support/fileTransferGateServer";

test("native download keeps the session page alive without page-side payload buffers", async ({
  page,
}, testInfo) => {
  const fixture = await startFileTransferGateServer();

  try {
    const downloadRequestTypes: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).pathname === "/download") {
        downloadRequestTypes.push(request.resourceType());
      }
    });
    await page.goto(fixture.pageUrl);
    await expect(page.getByTestId("session-state")).toHaveText("connected");
    const eventsBeforeDownload = await page.getByTestId("session-events").textContent();
    await expect(
      page.getByRole("link", { name: "Download fixture" }),
    ).toHaveJSProperty("onclick", null);

    const { download, savedPath } = await downloadFixture(
      page,
      testInfo,
      fixture,
    );

    const downloadedBytes = await readFile(savedPath);
    expect(download.suggestedFilename()).toBe(fixture.fileName);
    expect(downloadedBytes.byteLength).toBe(fixture.fileSize);
    expect(createHash("sha256").update(downloadedBytes).digest("hex")).toBe(
      fixture.sha256,
    );
    await expect(page).toHaveURL(fixture.pageUrl);
    await expect(page.getByTestId("session-state")).toHaveText("connected");
    await expect
      .poll(async () => page.getByTestId("session-events").textContent())
      .not.toBe(eventsBeforeDownload);
    expect(downloadRequestTypes).toHaveLength(1);
    expect(downloadRequestTypes).not.toContain("fetch");
    expect(downloadRequestTypes).not.toContain("xhr");
  } finally {
    await fixture.close();
  }
});

test("interrupted native download fails without a false verification step", async ({
  page,
}) => {
  const fixture = await startFileTransferGateServer({ truncateDownload: true });

  try {
    await page.goto(fixture.pageUrl);
    const downloadStarted = page.waitForEvent("download");
    await page.getByRole("link", { name: "Download fixture" }).click();
    const download = await downloadStarted;

    expect(await download.failure()).not.toBeNull();
    await expect(page.locator('input[type="file"]')).toHaveCount(0);
    await expect(page.getByTestId("session-state")).toHaveText("connected");
  } finally {
    await fixture.close();
  }
});

test("incremental SHA-256 hashes 500 MiB with bounded browser heap", async ({
  page,
}, testInfo) => {
  const fixture = await startFileTransferGateServer();

  try {
    await page.goto(fixture.pageUrl);
    const result = await page.evaluate(async () => {
      const benchmark = (
        globalThis as typeof globalThis & {
          runFileHashBenchmark?: (sizeMiB: number) => Promise<{
            digest: string;
            elapsedMs: number;
            heapGrowthBytes: number;
          }>;
        }
      ).runFileHashBenchmark;
      if (!benchmark) throw new Error("File hash benchmark is unavailable");
      return benchmark(500);
    });

    const chunk = deterministicBytes(1024 * 1024);
    const expected = createHash("sha256");
    for (let index = 0; index < 500; index += 1) expected.update(chunk);

    expect(result.digest).toBe(expected.digest("hex"));
    expect(result.heapGrowthBytes).toBeLessThan(96 * 1024 * 1024);
    expect(result.elapsedMs).toBeGreaterThan(0);
    console.info(
      `file-hash-benchmark ${JSON.stringify({
        browser: testInfo.project.name,
        ...result,
      })}`,
    );
  } finally {
    await fixture.close();
  }
}, 60_000);

async function downloadFixture(
  page: Page,
  testInfo: TestInfo,
  fixture: FileTransferGateServer,
) {
  const downloadStarted = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download fixture" }).click();
  const download = await downloadStarted;
  const savedPath = testInfo.outputPath(download.suggestedFilename());
  await download.saveAs(savedPath);
  return { download, savedPath };
}

async function downloadUrl(page: Page, pageUrl: string): Promise<string> {
  const href = await page
    .getByRole("link", { name: "Download fixture" })
    .getAttribute("href");
  if (!href) throw new Error("Download fixture link has no href");
  return new URL(href, pageUrl).toString();
}

function deterministicBytes(size: number): Uint8Array {
  const bytes = new Uint8Array(size);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = (index * 31 + 17) & 0xff;
  }
  return bytes;
}
