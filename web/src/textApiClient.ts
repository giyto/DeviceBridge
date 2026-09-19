export const TEXT_PROTOCOL_VERSION = 1;
export const TEXT_SEND_TYPE = "text.send";
export const TEXT_ACCEPTED_TYPE = "text.accepted";

export type TextContentKind = "TEXT" | "LINK";
export type TextTransferStatus = "PENDING" | "SENDING" | "UNCERTAIN" | "DELIVERED" | "FAILED";
export type TextApiErrorCode =
  | "INVALID_PAYLOAD"
  | "UNSUPPORTED_VERSION"
  | "CONTENT_TOO_LARGE"
  | "MESSAGE_CONFLICT"
  | "SESSION_UNAVAILABLE"
  | "UNAUTHORIZED"
  | "SESSION_CLOSED";

export interface TextSendCommand {
  readonly messageId: string;
  readonly timestamp: number;
  readonly content: string;
}

export interface TextAccepted {
  readonly protocolVersion: number;
  readonly messageId: string;
  readonly type: typeof TEXT_ACCEPTED_TYPE;
  readonly timestamp: number;
  readonly contentKind: TextContentKind;
  readonly status: TextTransferStatus;
}

export class TextApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: TextApiErrorCode,
    message: string,
    readonly relatedMessageId?: string,
  ) {
    super(message);
    this.name = "TextApiError";
  }
}

export class TextApiClient {
  constructor(
    private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis),
  ) {}

  async send(
    token: string,
    command: TextSendCommand,
    signal?: AbortSignal,
  ): Promise<TextAccepted> {
    requireToken(token);
    const response = await this.fetcher("/api/v1/text", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        protocolVersion: TEXT_PROTOCOL_VERSION,
        messageId: command.messageId,
        type: TEXT_SEND_TYPE,
        timestamp: command.timestamp,
        content: command.content,
      }),
      signal,
    });
    if (!response.ok) {
      throw await parseError(response);
    }
    requireJsonResponse(response);
    return parseAccepted(await response.json() as unknown);
  }
}

async function parseError(response: Response): Promise<TextApiError> {
  const fallback = new TextApiError(
    response.status,
    fallbackCode(response.status),
    "DeviceBridge отклонил отправку текста",
  );
  try {
    const value = await response.json() as unknown;
    if (!isRecord(value)) return fallback;

    if (value.type === "text.error" && isTextErrorCode(value.code)) {
      return new TextApiError(
        response.status,
        value.code,
        "DeviceBridge отклонил отправку текста",
        optionalProtocolId(value.relatedMessageId),
      );
    }

    if (isRecord(value.error)) {
      const code = value.error.code;
      const message = value.error.message;
      if (isTextErrorCode(code) && typeof message === "string") {
        return new TextApiError(response.status, code, message);
      }
    }
    return fallback;
  } catch {
    return fallback;
  }
}

function parseAccepted(value: unknown): TextAccepted {
  const record = requireRecord(value);
  const protocolVersion = requireNonNegativeInteger(record.protocolVersion);
  if (protocolVersion !== TEXT_PROTOCOL_VERSION) {
    throw new TextApiError(
      400,
      "UNSUPPORTED_VERSION",
      "Версия text protocol не поддерживается",
    );
  }
  if (record.type !== TEXT_ACCEPTED_TYPE) {
    throw new Error("Invalid DeviceBridge text response");
  }
  return {
    protocolVersion,
    messageId: requireProtocolId(record.messageId),
    type: TEXT_ACCEPTED_TYPE,
    timestamp: requirePositiveInteger(record.timestamp),
    contentKind: requireContentKind(record.contentKind),
    status: requireTransferStatus(record.status),
  };
}

function requireJsonResponse(response: Response): void {
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new Error("DeviceBridge returned a non-JSON text response");
  }
}

function fallbackCode(status: number): TextApiErrorCode {
  if (status === 401) return "UNAUTHORIZED";
  if (status === 409) return "MESSAGE_CONFLICT";
  if (status === 413) return "CONTENT_TOO_LARGE";
  if (status === 503) return "SESSION_UNAVAILABLE";
  return "INVALID_PAYLOAD";
}

function requireToken(token: string): void {
  if (token.length < 1 || token.length > 256 || /\s/.test(token)) {
    throw new Error("Invalid session credential");
  }
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (!isRecord(value)) throw new Error("Invalid DeviceBridge text response");
  return value;
}

function requireProtocolId(value: unknown): string {
  if (
    typeof value !== "string" ||
    value.length < 1 ||
    value.length > 64 ||
    !/^[A-Za-z0-9_-]+$/.test(value)
  ) {
    throw new Error("Invalid DeviceBridge text response");
  }
  return value;
}

function optionalProtocolId(value: unknown): string | undefined {
  try {
    return value === undefined ? undefined : requireProtocolId(value);
  } catch {
    return undefined;
  }
}

function requireNonNegativeInteger(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error("Invalid DeviceBridge text response");
  }
  return value;
}

function requirePositiveInteger(value: unknown): number {
  const result = requireNonNegativeInteger(value);
  if (result === 0) throw new Error("Invalid DeviceBridge text response");
  return result;
}

function requireContentKind(value: unknown): TextContentKind {
  if (value !== "TEXT" && value !== "LINK") {
    throw new Error("Invalid DeviceBridge text response");
  }
  return value;
}

function requireTransferStatus(value: unknown): TextTransferStatus {
  if (
    value !== "PENDING" &&
    value !== "SENDING" &&
    value !== "DELIVERED" &&
    value !== "FAILED"
  ) {
    throw new Error("Invalid DeviceBridge text response");
  }
  return value;
}

function isTextErrorCode(value: unknown): value is TextApiErrorCode {
  return typeof value === "string" && [
    "INVALID_PAYLOAD",
    "UNSUPPORTED_VERSION",
    "CONTENT_TOO_LARGE",
    "MESSAGE_CONFLICT",
    "SESSION_UNAVAILABLE",
    "UNAUTHORIZED",
    "SESSION_CLOSED",
  ].includes(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
