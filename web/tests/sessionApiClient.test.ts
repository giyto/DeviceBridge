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
        effectiveFileLimitBytes: 536_870_912,
        deviceName: "Google Pixel 8",
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = new SessionApiClient(fetcher);

    await client.createChallenge("Edge");
    await client.confirm("challenge-1", "123456", "Edge");
    const status = await client.status("secret-token");
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
    expect(status.effectiveFileLimitBytes).toBe(536_870_912);
    expect(status.deviceName).toBe("Google Pixel 8");
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

  it("requests trust and exchanges credential only in same-origin JSON bodies", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        challengeId: "challenge-trusted",
        expiresAtEpochMillis: 10_000,
        confirmTimeoutSeconds: 60,
        attemptsRemaining: 5,
      }))
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        sessionId: "session-paired",
        token: "temporary-token",
        serverTimeEpochMillis: 11_000,
        trustedCredential: "trusted_ABC-123",
        trustedCredentialExpiresAtEpochMillis: 99_000,
      }))
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        sessionId: "session-restored",
        token: "new-temporary-token",
        serverTimeEpochMillis: 12_000,
      }));
    const client = new SessionApiClient(fetcher);

    await client.createChallenge("Edge", true);
    const paired = await client.confirm("challenge-trusted", "123456", "Edge");
    const restored = await client.exchangeTrusted("trusted_ABC-123");

    expect(paired.trustedCredential).toBe("trusted_ABC-123");
    expect(paired.trustedCredentialExpiresAtEpochMillis).toBe(99_000);
    expect(restored.token).toBe("new-temporary-token");
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/session/challenge",
      "/api/v1/session/confirm",
      "/api/v1/session/trusted",
    ]);
    expect(JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))).toMatchObject({
      rememberBrowserRequested: true,
    });
    expect(JSON.parse(String(fetcher.mock.calls[2]?.[1]?.body))).toEqual({
      protocolVersion: 1,
      trustedCredential: "trusted_ABC-123",
    });
    expect(JSON.stringify(fetcher.mock.calls)).not.toContain("Authorization");
    expect(fetcher.mock.calls.every(([url]) => !String(url).includes("?"))).toBe(true);
  });

  it("checks the original pairing request without resending its code", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        state: "PENDING",
      }))
      .mockResolvedValueOnce(jsonResponse({
        protocolVersion: 1,
        state: "APPROVED",
        sessionId: "session-recovered",
        token: "recovered-token",
        serverTimeEpochMillis: 12_000,
      }));
    const client = new SessionApiClient(fetcher);

    const pending = await client.recoverConfirmation("challenge-1", "Edge");
    const approved = await client.recoverConfirmation("challenge-1", "Edge");

    expect(pending).toEqual({ protocolVersion: 1, state: "PENDING" });
    expect(approved).toMatchObject({
      state: "APPROVED",
      token: "recovered-token",
    });
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/session/confirmation/status",
      "/api/v1/session/confirmation/status",
    ]);
    for (const [, init] of fetcher.mock.calls) {
      expect(JSON.parse(String(init?.body))).toEqual({
        protocolVersion: 1,
        challengeId: "challenge-1",
        clientLabel: "Edge",
      });
      expect(String(init?.body)).not.toContain("123456");
    }
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
