import { describe, expect, it, vi } from "vitest";
import { SessionEventSocketClient, type SocketLike } from "../src/sessionEventSocketClient";

describe("SessionEventSocketClient", () => {
  it("authenticates on LAN HTTP when crypto.randomUUID is unavailable", () => {
    vi.stubGlobal("crypto", insecureHttpCrypto());
    try {
      const sockets: FakeSocket[] = [];
      const client = new SessionEventSocketClient(
        (url) => {
          const socket = new FakeSocket(url);
          sockets.push(socket);
          return socket;
        },
        "http://192.168.1.24:8787",
        immediateScheduler,
      );

      client.connect("secret-token", { onSessionLost: vi.fn() });
      expect(() => sockets[0]!.open()).not.toThrow();

      const auth = JSON.parse(sockets[0]!.sent[0]!) as { messageId: string; type: string };
      expect(auth.type).toBe("session.auth");
      expect(auth.messageId).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("keeps token out of URL and sends it only in the first auth frame", () => {
    const sockets: FakeSocket[] = [];
    const client = new SessionEventSocketClient(
      (url) => {
        const socket = new FakeSocket(url);
        sockets.push(socket);
        return socket;
      },
      "http://192.168.1.24:8787",
      immediateScheduler,
      () => "message-1",
      () => 1_000,
    );

    client.connect("secret-token", { onSessionLost: vi.fn() });
    const socket = sockets[0]!;
    expect(socket.url).toBe("ws://192.168.1.24:8787/api/v1/events");
    expect(socket.url).not.toContain("secret-token");
    socket.open();

    expect(socket.sent).toHaveLength(1);
    expect(JSON.parse(socket.sent[0]!)).toEqual({
      protocolVersion: 1,
      messageId: "message-1",
      type: "session.auth",
      timestamp: 1_000,
      token: "secret-token",
    });
  });

  it("uses bounded reconnect and reports a policy auth close immediately", () => {
    const sockets: FakeSocket[] = [];
    const lost = vi.fn();
    const client = new SessionEventSocketClient(
      (url) => {
        const socket = new FakeSocket(url);
        sockets.push(socket);
        return socket;
      },
      "https://devicebridge.local",
      immediateScheduler,
      () => "message-1",
      () => 1_000,
    );
    client.connect("token", { onSessionLost: lost });

    sockets[0]!.closeFromServer(1006);
    sockets[1]!.closeFromServer(1006);
    sockets[2]!.closeFromServer(1006);
    sockets[3]!.closeFromServer(1006);
    expect(sockets).toHaveLength(4);
    expect(lost).toHaveBeenCalledOnce();

    lost.mockClear();
    client.connect("token", { onSessionLost: lost });
    sockets.at(-1)!.closeFromServer(1008);
    expect(lost).toHaveBeenCalledOnce();
    expect(sockets.at(-1)?.url.startsWith("wss://")).toBe(true);
  });

  it("processes text only after authentication, deduplicates it, and acknowledges live events", () => {
    const sockets: FakeSocket[] = [];
    const received = vi.fn();
    const snapshots = vi.fn();
    let nextId = 0;
    const client = new SessionEventSocketClient(
      (url) => {
        const socket = new FakeSocket(url);
        sockets.push(socket);
        return socket;
      },
      "http://devicebridge.local",
      immediateScheduler,
      () => `client-message-${++nextId}`,
      () => 5_000,
    );
    client.connect("token", {
      onSessionLost: vi.fn(),
      onTextReceived: received,
      onTextSnapshot: snapshots,
    });
    const socket = sockets[0]!;
    socket.open();

    socket.message(receivedEvent("text-1", "до авторизации"));
    expect(received).not.toHaveBeenCalled();
    expect(socket.sent).toHaveLength(1);

    socket.message({
      protocolVersion: 1,
      messageId: "authenticated-1",
      type: "session.authenticated",
      timestamp: 5_000,
    });
    socket.message(snapshotEvent([
      snapshotItem("text-1", "из snapshot"),
      snapshotItem("text-1", "дубликат"),
    ]));
    expect(snapshots).toHaveBeenCalledWith(
      expect.objectContaining({
        type: "text.snapshot",
        items: [expect.objectContaining({ messageId: "text-1", content: "из snapshot" })],
      }),
    );

    socket.message(receivedEvent("text-1", "повтор"));
    socket.message(receivedEvent("text-2", "новое сообщение"));

    expect(received).toHaveBeenCalledTimes(1);
    expect(received).toHaveBeenCalledWith(
      expect.objectContaining({ messageId: "text-2", content: "новое сообщение" }),
    );
    expect(JSON.parse(socket.sent[1]!)).toEqual({
      protocolVersion: 1,
      messageId: "client-message-2",
      type: "text.ack",
      timestamp: 5_000,
      acknowledgedMessageId: "text-1",
    });
    expect(JSON.parse(socket.sent[2]!)).toEqual({
      protocolVersion: 1,
      messageId: "client-message-3",
      type: "text.ack",
      timestamp: 5_000,
      acknowledgedMessageId: "text-2",
    });
  });

  it("reports snapshots, protocol errors, reconnect attempts, and final session loss", () => {
    const sockets: FakeSocket[] = [];
    const reconnecting = vi.fn();
    const textError = vi.fn();
    const lost = vi.fn();
    const client = new SessionEventSocketClient(
      (url) => {
        const socket = new FakeSocket(url);
        sockets.push(socket);
        return socket;
      },
      "http://devicebridge.local",
      immediateScheduler,
      () => "client-message",
      () => 5_000,
    );
    client.connect("token", {
      onSessionLost: lost,
      onReconnecting: reconnecting,
      onTextError: textError,
    });
    sockets[0]!.open();
    sockets[0]!.message({
      protocolVersion: 1,
      messageId: "authenticated-1",
      type: "session.authenticated",
      timestamp: 5_000,
    });
    sockets[0]!.message({
      protocolVersion: 1,
      messageId: "server-error",
      type: "text.error",
      timestamp: 5_001,
      relatedMessageId: "text-1",
      code: "SESSION_UNAVAILABLE",
    });
    expect(textError).toHaveBeenCalledWith(
      expect.objectContaining({ code: "SESSION_UNAVAILABLE" }),
    );

    sockets[0]!.closeFromServer(1006);
    sockets[1]!.closeFromServer(1006);
    sockets[2]!.closeFromServer(1006);
    sockets[3]!.closeFromServer(1006);

    expect(reconnecting.mock.calls).toEqual([
      [1, 1_000],
      [2, 2_000],
      [3, 4_000],
    ]);
    expect(lost).toHaveBeenCalledOnce();
  });
});

class FakeSocket implements SocketLike {
  readonly sent: string[] = [];
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  constructor(readonly url: string) {}
  send(data: string): void { this.sent.push(data); }
  close(): void { this.onclose?.({ code: 1000 } as CloseEvent); }
  open(): void { this.onopen?.({} as Event); }
  message(value: unknown): void {
    this.onmessage?.({ data: JSON.stringify(value) } as MessageEvent<string>);
  }
  closeFromServer(code: number): void { this.onclose?.({ code } as CloseEvent); }
}

const immediateScheduler = {
  setTimeout(callback: () => void): unknown { callback(); return 1; },
  clearTimeout(): void {},
};

function insecureHttpCrypto(): Pick<Crypto, "getRandomValues"> {
  return {
    getRandomValues<T extends ArrayBufferView | null>(array: T): T {
      if (array instanceof Uint8Array) array.fill(0x2a);
      return array;
    },
  };
}

function receivedEvent(messageId: string, content: string): object {
  return {
    protocolVersion: 1,
    messageId,
    type: "text.received",
    timestamp: 5_000,
    content,
    contentKind: "TEXT",
    direction: "ANDROID_TO_BROWSER",
    senderLabel: "Телефон",
    status: "SENDING",
  };
}

function snapshotItem(messageId: string, content: string): object {
  return {
    messageId,
    timestamp: 5_000,
    content,
    contentKind: "TEXT",
    direction: "ANDROID_TO_BROWSER",
    senderLabel: "Телефон",
    status: "DELIVERED",
  };
}

function snapshotEvent(items: object[]): object {
  return {
    protocolVersion: 1,
    messageId: "snapshot-1",
    type: "text.snapshot",
    timestamp: 5_000,
    items,
  };
}
