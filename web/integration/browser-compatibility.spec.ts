import { expect, type Page, test, type WebSocketRoute } from "@playwright/test";

const MANIFEST = {
  protocolVersion: 1,
  webAssetVersion: "sha256-compatibility-test",
};
const STATUS = {
  protocolVersion: 1,
  sessionId: "compatibility-session",
  connected: true,
  activeSessionCount: 1,
  effectiveFileLimitBytes: 1_073_741_824,
  deviceName: "Google Pixel 8",
};

test("layout, reload, trusted reconnect and revoke preserve accessible session state", async ({
  page,
}) => {
  let trustedExchangeCount = 0;
  let revoked = false;

  await page.addInitScript(() => {
    if (localStorage.getItem("devicebridge.compatibility-seeded") === null) {
      localStorage.setItem("devicebridge.compatibility-seeded", "1");
      localStorage.setItem("devicebridge.trusted-browser.v1", JSON.stringify({
        version: 1,
        credential: "trusted-compatibility-credential",
        expiresAtEpochMillis: Date.now() + 86_400_000,
      }));
    }
  });
  await installManifest(page);
  await page.route("**/api/v1/session/trusted", async (route) => {
    trustedExchangeCount += 1;
    const body = route.request().postDataJSON() as { trustedCredential?: string };
    expect(body.trustedCredential).toBe("trusted-compatibility-credential");
    if (revoked) {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({
          error: { code: "UNAUTHORIZED", message: "Доверенный браузер отозван" },
        }),
      });
      return;
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        protocolVersion: 1,
        sessionId: STATUS.sessionId,
        token: `compatibility-token-${trustedExchangeCount}`,
        serverTimeEpochMillis: Date.now(),
      }),
    });
  });
  await page.route("**/api/v1/status", async (route) => {
    if (revoked) {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({
          error: { code: "UNAUTHORIZED", message: "Сессия отозвана" },
        }),
      });
      return;
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(STATUS),
    });
  });
  await page.route("**/api/v1/session/challenge", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      protocolVersion: 1,
      challengeId: "compatibility-challenge",
      expiresAtEpochMillis: 4_102_444_800_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    }),
  }));
  await installAuthenticatedSocket(page);

  await page.goto("/");
  await expectConnected(page);
  expect(trustedExchangeCount).toBe(1);
  await expectNoHorizontalOverflow(page);

  const status = page.locator('[data-role="status"]');
  await expect(status).toHaveAttribute("role", "status");
  await expect(status).toHaveAttribute("aria-live", "polite");
  await expect(status).toHaveAttribute("aria-atomic", "true");

  await page.reload();
  await expectConnected(page);
  expect(trustedExchangeCount).toBe(1);

  await page.evaluate(() => {
    sessionStorage.removeItem("devicebridge.session.v1");
  });
  await page.reload();
  await expectConnected(page);
  expect(trustedExchangeCount).toBe(2);

  revoked = true;
  await page.reload();

  await expect(page.getByRole("heading", { name: "Подключите браузер" })).toBeVisible();
  await expect(page.locator("#pairing-code")).toBeVisible();
  await expect(status).toHaveAttribute("data-state", "ready");
  await expect(page.locator('[data-role="status-detail"]')).toContainText("Этот телефон не узнал браузер");
  expect(trustedExchangeCount).toBe(3);
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem("devicebridge.trusted-browser.v1"),
  )).toBeNull();
  await expectNoHorizontalOverflow(page);
});

test("text retry keeps one message and exposes status to keyboard users", async ({ page }) => {
  await installConnectedSession(page, "text-compatibility-token");
  let sendCount = 0;
  const messageIds: string[] = [];
  await page.route("**/api/v1/text", async (route) => {
    sendCount += 1;
    const body = route.request().postDataJSON() as {
      messageId: string;
      content: string;
    };
    messageIds.push(body.messageId);
    expect(body.content).toBe("Тест повторной отправки");
    if (sendCount === 1) {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          error: {
            code: "SESSION_UNAVAILABLE",
            message: "Получатель сейчас недоступен.",
          },
        }),
      });
      return;
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        protocolVersion: 1,
        messageId: body.messageId,
        type: "text.accepted",
        timestamp: Date.now(),
        contentKind: "TEXT",
        status: "DELIVERED",
      }),
    });
  });

  await page.goto("/");
  await expectConnected(page);
  const draft = page.locator("#text-draft");
  await draft.fill("Тест повторной отправки");
  await page.getByRole("button", { name: "Отправить на телефон" }).click();

  const card = page.locator('[data-role="text-feed"] .text-card');
  await expect(card).toHaveCount(1);
  await expect(card.locator(".text-card__status")).toHaveText("Ошибка");
  await expect(page.locator('[data-role="text-error"]')).toHaveAttribute("role", "alert");

  const retry = card.getByRole("button", { name: "Повторить" });
  await retry.focus();
  await page.keyboard.press("Enter");

  await expect(card.locator(".text-card__status")).toHaveText("Доставлено");
  await expect(card.getByRole("button", { name: "Копировать" })).toBeFocused();
  await expect(page.locator('[data-role="text-feed"] .text-card')).toHaveCount(1);
  await expect(page.locator('[data-role="text-announcer"]')).toContainText("Доставлено");
  expect(messageIds).toHaveLength(2);
  expect(messageIds[1]).toBe(messageIds[0]);
});

