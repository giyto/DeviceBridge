import { describe, expect, it, vi } from "vitest";
// jsdom is test-only and this locked dependency does not ship TypeScript declarations.
// @ts-expect-error -- Vitest transpiles the runtime package correctly.
import { JSDOM } from "jsdom";
import { resolveFailure } from "../src/failureCatalog";
import {
  createFailureDetailsView,
  formatFailureTechnicalDetails,
} from "../src/failureDetailsView";

describe("failure technical details", () => {
  it("renders native keyboard controls and copies only allowlisted details", () => {
    const copyText = vi.fn<(value: string) => void>();
    const failure = resolveFailure("network_lost");
    const document = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" })
      .window.document;
    const view = createFailureDetailsView(
      failure,
      {
        operationId: "operation-42",
        lifecycleState: "running",
        sessionToken: "bearer-secret",
        path: "C:\\private\\video.mp4",
        content: "private message",
      },
      copyText,
      document,
    );

    expect(view.tagName).toBe("DETAILS");
    expect(view.querySelector("summary")?.textContent).toBe("Технические сведения");
    const button = view.querySelector("button");
    expect(button?.textContent).toBe("Скопировать сведения");
    button?.click();

    expect(copyText).toHaveBeenCalledOnce();
    const copied = copyText.mock.calls[0]?.[0] ?? "";
    expect(copied).toContain("Код: network_lost");
    expect(copied).toContain("operationId: operation-42");
    expect(copied).not.toContain("bearer-secret");
    expect(copied).not.toContain("private\\video.mp4");
    expect(copied).not.toContain("private message");
  });

  it("returns focus to the native summary when details close", () => {
    const document = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/" })
      .window.document;
    const view = createFailureDetailsView(
      resolveFailure("network_lost"),
      {},
      () => undefined,
      document,
    );
    document.body.append(view);
    view.open = true;
    view.querySelector<HTMLButtonElement>("button")!.focus();

    view.open = false;
    view.dispatchEvent(new view.ownerDocument.defaultView!.Event("toggle"));

    expect(document.activeElement).toBe(view.querySelector("summary"));
  });
  it("formats an unknown code through the safe fallback", () => {
    const details = formatFailureTechnicalDetails(
      resolveFailure("future_failure"),
      { failureCode: "future_failure", stackTrace: "private trace" },
    );

    expect(details).toContain("Код: unknown_error");
    expect(details).toContain("failureCode: future_failure");
    expect(details).not.toContain("private trace");
  });
});
