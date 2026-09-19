import { describe, expect, it, vi } from "vitest";
import {
  TextTransferController,
  type TextDraftStore,
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

  it("keeps the draft and blocks sending while the session reconnects", () => {
    const api = fakeSender();
    const fixture = createFixture(api);
    fixture.controller.activate("token");
    fixture.controller.updateDraft("Сохранённый черновик");

    fixture.controller.setConnectionAvailable(false);
    fixture.controller.sendDraft();

    expect(api.send).not.toHaveBeenCalled();
    expect(fixture.states.at(-1)).toMatchObject({
      kind: "active",
      connectionAvailable: false,
      draft: "Сохранённый черновик",
    });
  });
  it("keeps a failed item and retries the same idempotency command", async () => {
    const api = fakeSender();
    api.send = vi.fn()
      .mockRejectedValueOnce(
        new TextApiError(503, "SESSION_UNAVAILABLE", "Получатель недоступен", "browser-1"),
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
        error: { code: "SESSION_UNAVAILABLE" },
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

  it("does not retry an unchanged payload for a terminal validation failure", async () => {
    const api = fakeSender();
    api.send = vi.fn().mockRejectedValue(
      new TextApiError(413, "CONTENT_TOO_LARGE", "Текст превышает лимит", "browser-1"),
    );
    const fixture = createFixture(api);
    fixture.controller.activate("token");
    fixture.controller.updateDraft("Слишком большой текст");
    fixture.controller.sendDraft();
    await vi.waitFor(() => expect(fixture.states.at(-1)).toMatchObject({
      error: { code: "CONTENT_TOO_LARGE" },
      items: [{ messageId: "browser-1", status: "FAILED", retryable: false }],
    }));

    fixture.controller.retry("browser-1");

    expect(api.send).toHaveBeenCalledOnce();
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

  it("marks an in-flight operation uncertain on disconnect and retries the same messageId", async () => {
    const api = fakeSender();
    api.send = vi.fn()
      .mockImplementationOnce((_token: string, _command: unknown, signal?: AbortSignal) =>
        new Promise<TextAccepted>((_resolve, reject) => {
          signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        })
      )
      .mockResolvedValueOnce(accepted("browser-1"));
    const fixture = createFixture(api);
    fixture.controller.activate("token");
    fixture.controller.updateDraft("Не потерять");
    fixture.controller.sendDraft();
    expect(fixture.states.at(-1)).toMatchObject({
      kind: "active",
      draft: "Не потерять",
      items: [{ messageId: "browser-1", status: "SENDING" }],
    });

    fixture.controller.setConnectionAvailable(false);
    expect(fixture.states.at(-1)).toMatchObject({
      kind: "active",
      connectionAvailable: false,
      draft: "Не потерять",
      items: [{ messageId: "browser-1", status: "UNCERTAIN" }],
    });

    fixture.controller.setConnectionAvailable(true);
    fixture.controller.retry("browser-1");
    await vi.waitFor(() => expect(fixture.states.at(-1)).toMatchObject({
      items: [{ messageId: "browser-1", status: "DELIVERED" }],
    }));
    expect(api.send.mock.calls.map((call) => call[1].messageId))
      .toEqual(["browser-1", "browser-1"]);
  });

  it("creates a new operation when the failed draft is edited and sent", async () => {
    const api = fakeSender();
    api.send = vi.fn()
      .mockRejectedValueOnce(new Error("network"))
      .mockImplementationOnce(async (_token: string, command: { messageId: string }) =>
        accepted(command.messageId)
      );
    const fixture = createFixture(api);
    fixture.controller.activate("token");
    fixture.controller.updateDraft("Первый вариант");
    fixture.controller.sendDraft();
    await vi.waitFor(() => expect(fixture.states.at(-1)).toMatchObject({
      items: [{ messageId: "browser-1", status: "FAILED" }],
    }));

    fixture.controller.updateDraft("Изменённый вариант");
    fixture.controller.sendDraft();
    await vi.waitFor(() => expect(api.send).toHaveBeenCalledTimes(2));

    expect(api.send.mock.calls.map((call) => call[1].messageId))
      .toEqual(["browser-1", "browser-2"]);
  });

  it("restores a draft after reload only for the same browser session scope", async () => {
    const draftStore = new FakeDraftStore();
    const firstStates: TextTransferUiState[] = [];
    const first = new TextTransferController(
      fakeSender(),
      (state) => firstStates.push(state),
      () => "browser-1",
      () => 1_000,
      vi.fn(),
      draftStore,
    );
    first.activate("token-1", "session-1");
    first.updateDraft("Черновик после reload");
    first.dispose();

    const restoredStates: TextTransferUiState[] = [];
    const restored = new TextTransferController(
      fakeSender(),
      (state) => restoredStates.push(state),
      () => "browser-2",
      () => 2_000,
      vi.fn(),
      draftStore,
    );
    restored.activate("token-2", "session-1");
    expect(restoredStates.at(-1)).toMatchObject({
      kind: "active",
      draft: "Черновик после reload",
    });

    restored.updateDraft("");
    expect(draftStore.read("session-1")).toBeUndefined();
    restored.updateDraft("Доставить и очистить");
    restored.sendDraft();
    await vi.waitFor(() => {
      expect(draftStore.read("session-1")).toBeUndefined();
    });

    restored.deactivate();
    const another = new TextTransferController(
      fakeSender(),
      () => undefined,
      () => "browser-3",
      () => 3_000,
      vi.fn(),
      draftStore,
    );
    another.activate("token-3", "session-2");
    expect(draftStore.read("session-2")).toBeUndefined();
  });
  it("preserves draft on unexpected 401 and clears it on explicit deactivation", async () => {
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
      expect(fixture.states.at(-1)).toMatchObject({
        kind: "active",
        connectionAvailable: false,
        draft: "секретный черновик",
      }),
    );
    expect(unauthorized).toHaveBeenCalledOnce();

    fixture.controller.deactivate();
    expect(fixture.states.at(-1)).toEqual({ kind: "inactive" });
    expect(JSON.stringify(fixture.states.at(-1))).not.toContain("черновик");
  });
});

class FakeDraftStore implements TextDraftStore {
  private value?: { scopeId: string; draft: string };
  read(scopeId: string): string | undefined {
    return this.value?.scopeId === scopeId ? this.value.draft : undefined;
  }
  save(scopeId: string, draft: string): void { this.value = { scopeId, draft }; }
  clear(scopeId: string): void {
    if (this.value?.scopeId === scopeId) this.value = undefined;
  }
}
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