test("file cancel and retry keep one transfer, keyboard focus and live feedback", async ({
  page,
}) => {
  const metadata = {
    transferId: "compatibility-file",
    displayName: "compatibility-video.mp4",
    sizeBytes: 1_000,
    mimeType: "video/mp4",
    sha256: "a".repeat(64),
    direction: "ANDROID_TO_BROWSER",
  };
  const snapshot = (
    status: "TRANSFERRING" | "CANCELLED" | "CONNECTING",
    messageId: string,
  ) => ({
    protocolVersion: 1,
    messageId,
    type: "file.snapshot",
    timestamp: Date.now(),
    items: [{
      metadata,
      status,
      bytesTransferred: status === "TRANSFERRING" ? 500 : 0,
      speedBytesPerSecond: status === "TRANSFERRING" ? 100 : 0,
    }],
  });

  await installConnectedSession(
    page,
    "file-compatibility-token",
    snapshot("TRANSFERRING", "compatibility-file-started"),
  );
  await page.route("**/api/v1/transfers/compatibility-file", (route) => {
    expect(route.request().method()).toBe("DELETE");
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(snapshot("CANCELLED", "compatibility-file-cancelled")),
    });
  });
  await page.route("**/api/v1/transfers/compatibility-file/retry", (route) => {
    expect(route.request().method()).toBe("POST");
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(snapshot("CONNECTING", "compatibility-file-retry")),
    });
  });

  await page.goto("/");
  await expectConnected(page);
  const card = page.locator('[data-transfer-id="compatibility-file"]');
  await expect(card).toBeVisible();
  await expect(card.locator(".file-card__status")).toHaveText("Передаётся");

  const cancel = card.getByRole("button", { name: "Отменить" });
  await cancel.focus();
  await page.keyboard.press("Enter");

  const retry = card.getByRole("button", { name: "Повторить" });
  await expect(retry).toBeFocused();
  await expect(card.locator(".file-card__status")).toHaveText("Отменено");
  await expect(page.locator('[data-role="file-announcer"]')).toContainText("Отменено");
  await page.keyboard.press("Space");

  await expect(card.getByRole("button", { name: "Отменить" })).toBeFocused();
  await expect(card.locator(".file-card__status")).toHaveText("Ожидает скачивания");
  await expect(page.locator('[data-role="file-transfer-list"] .file-card')).toHaveCount(1);
});

test("pasted clipboard image joins the file draft and uploads only after confirmation", async ({
  page,
}) => {
  const offers: Array<{ items: Array<{ displayName: string; mimeType: string; sizeBytes: number }> }> = [];
  await installConnectedSession(page, "paste-compatibility-token");
  await page.route("**/api/v1/files", (route) => {
    const body = JSON.parse(route.request().postData() ?? "{}");
    offers.push(body);
    const item = body.items[0];
    return route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        protocolVersion: 1,
        messageId: "paste-offer-accepted",
        type: "file.snapshot",
        timestamp: Date.now(),
        items: [{ metadata: item, status: "QUEUED", bytesTransferred: 0, speedBytesPerSecond: 0 }],
      }),
    });
  });

  await page.goto("/");
  await expectConnected(page);
  await expect(page.locator('[data-role="file-paste-hint"]')).toContainText("Ctrl+V");

  await page.evaluate(() => {
    const transfer = new DataTransfer();
    transfer.items.add(new File([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])], "image.png", {
      type: "image/png",
    }));
    document.body.dispatchEvent(new ClipboardEvent("paste", {
      bubbles: true,
      cancelable: true,
      clipboardData: transfer,
    }));
  });

  const draft = page.locator('[data-role="file-selection"]');
  await expect(draft).toContainText(/Скриншот \d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}\.png/);
  await page.waitForTimeout(300);
  expect(offers).toHaveLength(0);

  await page.getByRole("button", { name: "Подтвердить отправку" }).click();
  await expect.poll(() => offers.length).toBe(1);
  expect(offers[0]!.items[0]!.displayName).toMatch(/^Скриншот .+\.png$/);
  expect(offers[0]!.items[0]!.mimeType).toBe("image/png");
  expect(offers[0]!.items[0]!.sizeBytes).toBe(8);
});

