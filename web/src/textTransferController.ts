import {
  TextApiError,
  type TextAccepted,
  type TextApiClient,
  type TextApiErrorCode,
  type TextContentKind,
  type TextSendCommand,
  type TextTransferStatus,
} from "./textApiClient";
import type {
  TextDirection,
  TextErrorEvent,
  TextFeedItem,
  TextReceivedEvent,
  TextSnapshotEvent,
} from "./sessionEventSocketClient";
import { createProtocolMessageId } from "./protocolMessageId";

const MAX_FEED_ITEMS = 100;

export interface TextSender {
  send(
    token: string,
    command: TextSendCommand,
    signal?: AbortSignal,
  ): Promise<TextAccepted>;
}

export interface TextTransferUiItem {
  readonly messageId: string;
  readonly timestamp: number;
  readonly content: string;
  readonly contentKind: TextContentKind;
  readonly direction: TextDirection;
  readonly senderLabel: string;
  readonly status: TextTransferStatus;
}

export interface TextTransferUiError {
  readonly code: TextApiErrorCode;
  readonly message: string;
  readonly relatedMessageId?: string;
}

export type TextTransferUiState =
  | Readonly<{ kind: "inactive" }>
  | Readonly<{
      kind: "active";
      draft: string;
      sending: boolean;
      items: readonly TextTransferUiItem[];
      error: TextTransferUiError | undefined;
    }>;

export class TextTransferController {
  private generation = 0;
  private token?: string;
  private abortController?: AbortController;
  private state: TextTransferUiState = { kind: "inactive" };
  private readonly retryCommands = new Map<string, TextSendCommand>();

  constructor(
    private readonly api: TextSender,
    private readonly onStateChange: (state: TextTransferUiState) => void,
    private readonly createMessageId: () => string = createProtocolMessageId,
    private readonly now: () => number = () => Date.now(),
    private readonly onUnauthorized: () => void = () => undefined,
  ) {}

  activate(token: string): void {
    if (this.state.kind === "active" && this.token === token) return;
    this.beginNewGeneration();
    this.token = token;
    this.retryCommands.clear();
    this.emit({
      kind: "active",
      draft: "",
      sending: false,
      items: [],
      error: undefined,
    });
  }

  deactivate(): void {
    this.beginNewGeneration();
    this.token = undefined;
    this.retryCommands.clear();
    this.emit({ kind: "inactive" });
  }

  dispose(): void {
    this.deactivate();
  }

  updateDraft(draft: string): void {
    if (this.state.kind !== "active") return;
    this.emit({ ...this.state, draft, error: undefined });
  }

  sendDraft(): void {
    if (this.state.kind !== "active" || this.state.sending) return;
    if (this.state.draft.trim().length === 0) return;
    const command: TextSendCommand = {
      messageId: this.createMessageId(),
      timestamp: this.now(),
      content: this.state.draft,
    };
    this.retryCommands.set(command.messageId, command);
    this.send(command);
  }

  retry(messageId: string): void {
    if (this.state.kind !== "active" || this.state.sending) return;
    const command = this.retryCommands.get(messageId);
    if (command !== undefined) this.send(command);
  }

  receive(event: TextReceivedEvent): void {
    if (this.state.kind !== "active") return;
    this.mergeItems([event]);
  }

  applySnapshot(event: TextSnapshotEvent): void {
    if (this.state.kind !== "active") return;
    this.mergeItems(event.items);
  }

  receiveError(event: TextErrorEvent): void {
    if (this.state.kind !== "active") return;
    const items = event.relatedMessageId === undefined
      ? this.state.items
      : this.state.items.map((item) =>
          item.messageId === event.relatedMessageId
            ? { ...item, status: "FAILED" as const }
            : item
        );
    this.emit({
      ...this.state,
      items,
      error: {
        code: event.code,
        message: errorMessage(event.code),
        ...(event.relatedMessageId === undefined
          ? {}
          : { relatedMessageId: event.relatedMessageId }),
      },
    });
  }

