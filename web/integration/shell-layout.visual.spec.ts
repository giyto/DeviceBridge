import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }, testInfo) => {
  const preference = testInfo.project.name.endsWith("-light") ? "light" : "dark";
  await page.addInitScript((theme) => {
    localStorage.setItem(
      "devicebridge.theme.v1",
      JSON.stringify({ version: 1, preference: theme }),
    );
  }, preference);
});

const WIDE_PROJECTS = new Set([
  "chrome-1920",
  "chrome-1920-light",
  "edge-1920",
  "edge-1920-light",
]);

test("shell keeps its reading order and reflows without horizontal overflow", async ({
  page,
}, testInfo) => {
  await page.route("**/web-manifest.json", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      webAssetVersion: "sha256-visual-test",
    }),
  }));
  await page.route("**/api/v1/session/challenge", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      challengeId: "visual-challenge",
      expiresAtEpochMillis: 4_102_444_800_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    }),
  }));

  await page.goto("/");
  const expectedTheme = testInfo.project.name.endsWith("-light") ? "light" : "dark";
  await expect(page.locator("html")).toHaveAttribute("data-theme", expectedTheme);
  const themeToggle = page.locator('[data-role="theme-control"]');
  await expect(themeToggle).toHaveAttribute("data-active-theme", expectedTheme);
  const oppositeTheme = expectedTheme === "dark" ? "light" : "dark";
  await themeToggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", oppositeTheme);
  await themeToggle.click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", expectedTheme);
  await expect(page.locator(".site-header")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "DeviceBridge доступен" })).toBeVisible();
  const warning = page.locator('[data-role="security-warning"]');
  const warningDetail = page.locator('[data-role="security-warning-detail"]');
  await expect(warning).toBeVisible();
  await expect(warning).toHaveAttribute("aria-expanded", "true");
  await expect(warningDetail).toBeVisible();
  const statusMotion = await page.locator('[data-role="status"]').evaluate((element) => ({
    transitionDuration: getComputedStyle(element).transitionDuration,
    pulseAnimation: getComputedStyle(element.querySelector(".status-card__pulse")!).animationName,
  }));
  expect(statusMotion).toEqual({ transitionDuration: "0s", pulseAnimation: "none" });

  const layout = page.locator(".shell-layout");
  const connection = page.locator(".shell-layout__connection");
  const workspace = page.locator(".shell-layout__workspace");
  const [connectionBox, workspaceBox] = await Promise.all([
    connection.boundingBox(),
    workspace.boundingBox(),
  ]);
  expect(connectionBox).not.toBeNull();
  expect(workspaceBox).not.toBeNull();

  if (WIDE_PROJECTS.has(testInfo.project.name)) {
    expect(connectionBox!.x).toBeLessThan(workspaceBox!.x);
    expect(Math.abs(connectionBox!.y - workspaceBox!.y)).toBeLessThanOrEqual(1);
  } else {
    expect(workspaceBox!.y).toBeGreaterThan(connectionBox!.y + connectionBox!.height);
  }

  const overflow = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    return Array.from(document.querySelectorAll<HTMLElement>("body *"))
      .filter((element) => {
        const style = getComputedStyle(element);
        if (style.display === "none" || style.visibility === "hidden") return false;
        const rect = element.getBoundingClientRect();
        return rect.left < -1 || rect.right > viewportWidth + 1;
      })
      .map((element) => element.className || element.tagName);
  });
  expect(overflow).toEqual([]);
  await expect(layout).toHaveScreenshot("shell-layout.png");
  await warning.click();
  await expect(warning).toBeVisible();
  await expect(warning).toHaveAttribute("aria-expanded", "false");
  await expect(warningDetail).toBeHidden();
  await expect(layout).toHaveScreenshot("shell-layout-warning-hidden.png");

  await page.reload();
  await expect(page.getByRole("heading", { name: "DeviceBridge доступен" })).toBeVisible();
  await expect(warning).toHaveAttribute("aria-expanded", "false");
  await expect(warningDetail).toBeHidden();
  await warning.click();
  await expect(warning).toHaveAttribute("aria-expanded", "true");
  await expect(warningDetail).toBeVisible();
});
test("text composer and long-link actions keep visual and keyboard order", async ({
  page,
}) => {
  await page.addInitScript(() => {
    sessionStorage.setItem("devicebridge.session.v1", "visual-session-token");
  });
  await page.route("**/web-manifest.json", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      webAssetVersion: "sha256-visual-test",
    }),
  }));
  await page.route("**/api/v1/status", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      sessionId: "visual-session",
      connected: true,
      activeSessionCount: 1,
      effectiveFileLimitBytes: 1_073_741_824,
      deviceName: "Google Pixel 8",
    }),
  }));
  await page.routeWebSocket("**/api/v1/events", (socket) => {
    socket.onMessage((message) => {
      const value = JSON.parse(
        typeof message === "string" ? message : message.toString(),
      ) as { type?: string };
      if (value.type !== "session.auth") return;
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "authenticated-visual",
        type: "session.authenticated",
        timestamp: 2_000,
      }));
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "snapshot-visual",
        type: "text.snapshot",
        timestamp: 2_001,
        items: [{
          messageId: "long-link-visual",
          timestamp: 2_000,
          content: "https://example.com/very/long/path/without/a/layout/break?first=abcdefghijklmnopqrstuvwxyz&second=0123456789",
          contentKind: "LINK",
          direction: "ANDROID_TO_BROWSER",
          senderLabel: "Телефон",
          status: "DELIVERED",
        }],
      }));
    });
  });

  await page.goto("/");
  const card = page.locator('[data-message-id="long-link-visual"]');
  await expect(card).toBeVisible();
  await expect(card.getByText("Доставлено")).toBeVisible();

  const draft = page.locator("#text-draft");
  await draft.fill("Тест keyboard order");
  await draft.focus();
  await page.keyboard.press("Tab");
  await expect(page.locator('[data-action="send-text"]')).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(card.getByRole("button", { name: "Открыть ссылку" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(card.getByRole("button", { name: "Копировать" })).toBeFocused();

  const overflow = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    return Array.from(document.querySelectorAll<HTMLElement>("body *"))
      .filter((element) => {
        const style = getComputedStyle(element);
        if (style.display === "none" || style.visibility === "hidden") return false;
        const rect = element.getBoundingClientRect();
        return rect.left < -1 || rect.right > viewportWidth + 1;
      })
      .map((element) => element.className || element.tagName);
  });
  expect(overflow).toEqual([]);
  await expect(page.locator(".shell-layout")).toHaveScreenshot("text-feed-long-link.png");
});
test("file cards keep metadata, stages and applicable actions inside their bounds", async ({
  page,
}) => {
  await page.addInitScript(() => {
    sessionStorage.setItem("devicebridge.session.v1", "visual-file-session-token");
  });
  await page.route("**/web-manifest.json", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ protocolVersion: 1, webAssetVersion: "sha256-visual-test" }),
  }));
  await page.route("**/api/v1/status", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      sessionId: "visual-file-session",
      connected: true,
      activeSessionCount: 1,
      effectiveFileLimitBytes: 1_073_741_824,
      deviceName: "Google Pixel 8",
    }),
  }));
  await page.routeWebSocket("**/api/v1/events", (socket) => {
    socket.onMessage((message) => {
      const value = JSON.parse(
        typeof message === "string" ? message : message.toString(),
      ) as { type?: string };
      if (value.type !== "session.auth") return;
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "authenticated-files",
        type: "session.authenticated",
        timestamp: 3_000,
      }));
      const metadata = (
        transferId: string,
        displayName: string,
        direction: "ANDROID_TO_BROWSER" | "BROWSER_TO_ANDROID",
        mimeType = "application/octet-stream",
      ) => ({
        transferId,
        displayName,
        sizeBytes: 100_000_000,
        mimeType,
        sha256: "a".repeat(64),
        direction,
      });
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "snapshot-files",
        type: "file.snapshot",
        timestamp: 3_001,
        items: [
          {
            metadata: metadata(
              "incoming-long",
              `${"очень-длинное-имя-файла-".repeat(7)}video.mp4`,
              "ANDROID_TO_BROWSER",
              "video/mp4",
            ),
            status: "CONNECTING",
            bytesTransferred: 0,
            speedBytesPerSecond: 0,
          },
          {
            metadata: metadata("uploading", "archive.zip", "BROWSER_TO_ANDROID", "application/zip"),
            status: "TRANSFERRING",
            bytesTransferred: 37_000_000,
            speedBytesPerSecond: 2_000_000,
          },
          {
            metadata: metadata("failed", "failed.pdf", "ANDROID_TO_BROWSER", "application/pdf"),
            status: "FAILED",
            bytesTransferred: 0,
            speedBytesPerSecond: 0,
          },
          {
            metadata: metadata("cancelled", "cancelled.txt", "BROWSER_TO_ANDROID", "text/plain"),
            status: "CANCELLED",
            bytesTransferred: 0,
            speedBytesPerSecond: 0,
          },
        ],
      }));
    });
  });

  await page.goto("/");
  const fileSection = page.locator('[data-role="file-transfer"]');
  await expect(fileSection.locator('[data-transfer-id="incoming-long"]')).toBeVisible();
  await expect(fileSection.locator('[data-transfer-id="uploading"] progress'))
    .toHaveAttribute("value", "37000000");
  await expect(fileSection.locator('[data-transfer-id="uploading"] .file-card__progress'))
    .toContainText("37%");
  const reducedMotion = await fileSection.locator('[data-transfer-id="uploading"]')
    .evaluate((card) => ({
      itemAnimation: getComputedStyle(card).animationName,
      progressTransition: getComputedStyle(card.querySelector("progress")!).transitionDuration,
    }));
  expect(reducedMotion).toEqual({ itemAnimation: "none", progressTransition: "0s" });
  await expect(
    fileSection.locator('[data-transfer-id="incoming-long"] .file-card__actions button'),
  ).toHaveText(["Скачать", "Отменить"]);
  await expect(
    fileSection.locator('[data-transfer-id="failed"] .file-card__actions button'),
  ).toHaveText(["Повторить"]);
  await expect(
    fileSection.locator('[data-transfer-id="cancelled"] .file-card__actions button'),
  ).toHaveText(["Повторить"]);

  const firstDownload = fileSection
    .locator('[data-transfer-id="incoming-long"]')
    .getByRole("button", { name: "Скачать" });
  await firstDownload.focus();
  await page.keyboard.press("Tab");
  await expect(
    fileSection.locator('[data-transfer-id="incoming-long"]').getByRole("button", { name: "Отменить" }),
  ).toBeFocused();

  const overflow = await fileSection.evaluate((section) => {
    const sectionRect = section.getBoundingClientRect();
    return Array.from(section.querySelectorAll<HTMLElement>("*"))
      .filter((element) => {
        const style = getComputedStyle(element);
        if (style.display === "none" || style.visibility === "hidden") return false;
        const rect = element.getBoundingClientRect();
        return rect.left < sectionRect.left - 1 || rect.right > sectionRect.right + 1;
      })
      .map((element) => element.className || element.tagName);
  });
  expect(overflow).toEqual([]);
  await expect(fileSection).toHaveScreenshot("file-cards-long-name.png");
});