test("an HTTPS page notifies once about a phone text while the tab is hidden", async ({
  page,
  context,
}) => {
  const origin = "https://devicebridge.test";
  await context.grantPermissions(["notifications"], { origin });
  await page.addInitScript(() => {
    const attention = { hidden: false };
    const shown: { title: string; body?: string }[] = [];
    Object.assign(window, { __attention: attention, __notifications: shown });
    Object.defineProperty(document, "visibilityState", {
      get: () => (attention.hidden ? "hidden" : "visible"),
    });
    document.hasFocus = () => !attention.hidden;
    const Real = window.Notification;
    class Recording extends Real {
      constructor(title: string, options?: NotificationOptions) {
        super(title, options);
        shown.push({ title, body: options?.body });
      }
    }
    window.Notification = Recording as typeof Notification;
  });
  // The dev server's files under an HTTPS origin: the page is a secure context, as in secure mode.
  await page.route(`${origin}/**`, async (route) => {
    const url = new URL(route.request().url());
    const response = await route.fetch({ url: "http://127.0.0.1:4176" + url.pathname + url.search });
    await route.fulfill({ response });
  });
  await page.addInitScript(() => sessionStorage.setItem("devicebridge.session.v1", "notification-token"));
  await installManifest(page);
  await page.route("**/api/v1/status", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify(STATUS),
  }));
  let phone: WebSocketRoute | undefined;
  await page.routeWebSocket("**/api/v1/events", (socket) => {
    phone = socket;
    socket.onMessage((message) => {
      const value = JSON.parse(typeof message === "string" ? message : message.toString()) as {
        type?: string;
      };
      if (value.type !== "session.auth") return;
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "notification-authenticated",
        type: "session.authenticated",
        timestamp: Date.now(),
      }));
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "notification-snapshot",
        type: "text.snapshot",
        timestamp: Date.now(),
        items: [],
      }));
    });
  });

  await page.goto(`${origin}/`);
  await expectConnected(page);
  await page.getByRole("button", { name: "Включить уведомления" }).click();
  await expect(page.getByRole("button", { name: "Выключить уведомления" })).toBeVisible();

  await page.evaluate(() => {
    (window as unknown as { __attention: { hidden: boolean } }).__attention.hidden = true;
    document.dispatchEvent(new Event("visibilitychange"));
  });
  phone!.send(JSON.stringify({
    protocolVersion: 1,
    messageId: "phone-text",
    type: "text.received",
    timestamp: Date.now(),
    content: "Привет с телефона",
    contentKind: "TEXT",
    direction: "ANDROID_TO_BROWSER",
    senderLabel: "Телефон",
    status: "DELIVERED",
  }));

  await expect.poll(() => page.evaluate(() =>
    (window as unknown as { __notifications: unknown[] }).__notifications,
  )).toEqual([{ title: "Текст с телефона", body: "Привет с телефона" }]);
  await expect(page).toHaveTitle(/^\(1\) DeviceBridge/);
  // The certificate probe's registration attempt fails on its own; nothing may stay registered.
  await expect.poll(
    () => page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length),
    { timeout: 15_000 },
  ).toBe(0);

  await page.evaluate(() => {
    (window as unknown as { __attention: { hidden: boolean } }).__attention.hidden = false;
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(page).toHaveTitle(/^DeviceBridge/);
});

async function installManifest(page: Page): Promise<void> {
  await page.route("**/web-manifest.json", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify(MANIFEST),
  }));
}

async function installConnectedSession(
  page: Page,
  token: string,
  initialSocketEvent?: unknown,
): Promise<void> {
  await page.addInitScript((sessionToken) => {
    sessionStorage.setItem("devicebridge.session.v1", sessionToken);
  }, token);
  await installManifest(page);
  await page.route("**/api/v1/status", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify(STATUS),
  }));
  await installAuthenticatedSocket(page, initialSocketEvent);
}

async function installAuthenticatedSocket(
  page: Page,
  initialSocketEvent?: unknown,
): Promise<void> {
  await page.routeWebSocket("**/api/v1/events", (socket) => {
    socket.onMessage((message) => {
      const value = JSON.parse(
        typeof message === "string" ? message : message.toString(),
      ) as { type?: string };
      if (value.type !== "session.auth") return;
      socket.send(JSON.stringify({
        protocolVersion: 1,
        messageId: "compatibility-authenticated",
        type: "session.authenticated",
        timestamp: Date.now(),
      }));
      if (initialSocketEvent !== undefined) {
        socket.send(JSON.stringify(initialSocketEvent));
      }
    });
  });
}

async function expectConnected(page: Page): Promise<void> {
  await expect(page.getByRole("heading", { name: "Безопасное подключение активно" }))
    .toBeVisible();
  await expect(page.getByRole("button", { name: "Отключить этот браузер" })).toBeVisible();
  await expect(page.locator('[data-role="status"]')).toHaveAttribute(
    "data-state",
    "connected",
  );
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
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
}
