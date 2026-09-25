const MAX_SESSION_TOKEN_LENGTH = 256;
const PROTOCOL_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** A session bearer is 1–256 characters without whitespace. */
export function isSessionToken(token: string): boolean {
  return token.length >= 1 && token.length <= MAX_SESSION_TOKEN_LENGTH && !/\s/.test(token);
}

export function requireSessionToken(token: string): void {
  if (!isSessionToken(token)) throw new Error("Invalid session credential");
}

/** Message, transfer and scope identifiers: 1–64 characters of `A-Z a-z 0-9 _ -`. */
export function isProtocolId(value: unknown): value is string {
  return typeof value === "string" && PROTOCOL_ID_PATTERN.test(value);
}

export function requireJsonResponse(response: Response, message: string): void {
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) throw new Error(message);
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}