test("waiting for the phone keeps a clear status and a check-now action", async ({ page }) => {
  await page.route("**/web-manifest.json", (route) => route.abort("connectionrefused"));

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Телефон недоступен" }))
    .toBeVisible({ timeout: 15_000 });
  const retry = page.getByRole("button", { name: "Проверить сейчас" });
  await expect(retry).toBeVisible();
  await expect(page.locator('[data-role="status"]')).toHaveAttribute("data-state", "waiting");
  await expect(page.locator('[data-role="status"]')).toHaveScreenshot("waiting-status.png");
});

test("pairing error restores focus through a keyboard-only flow", async ({ page }) => {
  await page.route("**/web-manifest.json", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ protocolVersion: 1, webAssetVersion: "sha256-keyboard-test" }),
  }));
  await page.route("**/api/v1/session/challenge", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      challengeId: "keyboard-challenge",
      expiresAtEpochMillis: 4_102_444_800_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    }),
  }));
  await page.route("**/api/v1/session/confirm", (route) => route.fulfill({
    status: 400,
    contentType: "application/json",
    body: JSON.stringify({
      error: {
        code: "INVALID_CODE",
        message: "Неверный код",
        attemptsRemaining: 4,
      },
    }),
  }));

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "DeviceBridge доступен" })).toBeVisible();
  const input = page.locator("#pairing-code");
  const remember = page.locator("#remember-browser");
  const pair = page.getByRole("button", { name: "Подключить браузер" });

  await page.keyboard.press("Tab");
  await expect(page.locator(".skip-link")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.locator('[data-role="theme-control"]')).toBeFocused();
  await page.keyboard.press("Tab");
  const warning = page.locator('[data-role="security-warning"]');
  await expect(warning).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(warning).toHaveAttribute("aria-expanded", "false");
  await expect(warning).toBeFocused();
  await page.keyboard.press("Space");
  await expect(warning).toHaveAttribute("aria-expanded", "true");
  await expect(warning).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(input).toBeFocused();
  await page.keyboard.type("123456");
  await page.keyboard.press("Tab");
  await expect(remember).toBeFocused();
  // Checked by default: Space turns it off and back on.
  await expect(remember).toBeChecked();
  await page.keyboard.press("Space");
  await expect(remember).not.toBeChecked();
  await page.keyboard.press("Space");
  await expect(remember).toBeChecked();
  await page.keyboard.press("Shift+Tab");
  await expect(input).toBeFocused();
  await page.keyboard.press("Tab");
  await page.keyboard.press("Tab");
  await expect(pair).toBeFocused();
  await page.keyboard.press("Enter");

  await expect(page.locator('[data-role="session-detail"]')).toContainText("4");
  await expect(input).toBeFocused();
});

