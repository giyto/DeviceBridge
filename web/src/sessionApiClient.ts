export const SESSION_PROTOCOL_VERSION = 1;

export type SessionErrorCode =
  | "INVALID_PAYLOAD"
  | "UNSUPPORTED_VERSION"
  | "INVALID_CODE"
  | "EXPIRED"
  | "DENIED"
  | "RATE_LIMITED"
  | "CAPACITY_REACHED"
  | "UNAUTHORIZED"
  | "SESSION_CLOSED";

export interface SessionChallenge {
  readonly protocolVersion: number;
  readonly challengeId: string;
  readonly expiresAtEpochMillis: number;
  readonly confirmTimeoutSeconds: number;
  readonly attemptsRemaining: number;
}

export interface SessionConfirmation {
  readonly protocolVersion: number;
  readonly sessionId: string;
  readonly token: string;
  readonly serverTimeEpochMillis: number;
}

export interface SessionStatus {
  readonly protocolVersion: number;
  readonly sessionId: string;
  readonly connected: boolean;
  readonly activeSessionCount: number;
}

export class SessionApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: SessionErrorCode,
    message: string,
    readonly retryAfterSeconds?: number,
    readonly attemptsRemaining?: number,
  ) {
    super(message);
    this.name = "SessionApiError";
  }
}

export class SessionApiClient {
  constructor(
    private readonly fetcher: typeof fetch = globalThis.fetch.bind(globalThis),
  ) {}

  async createChallenge(
    clientLabel: string,
    signal?: AbortSignal,
  ): Promise<SessionChallenge> {
    const value = await this.requestJson(
      "/api/v1/session/challenge",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          protocolVersion: SESSION_PROTOCOL_VERSION,
          clientLabel,
        }),
        signal,
      },
    );
    return parseChallenge(value);
  }

  async confirm(
    challengeId: string,
    code: string,
    clientLabel: string,
    signal?: AbortSignal,
  ): Promise<SessionConfirmation> {
    const value = await this.requestJson(
      "/api/v1/session/confirm",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          protocolVersion: SESSION_PROTOCOL_VERSION,
          challengeId,
          code,
          clientLabel,
        }),
        signal,
      },
    );
    return parseConfirmation(value);
  }

  async status(token: string, signal?: AbortSignal): Promise<SessionStatus> {
    requireToken(token);
    const value = await this.requestJson(
      "/api/v1/status",
      {
        method: "GET",
        headers: { Authorization: `Bearer ${token}` },
        signal,
      },
    );
    return parseStatus(value);
  }

  async close(token: string, signal?: AbortSignal): Promise<void> {
    requireToken(token);
    const response = await this.fetcher("/api/v1/session", {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
      signal,
    });
    if (!response.ok) {
      throw await parseError(response);
    }
  }

  private async requestJson(url: string, init: RequestInit): Promise<unknown> {
    const response = await this.fetcher(url, init);
    if (!response.ok) {
      throw await parseError(response);
    }
    const contentType = response.headers.get("Content-Type") ?? "";
    if (!contentType.toLowerCase().startsWith("application/json")) {
      throw new Error("DeviceBridge returned a non-JSON response");
    }
    return response.json() as Promise<unknown>;
  }
}

async function parseError(response: Response): Promise<SessionApiError> {
  const fallback = new SessionApiError(
    response.status,
    response.status === 401 ? "UNAUTHORIZED" : "INVALID_PAYLOAD",
    "DeviceBridge отклонил запрос",
  );
  try {
    const value = await response.json() as unknown;
    if (!isRecord(value) || !isRecord(value.error)) return fallback;
    const code = value.error.code;
    const message = value.error.message;
    if (!isErrorCode(code) || typeof message !== "string") return fallback;
    return new SessionApiError(
      response.status,
      code,
      message,
      optionalNonNegativeNumber(value.error.retryAfterSeconds),
      optionalNonNegativeNumber(value.error.attemptsRemaining),
    );
  } catch {
    return fallback;
  }
}

function parseChallenge(value: unknown): SessionChallenge {
  const record = requireRecord(value);
  const challenge: SessionChallenge = {
    protocolVersion: requireNumber(record.protocolVersion),
    challengeId: requireString(record.challengeId),
    expiresAtEpochMillis: requireNumber(record.expiresAtEpochMillis),
    confirmTimeoutSeconds: requireNumber(record.confirmTimeoutSeconds),
    attemptsRemaining: requireNumber(record.attemptsRemaining),
  };
  requireCompatible(challenge.protocolVersion);
  return challenge;
}

function parseConfirmation(value: unknown): SessionConfirmation {
  const record = requireRecord(value);
  const confirmation: SessionConfirmation = {
    protocolVersion: requireNumber(record.protocolVersion),
    sessionId: requireString(record.sessionId),
    token: requireString(record.token),
    serverTimeEpochMillis: requireNumber(record.serverTimeEpochMillis),
  };
  requireCompatible(confirmation.protocolVersion);
  requireToken(confirmation.token);
  return confirmation;
}

function parseStatus(value: unknown): SessionStatus {
  const record = requireRecord(value);
  const status: SessionStatus = {
    protocolVersion: requireNumber(record.protocolVersion),
    sessionId: requireString(record.sessionId),
    connected: requireBoolean(record.connected),
    activeSessionCount: requireNumber(record.activeSessionCount),
  };
  requireCompatible(status.protocolVersion);
  return status;
}

function requireCompatible(version: number): void {
  if (version !== SESSION_PROTOCOL_VERSION) {
    throw new SessionApiError(
      400,
      "UNSUPPORTED_VERSION",
      "Версия протокола не поддерживается",
    );
  }
}

function requireToken(token: string): void {
  if (token.length < 1 || token.length > 256 || /\s/.test(token)) {
    throw new Error("Invalid session credential");
  }
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (!isRecord(value)) throw new Error("Invalid DeviceBridge response");
  return value;
}

function requireString(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error("Invalid DeviceBridge response");
  }
  return value;
}

function requireNumber(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error("Invalid DeviceBridge response");
  }
  return value;
}

function requireBoolean(value: unknown): boolean {
  if (typeof value !== "boolean") throw new Error("Invalid DeviceBridge response");
  return value;
}

function optionalNonNegativeNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0
    ? value
    : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isErrorCode(value: unknown): value is SessionErrorCode {
  return typeof value === "string" && [
    "INVALID_PAYLOAD",
    "UNSUPPORTED_VERSION",
    "INVALID_CODE",
    "EXPIRED",
    "DENIED",
    "RATE_LIMITED",
    "CAPACITY_REACHED",
    "UNAUTHORIZED",
    "SESSION_CLOSED",
  ].includes(value);
}
