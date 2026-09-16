import { describe, expect, it, vi } from "vitest";
import {
  TextApiClient,
  TextApiError,
  type TextSendCommand,
} from "../src/textApiClient";

const command: TextSendCommand = {
  messageId: "browser-message-1",
  timestamp: 1_000,
  content: "Привет\nhttps://example.com",
};

describe("TextApiClient", () => {
  it("posts the versioned JSON schema with bearer only in the header", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({
        protocolVersion: 1,
        messageId: "browser-message-1",
        type: "text.accepted",
        timestamp: 2_000,
        contentKind: "TEXT",
        status: "DELIVERED",
      }),
    );

    const accepted = await new TextApiClient(fetcher).send("secret-token", command);

    expect(accepted).toEqual({
      protocolVersion: 1,
      messageId: "browser-message-1",
      type: "text.accepted",
      timestamp: 2_000,
      contentKind: "TEXT",
      status: "DELIVERED",
    });
    expect(fetcher).toHaveBeenCalledOnce();
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe("/api/v1/text");
    expect(String(url)).not.toContain("secret-token");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer secret-token");
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
    expect(JSON.parse(String(init?.body))).toEqual({
      protocolVersion: 1,
      messageId: "browser-message-1",
      type: "text.send",
      timestamp: 1_000,
      content: "Привет\nhttps://example.com",
    });
  });

  it("returns a typed unauthorized error from the shared session error schema", async () => {
    const error = await rejectedError(
      jsonResponse({
        error: {
          code: "UNAUTHORIZED",
          message: "Требуется действующая browser session",
        },
      }, 401),
    );

    expect(error).toBeInstanceOf(TextApiError);
    expect(error).toMatchObject({ status: 401, code: "UNAUTHORIZED" });
  });

  it.each([
    [409, "MESSAGE_CONFLICT"],
    [413, "CONTENT_TOO_LARGE"],
  ] as const)("returns typed %i text protocol errors", async (status, code) => {
    const error = await rejectedError(
      jsonResponse({
        protocolVersion: 1,
        messageId: "server-error",
        type: "text.error",
        timestamp: 2_000,
        relatedMessageId: command.messageId,
        code,
      }, status),
    );

    expect(error).toBeInstanceOf(TextApiError);
    expect(error).toMatchObject({
      status,
      code,
      relatedMessageId: command.messageId,
    });
  });

  it("forwards AbortSignal and preserves AbortError", async () => {
    const controller = new AbortController();
    const fetcher = vi.fn<typeof fetch>((_url, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => {
          reject(new DOMException("The operation was aborted", "AbortError"));
        });
      }),
    );
    const request = new TextApiClient(fetcher).send(
      "secret-token",
      command,
      controller.signal,
    );

    controller.abort();

    await expect(request).rejects.toMatchObject({ name: "AbortError" });
    expect(fetcher.mock.calls[0]?.[1]?.signal).toBe(controller.signal);
  });
});

async function rejectedError(response: Response): Promise<unknown> {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response);
  return new TextApiClient(fetcher)
    .send("secret-token", command)
    .catch((caught: unknown) => caught);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
