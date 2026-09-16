import { SESSION_PROTOCOL_VERSION } from "./sessionApiClient";

export interface SocketLike {
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent<string>) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string): void;
  close(): void;
}

export interface SocketScheduler {
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}

export interface SessionEventCallbacks {
  readonly onSessionLost: () => void;
  readonly onAuthenticated?: () => void;
}

type SocketFactory = (url: string) => SocketLike;

const RECONNECT_DELAYS_MS = [1_000, 2_000, 4_000] as const;

const browserScheduler: SocketScheduler = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
};

export class SessionEventSocketClient {
  private generation = 0;
  private socket?: SocketLike;
  private retryHandle?: unknown;

  constructor(
    private readonly socketFactory: SocketFactory = (url) => new WebSocket(url),
    private readonly origin: string = globalThis.location.origin,
    private readonly scheduler: SocketScheduler = browserScheduler,
    private readonly createMessageId: () => string = () => crypto.randomUUID(),
    private readonly now: () => number = () => Date.now(),
  ) {}

  connect(token: string, callbacks: SessionEventCallbacks): void {
    this.disconnect();
    const generation = this.generation;
    this.open(generation, token, callbacks, 0);
  }

  disconnect(): void {
    this.generation += 1;
    if (this.retryHandle !== undefined) {
      this.scheduler.clearTimeout(this.retryHandle);
      this.retryHandle = undefined;
    }
    const socket = this.socket;
    this.socket = undefined;
    socket?.close();
  }

  private open(
    generation: number,
    token: string,
    callbacks: SessionEventCallbacks,
    retryIndex: number,
  ): void {
    if (generation !== this.generation) return;
    const socket = this.socketFactory(eventsUrl(this.origin));
    this.socket = socket;
    socket.onopen = () => {
      if (generation !== this.generation) return;
      socket.send(JSON.stringify({
        protocolVersion: SESSION_PROTOCOL_VERSION,
        messageId: this.createMessageId(),
        type: "session.auth",
        timestamp: this.now(),
        token,
      }));
    };
    socket.onmessage = (event) => {
      if (generation !== this.generation) return;
      const value = parseEvent(event.data);
      if (
        value?.protocolVersion === SESSION_PROTOCOL_VERSION &&
        value.type === "session.authenticated"
      ) {
        callbacks.onAuthenticated?.();
      }
    };
    socket.onerror = () => undefined;
    socket.onclose = (event) => {
      if (generation !== this.generation) return;
      if (this.socket === socket) this.socket = undefined;
      if (event.code === 1008) {
        callbacks.onSessionLost();
        return;
      }
      const delay = RECONNECT_DELAYS_MS[retryIndex];
      if (delay === undefined) {
        callbacks.onSessionLost();
        return;
      }
      this.retryHandle = this.scheduler.setTimeout(() => {
        this.retryHandle = undefined;
        this.open(generation, token, callbacks, retryIndex + 1);
      }, delay);
    };
  }
}

function eventsUrl(origin: string): string {
  const url = new URL("/api/v1/events", origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.search = "";
  url.hash = "";
  return url.toString();
}

function parseEvent(value: string): { protocolVersion?: number; type?: string } | undefined {
  try {
    const parsed = JSON.parse(value) as unknown;
    return typeof parsed === "object" && parsed !== null
      ? parsed as { protocolVersion?: number; type?: string }
      : undefined;
  } catch {
    return undefined;
  }
}