test("primary action states stay visible without layout shift", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "chrome-1920-light", "One light-theme visual matrix is sufficient.");
  await page.route("**/web-manifest.json", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({ protocolVersion: 1, webAssetVersion: "sha256-state-test" }),
  }));
  await page.route("**/api/v1/session/challenge", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      challengeId: "state-challenge",
      expiresAtEpochMillis: 4_102_444_800_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    }),
  }));
  let releaseConfirm = (): void => undefined;
  const confirmGate = new Promise<void>((resolve) => {
    releaseConfirm = resolve;
  });
  await page.route("**/api/v1/session/confirm", async (route) => {
    await confirmGate;
    await route.fulfill({
      status: 400,
      contentType: "application/json",
      body: JSON.stringify({ error: { code: "INVALID_CODE", message: "Неверный код", attemptsRemaining: 4 } }),
    });
  });

  await page.goto("/");
  const panel = page.locator('[data-role="session-panel"]');
  const button = page.locator('[data-action="pair"]');
  await page.locator("#pairing-code").fill("123456");
  const initialBox = await button.boundingBox();
  expect(initialBox).not.toBeNull();

  await button.focus();
  await expect(panel).toHaveScreenshot("primary-focused-light.png");
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  await button.hover();
  await expect(panel).toHaveScreenshot("primary-hover-light.png");
  await page.mouse.down();
  await expect(panel).toHaveScreenshot("primary-pressed-light.png");
  await page.mouse.up();
  await expect(button).toBeDisabled();
  await expect(button).toHaveAttribute("aria-busy", "true");
  await expect(button).toContainText("Ожидаем подтверждение");
  await expect(panel).toHaveScreenshot("primary-loading-disabled-light.png");

  const loadingBox = await button.boundingBox();
  const panelBox = await panel.boundingBox();
  expect(loadingBox).not.toBeNull();
  expect(panelBox).not.toBeNull();
  expect(Math.abs(loadingBox!.x - initialBox!.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(loadingBox!.y - initialBox!.y)).toBeLessThanOrEqual(1);
  expect(loadingBox!.x + loadingBox!.width).toBeLessThanOrEqual(panelBox!.x + panelBox!.width);
  expect(loadingBox!.y + loadingBox!.height).toBeLessThanOrEqual(panelBox!.y + panelBox!.height);

  releaseConfirm();
  await expect(page.locator("#pairing-code")).toBeFocused();
});
