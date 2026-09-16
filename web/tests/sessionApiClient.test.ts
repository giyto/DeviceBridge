import { describe, expect, it, vi } from "vitest";
import { SessionApiClient, SessionApiError } from "../src/sessionApiClient";

describe("SessionApiClient", () => {
  it("uses same-origin endpoints and sends bearer only in protected headers", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        challengeId: "challenge-1",
        expiresAtEpochMillis: 10_000,
        confirmTimeoutSeconds: 60,
        attemptsRemaining: 5,
      }))
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        sessionId: "session-1",
        token: "secret-token",
        serverTimeEpochMillis: 11_000,
      }))
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        sessionId: "session-1",
        connected: true,
        activeSessionCount: 1,
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = new SessionApiClient(fetcher);

    await client.createChallenge("Edge");
    await client.confirm("challenge-1", "123456", "Edge");
    await client.status("secret-token");
    await client.close("secret-token");

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/session/challenge",
      "/api/v1/session/confirm",
      "/api/v1/status",
      "/api/v1/session",
    ]);
    expect(JSON.stringify(fetcher.mock.calls)).not.toContain("?token=");
    const statusHeaders = new Headers(fetcher.mock.calls[2]?.[1]?.headers);
    expect(statusHeaders.get("Authorization")).toBe("Bearer secret-token");
    const closeHeaders = new Headers(fetcher.mock.calls[3]?.[1]?.headers);
    expect(closeHeaders.get("Authorization")).toBe("Bearer secret-token");
  });

  it("returns typed server errors without putting credentials in messages", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse(
        {
          error: {
            code: "RATE_LIMITED",
            message: "Слишком много попыток",
            retryAfterSeconds: 60,
            attemptsRemaining: 0,
          },
        },
        429,
      ),
    );

    const error = await new SessionApiClient(fetcher)
      .confirm("challenge-1", "000000", "Edge")
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(SessionApiError);
    expect(error).toMatchObject({
      status: 429,
      code: "RATE_LIMITED",
      retryAfterSeconds: 60,
      attemptsRemaining: 0,
    });
    expect(String(error)).not.toContain("000000");
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
