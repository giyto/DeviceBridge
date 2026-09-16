import { SESSION_PROTOCOL_VERSION } from "./sessionApiClient";
import { createProtocolMessageId } from "./protocolMessageId";
import type {
  TextApiErrorCode,
  TextContentKind,
  TextTransferStatus,
} from "./textApiClient";

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
  readonly onReconnecting?: (attempt: number, delayMs: number) => void;
  readonly onTextReceived?: (event: TextReceivedEvent) => void;
  readonly onTextSnapshot?: (event: TextSnapshotEvent) => void;
  readonly onTextError?: (event: TextErrorEvent) => void;
}

export type TextDirection = "ANDROID_TO_BROWSER" | "BROWSER_TO_ANDROID";

export interface TextFeedItem {
  readonly messageId: string;
  readonly timestamp: number;
  readonly content: string;
  readonly contentKind: TextContentKind;
  readonly direction: TextDirection;
  readonly senderLabel: string;
  readonly status: TextTransferStatus;
}

export interface TextReceivedEvent extends TextFeedItem {
  readonly protocolVersion: number;
  readonly type: "text.received";
}

export interface TextSnapshotEvent {
  readonly protocolVersion: number;
  readonly messageId: string;
  readonly type: "text.snapshot";
  readonly timestamp: number;
  readonly items: readonly TextFeedItem[];
}

export interface TextErrorEvent {
  readonly protocolVersion: number;
  readonly messageId: string;
  readonly type: "text.error";
  readonly timestamp: number;
  readonly relatedMessageId?: string;
  readonly code: TextApiErrorCode;
}

type SocketFactory = (url: string) => SocketLike;

const RECONNECT_DELAYS_MS = [1_000, 2_000, 4_000] as const;
const MAX_DEDUPLICATED_TEXT_IDS = 100;

const browserScheduler: SocketScheduler = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
};

export class SessionEventSocketClient {
  private generation = 0;
  private socket?: SocketLike;
  private retryHandle?: unknown;
  private readonly seenTextMessageIds = new Set<string>();

  constructor(
    private readonly socketFactory: SocketFactory = (url) => new WebSocket(url),
    private readonly origin: string = globalThis.location.origin,
    private readonly scheduler: SocketScheduler = browserScheduler,
    private readonly createMessageId: () => string = createProtocolMessageId,
    private readonly now: () => number = () => Date.now(),
  ) {}

  connect(token: string, callbacks: SessionEventCallbacks): void {
    this.disconnect();
    this.seenTextMessageIds.clear();
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
    let authenticated = false;
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
        authenticated = true;
        callbacks.onAuthenticated?.();
        return;
      }
      if (!authenticated) return;
      if (value?.type === "text.received") {
        const received = parseTextReceived(value);
        if (received === undefined) return;
        if (this.rememberTextMessage(received.messageId)) {
          callbacks.onTextReceived?.(received);
        }
        socket.send(JSON.stringify({
          protocolVersion: SESSION_PROTOCOL_VERSION,
          messageId: this.createMessageId(),
          type: "text.ack",
          timestamp: this.now(),
          acknowledgedMessageId: received.messageId,
        }));
        return;
      }
      if (value?.type === "text.snapshot") {
        const snapshot = parseTextSnapshot(value);
        if (snapshot === undefined) return;
        const items = snapshot.items.filter((item) =>
          this.rememberTextMessage(item.messageId)
        );
        callbacks.onTextSnapshot?.({ ...snapshot, items });
        return;
      }
      if (value?.type === "text.error") {
        const textError = parseTextError(value);
        if (textError !== undefined) callbacks.onTextError?.(textError);
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
      callbacks.onReconnecting?.(retryIndex + 1, delay);
      this.retryHandle = this.scheduler.setTimeout(() => {
        this.retryHandle = undefined;
        this.open(generation, token, callbacks, retryIndex + 1);
      }, delay);
    };
  }

  private rememberTextMessage(messageId: string): boolean {
    if (this.seenTextMessageIds.has(messageId)) return false;
    this.seenTextMessageIds.add(messageId);
    if (this.seenTextMessageIds.size > MAX_DEDUPLICATED_TEXT_IDS) {
      const oldest = this.seenTextMessageIds.values().next().value as string | undefined;
      if (oldest !== undefined) this.seenTextMessageIds.delete(oldest);
    }
    return true;
  }
}