  private send(command: TextSendCommand): void {
    if (this.state.kind !== "active" || this.token === undefined) return;
    const generation = this.generation;
    const token = this.token;
    const controller = new AbortController();
    this.abortController?.abort();
    this.abortController = controller;
    this.upsertOutgoing(command, "SENDING");
    void this.api.send(token, command, controller.signal)
      .then((accepted) => {
        if (!this.isCurrent(generation, token)) return;
        this.applyAccepted(command, accepted);
      })
      .catch((error: unknown) => {
        if (!this.isCurrent(generation, token) || isAbortError(error)) return;
        if (error instanceof TextApiError && error.code === "UNAUTHORIZED") {
          this.deactivate();
          this.onUnauthorized();
          return;
        }
        this.applyFailure(command, error);
      })
      .finally(() => {
        if (this.abortController === controller) this.abortController = undefined;
      });
  }

  private upsertOutgoing(
    command: TextSendCommand,
    status: TextTransferStatus,
  ): void {
    if (this.state.kind !== "active") return;
    const item: TextTransferUiItem = {
      messageId: command.messageId,
      timestamp: command.timestamp,
      content: command.content,
      contentKind: classifyForPreview(command.content),
      direction: "BROWSER_TO_ANDROID",
      senderLabel: "Этот браузер",
      status,
    };
    this.emit({
      ...this.state,
      sending: true,
      items: upsertBounded(this.state.items, item),
      error: undefined,
    });
  }

  private applyAccepted(command: TextSendCommand, accepted: TextAccepted): void {
    if (this.state.kind !== "active") return;
    const previous = this.state.items.find((item) => item.messageId === command.messageId);
    if (previous === undefined) return;
    this.retryCommands.delete(command.messageId);
    this.emit({
      ...this.state,
      draft: this.state.draft === command.content ? "" : this.state.draft,
      sending: false,
      items: upsertBounded(this.state.items, {
        ...previous,
        timestamp: accepted.timestamp,
        contentKind: accepted.contentKind,
        status: accepted.status,
      }),
      error: undefined,
    });
  }

  private applyFailure(command: TextSendCommand, error: unknown): void {
    if (this.state.kind !== "active") return;
    const code = error instanceof TextApiError ? error.code : "SESSION_UNAVAILABLE";
    const message = error instanceof Error
      ? error.message
      : "Не удалось отправить текст";
    const items = this.state.items.map((item) =>
      item.messageId === command.messageId
        ? { ...item, status: "FAILED" as const }
        : item
    );
    this.emit({
      ...this.state,
      sending: false,
      items,
      error: {
        code,
        message,
        relatedMessageId: command.messageId,
      },
    });
  }

  private mergeItems(items: readonly TextFeedItem[]): void {
    if (this.state.kind !== "active") return;
    let merged = this.state.items;
    for (const item of items) {
      if (merged.some((existing) => existing.messageId === item.messageId)) continue;
      merged = upsertBounded(merged, item);
    }
    this.emit({ ...this.state, items: merged });
  }

  private beginNewGeneration(): void {
    this.generation += 1;
    this.abortController?.abort();
    this.abortController = undefined;
  }

  private isCurrent(generation: number, token: string): boolean {
    return generation === this.generation &&
      this.state.kind === "active" &&
      this.token === token;
  }

  private emit(state: TextTransferUiState): void {
    this.state = state;
    this.onStateChange(state);
  }
}

function upsertBounded(
  items: readonly TextTransferUiItem[],
  item: TextTransferUiItem,
): readonly TextTransferUiItem[] {
  const index = items.findIndex((candidate) => candidate.messageId === item.messageId);
  const next = index === -1
    ? [...items, item]
    : items.map((candidate, candidateIndex) =>
        candidateIndex === index ? item : candidate
      );
  return next.length <= MAX_FEED_ITEMS ? next : next.slice(next.length - MAX_FEED_ITEMS);
}

function classifyForPreview(content: string): TextContentKind {
  try {
    const url = new URL(content);
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname.length > 0
      ? "LINK"
      : "TEXT";
  } catch {
    return "TEXT";
  }
}

function errorMessage(code: TextApiErrorCode): string {
  switch (code) {
    case "CONTENT_TOO_LARGE":
      return "Текст превышает лимит 100 КБ.";
    case "MESSAGE_CONFLICT":
      return "Идентификатор сообщения уже использован.";
    case "UNAUTHORIZED":
    case "SESSION_CLOSED":
      return "Сессия браузера завершена.";
    case "SESSION_UNAVAILABLE":
      return "Получатель сейчас недоступен.";
    case "UNSUPPORTED_VERSION":
      return "Версия text protocol не поддерживается.";
    case "INVALID_PAYLOAD":
      return "Некорректное текстовое сообщение.";
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

export type { TextApiClient };
