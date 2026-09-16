import { describe, expect, it, vi } from "vitest";
import {
  TextTransferController,
  type TextSender,
  type TextTransferUiState,
} from "../src/textTransferController";
import { TextApiError, type TextAccepted } from "../src/textApiClient";

describe("TextTransferController", () => {
  it("sends from LAN HTTP when crypto.randomUUID is unavailable", async () => {
    vi.stubGlobal("crypto", insecureHttpCrypto());
    try {
      const api = fakeSender();
      const states: TextTransferUiState[] = [];
      const controller = new TextTransferController(
        api,
        (state) => states.push(state),
      );
      controller.activate("secret-token");
      controller.updateDraft("hello");

      expect(() => controller.sendDraft()).not.toThrow();
      await vi.waitFor(() => expect(api.send).toHaveBeenCalledOnce());

      const command = api.send.mock.calls[0]?.[1] as { messageId: string };
      expect(command.messageId).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("sends only for an active session and publishes the delivered item", async () => {
    const api = fakeSender();
    const fixture = createFixture(api);

    fixture.controller.updateDraft("не отправлять");
    fixture.controller.sendDraft();
    expect(api.send).not.toHaveBeenCalled();

    fixture.controller.activate("secret-token");
    fixture.controller.updateDraft("Привет");
    fixture.controller.sendDraft();
    await vi.waitFor(() =>
      expect(fixture.states.at(-1)).toMatchObject({
        kind: "active",
        draft: "",
        sending: false,
        items: [{
          messageId: "browser-1",
          content: "Привет",
          direction: "BROWSER_TO_ANDROID",
          status: "DELIVERED",
        }],
      }),
    );

    expect(api.send).toHaveBeenCalledWith(
      "secret-token",
      {
        messageId: "browser-1",
        timestamp: 1_000,
        content: "Привет",
      },
      expect.any(AbortSignal),
    );
  });

  it("keeps a failed item and retries the same idempotency command", async () => {
    const api = fakeSender();
    api.send = vi.fn()
      .mockRejectedValueOnce(
        new TextApiError(409, "MESSAGE_CONFLICT", "Конфликт messageId", "browser-1"),
      )
      .mockResolvedValueOnce(accepted("browser-1"));
    const fixture = createFixture(api);
    fixture.controller.activate("token");
    fixture.controller.updateDraft("Повторить");

    fixture.controller.sendDraft();
    await vi.waitFor(() =>
      expect(fixture.states.at(-1)).toMatchObject({
        kind: "active",
        sending: false,
        error: { code: "MESSAGE_CONFLICT" },
        items: [{ messageId: "browser-1", status: "FAILED" }],
      }),
    );
    fixture.controller.retry("browser-1");
    await vi.waitFor(() =>
      expect(fixture.states.at(-1)).toMatchObject({
        kind: "active",
        sending: false,
        error: undefined,
        items: [{ messageId: "browser-1", status: "DELIVERED" }],
      }),
    );

    expect(api.send).toHaveBeenCalledTimes(2);
    expect(api.send.mock.calls[1]?.[1]).toEqual(api.send.mock.calls[0]?.[1]);
  });

  it("merges snapshot and live events without duplicates", () => {
    const fixture = createFixture();
    fixture.controller.activate("token");
    fixture.controller.applySnapshot({
      protocolVersion: 1,
      messageId: "snapshot-1",
      type: "text.snapshot",
      timestamp: 2_000,
      items: [
        incomingItem("incoming-1", "из snapshot"),
        incomingItem("incoming-1", "дубликат"),
      ],
    });
    fixture.controller.receive({
      protocolVersion: 1,
      type: "text.received",
      ...incomingItem("incoming-1", "повтор"),
    });
    fixture.controller.receive({
      protocolVersion: 1,
      type: "text.received",
      ...incomingItem("incoming-2", "новое"),
    });

    expect(fixture.states.at(-1)).toMatchObject({
      kind: "active",
      items: [
        { messageId: "incoming-1", content: "из snapshot" },
        { messageId: "incoming-2", content: "новое" },
      ],
    });
  });

  it("clears draft on session loss and deactivates on 401", async () => {
    const unauthorized = vi.fn();
    const api = fakeSender();
    api.send = vi.fn().mockRejectedValue(
      new TextApiError(401, "UNAUTHORIZED", "Сессия завершена"),
    );
    const fixture = createFixture(api, unauthorized);
    fixture.controller.activate("token");
    fixture.controller.updateDraft("секретный черновик");
    fixture.controller.sendDraft();

    await vi.waitFor(() =>
      expect(fixture.states.at(-1)).toEqual({ kind: "inactive" }),
    );
    expect(unauthorized).toHaveBeenCalledOnce();

    fixture.controller.activate("new-token");
    fixture.controller.updateDraft("ещё черновик");
    fixture.controller.deactivate();
    expect(fixture.states.at(-1)).toEqual({ kind: "inactive" });
    expect(JSON.stringify(fixture.states.at(-1))).not.toContain("черновик");
  });
});

function createFixture(
  api = fakeSender(),
  onUnauthorized = vi.fn(),
) {
  const states: TextTransferUiState[] = [];
  let messageSequence = 0;
  const controller = new TextTransferController(
    api,
    (state) => states.push(state),
    () => `browser-${++messageSequence}`,
    () => 1_000,
    onUnauthorized,
  );
  return { controller, states };
}

function fakeSender(): TextSender & { send: ReturnType<typeof vi.fn> } {
  return {
    send: vi.fn().mockImplementation(
      async (_token: string, command: { messageId: string }): Promise<TextAccepted> =>
        accepted(command.messageId),
    ),
  };
}

function accepted(messageId: string): TextAccepted {
  return {
    protocolVersion: 1,
    messageId,
    type: "text.accepted",
    timestamp: 2_000,
    contentKind: "TEXT",
    status: "DELIVERED",
  };
}

function incomingItem(messageId: string, content: string) {
  return {
    messageId,
    timestamp: 2_000,
    content,
    contentKind: "TEXT" as const,
    direction: "ANDROID_TO_BROWSER" as const,
    senderLabel: "Телефон",
    status: "DELIVERED" as const,
  };
}

function insecureHttpCrypto(): Pick<Crypto, "getRandomValues"> {
  return {
    getRandomValues<T extends ArrayBufferView | null>(array: T): T {
      if (array instanceof Uint8Array) array.fill(0x2a);
      return array;
    },
  };
}