function eventsUrl(origin: string): string {
  const url = new URL("/api/v1/events", origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.search = "";
  url.hash = "";
  return url.toString();
}

function parseEvent(value: string): Record<string, unknown> | undefined {
  try {
    const parsed = JSON.parse(value) as unknown;
    return isRecord(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function parseTextReceived(value: Record<string, unknown>): TextReceivedEvent | undefined {
  const item = parseTextFeedItem(value);
  if (
    item === undefined ||
    value.protocolVersion !== SESSION_PROTOCOL_VERSION ||
    value.type !== "text.received"
  ) return undefined;
  return {
    protocolVersion: SESSION_PROTOCOL_VERSION,
    type: "text.received",
    ...item,
  };
}

function parseTextSnapshot(value: Record<string, unknown>): TextSnapshotEvent | undefined {
  if (
    value.protocolVersion !== SESSION_PROTOCOL_VERSION ||
    value.type !== "text.snapshot" ||
    !isProtocolId(value.messageId) ||
    !isPositiveInteger(value.timestamp) ||
    !Array.isArray(value.items) ||
    value.items.length > 100
  ) return undefined;
  const items: TextFeedItem[] = [];
  for (const candidate of value.items) {
    if (!isRecord(candidate)) return undefined;
    const item = parseTextFeedItem(candidate);
    if (item === undefined) return undefined;
    items.push(item);
  }
  return {
    protocolVersion: SESSION_PROTOCOL_VERSION,
    messageId: value.messageId,
    type: "text.snapshot",
    timestamp: value.timestamp,
    items,
  };
}

function parseTextError(value: Record<string, unknown>): TextErrorEvent | undefined {
  if (
    value.protocolVersion !== SESSION_PROTOCOL_VERSION ||
    value.type !== "text.error" ||
    !isProtocolId(value.messageId) ||
    !isPositiveInteger(value.timestamp) ||
    !isTextErrorCode(value.code) ||
    (value.relatedMessageId !== undefined && !isProtocolId(value.relatedMessageId))
  ) return undefined;
  return {
    protocolVersion: SESSION_PROTOCOL_VERSION,
    messageId: value.messageId,
    type: "text.error",
    timestamp: value.timestamp,
    ...(typeof value.relatedMessageId === "string"
      ? { relatedMessageId: value.relatedMessageId }
      : {}),
    code: value.code,
  };
}

function parseTextFeedItem(value: Record<string, unknown>): TextFeedItem | undefined {
  if (
    !isProtocolId(value.messageId) ||
    !isPositiveInteger(value.timestamp) ||
    typeof value.content !== "string" ||
    value.content.length === 0 ||
    !isContentKind(value.contentKind) ||
    !isDirection(value.direction) ||
    typeof value.senderLabel !== "string" ||
    value.senderLabel.trim().length === 0 ||
    value.senderLabel.length > 64 ||
    [...value.senderLabel].some((character) => isControlCharacter(character)) ||
    !isTransferStatus(value.status)
  ) return undefined;
  return {
    messageId: value.messageId,
    timestamp: value.timestamp,
    content: value.content,
    contentKind: value.contentKind,
    direction: value.direction,
    senderLabel: value.senderLabel,
    status: value.status,
  };
}

function isProtocolId(value: unknown): value is string {
  return typeof value === "string" &&
    value.length >= 1 &&
    value.length <= 64 &&
    /^[A-Za-z0-9_-]+$/.test(value);
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isContentKind(value: unknown): value is TextContentKind {
  return value === "TEXT" || value === "LINK";
}

function isDirection(value: unknown): value is TextDirection {
  return value === "ANDROID_TO_BROWSER" || value === "BROWSER_TO_ANDROID";
}

function isTransferStatus(value: unknown): value is TextTransferStatus {
  return value === "PENDING" ||
    value === "SENDING" ||
    value === "DELIVERED" ||
    value === "FAILED";
}

function isTextErrorCode(value: unknown): value is TextApiErrorCode {
  return value === "INVALID_PAYLOAD" ||
    value === "UNSUPPORTED_VERSION" ||
    value === "CONTENT_TOO_LARGE" ||
    value === "MESSAGE_CONFLICT" ||
    value === "SESSION_UNAVAILABLE" ||
    value === "UNAUTHORIZED" ||
    value === "SESSION_CLOSED";
}

function isControlCharacter(value: string): boolean {
  const codePoint = value.codePointAt(0);
  return codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
