import { describe, expect, it, vi } from "vitest";
import { SessionEventSocketClient, type SocketLike } from "../src/sessionEventSocketClient";

describe("SessionEventSocketClient", () => {
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
  closeFromServer(code: number): void { this.onclose?.({ code } as CloseEvent); }
}

const immediateScheduler = {
  setTimeout(callback: () => void): unknown { callback(); return 1; },
  clearTimeout(): void {},
};
