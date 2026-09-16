import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SessionController,
  type SessionApi,
  type SessionEventChannel,
  type SessionTokenStore,
  type SessionUiState,
} from "../src/sessionController";
import { SessionApiError } from "../src/sessionApiClient";
import type { WebManifest } from "../src/webManifestClient";
import { ManifestCompatibilityError } from "../src/webManifestClient";
import type {
  TextErrorEvent,
  TextReceivedEvent,
  TextSnapshotEvent,
} from "../src/sessionEventSocketClient";

const manifest: WebManifest = { protocolVersion: 1, webAssetVersion: "sha256-test" };

afterEach(() => vi.useRealTimers());

describe("SessionController", () => {
  it("moves through ready, submitting, awaiting and connected without exposing token", async () => {
    const fixture = createFixture();
    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("ready"));

    fixture.controller.submitCode("123456");
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));

    expect(fixture.states.map((state) => state.kind)).toEqual([
      "checking", "ready", "submitting", "awaiting", "connected",
    ]);
    expect(JSON.stringify(fixture.states)).not.toContain("secret-token");
    expect(fixture.store.saved).toBe("secret-token");
    expect(fixture.events.connectedWith).toBe("secret-token");
  });

  it("blocks conflicting submissions and maps rate-limit, denied and expired errors", async () => {
    const api = fakeApi();
    const pending = new Promise<never>(() => undefined);
    api.confirm = vi.fn().mockReturnValueOnce(pending);
    const fixture = createFixture(api);
    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("ready"));

    fixture.controller.submitCode("123456");
    fixture.controller.submitCode("654321");
    await vi.waitFor(() => expect(api.confirm).toHaveBeenCalledOnce());
    fixture.controller.dispose();

    for (const [code, expected] of [
      ["RATE_LIMITED", "blocked"],
      ["DENIED", "denied"],
      ["EXPIRED", "expired"],
    ] as const) {
      const failing = fakeApi();
      failing.confirm = vi.fn().mockRejectedValue(new SessionApiError(400, code, code, 60));
      const attempt = createFixture(failing);
      attempt.controller.start();
      await vi.waitFor(() => expect(attempt.states.at(-1)?.kind).toBe("ready"));
      attempt.controller.submitCode("123456");
      await vi.waitFor(() => expect(attempt.states.at(-1)?.kind).toBe(expected));
    }
  });

  it("restores the current tab, clears token on 401 and revokes explicitly", async () => {
    const api = fakeApi();
    const store = new FakeTokenStore("restored-token");
    const restored = createFixture(api, store);
    restored.controller.start();
    await vi.waitFor(() => expect(restored.states.at(-1)?.kind).toBe("connected"));
    expect(api.status).toHaveBeenCalledWith("restored-token", expect.any(AbortSignal));

    restored.controller.disconnect();
    await vi.waitFor(() => expect(restored.states.at(-1)?.kind).toBe("ready"));
    expect(api.close).toHaveBeenCalledWith("restored-token", expect.any(AbortSignal));
    expect(store.saved).toBeUndefined();

    const rejectedApi = fakeApi();
    rejectedApi.status = vi.fn().mockRejectedValue(
      new SessionApiError(401, "UNAUTHORIZED", "Недействительная сессия"),
    );
    const rejectedStore = new FakeTokenStore("stale-token");
    const rejected = createFixture(rejectedApi, rejectedStore);
    rejected.controller.start();
    await vi.waitFor(() => expect(rejected.states.at(-1)?.kind).toBe("ready"));
    expect(rejectedStore.saved).toBeUndefined();
  });

  it("clears the token when protocol is incompatible or event channel loses auth", async () => {
    const incompatibleStore = new FakeTokenStore("old-token");
    const incompatible = createFixture(
      fakeApi(),
      incompatibleStore,
      { load: vi.fn().mockRejectedValue(new ManifestCompatibilityError(2)) },
    );
    incompatible.controller.start();
    await vi.waitFor(() => expect(incompatible.states.at(-1)?.kind).toBe("sessionLost"));
    expect(incompatibleStore.saved).toBeUndefined();

    const disconnectedApi = fakeApi();
    disconnectedApi.status = vi.fn()
      .mockResolvedValueOnce({
        protocolVersion: 1,
        sessionId: "session-1",
        connected: true,
        activeSessionCount: 1,
      })
      .mockRejectedValueOnce(
        new SessionApiError(401, "UNAUTHORIZED", "Недействительная сессия"),
      );
    const connected = createFixture(disconnectedApi, new FakeTokenStore("token"));
    connected.controller.start();
    await vi.waitFor(() => expect(connected.states.at(-1)?.kind).toBe("connected"));
    connected.events.lose();
    await vi.waitFor(() => expect(connected.states.at(-1)?.kind).toBe("sessionLost"));
    expect(connected.store.saved).toBeUndefined();
  });

  it("keeps the tab session when the event channel drops but status still authorizes it", async () => {
    const api = fakeApi();
    const store = new FakeTokenStore("token");
    const connected = createFixture(api, store);
    connected.controller.start();
    await vi.waitFor(() => expect(connected.states.at(-1)?.kind).toBe("connected"));

    connected.events.lose();

    await vi.waitFor(() => expect(api.status).toHaveBeenCalledTimes(2));
    expect(connected.states.at(-1)?.kind).toBe("connected");
    expect(store.saved).toBe("token");
    expect(connected.events.connectedWith).toBe("token");
  });

  it("activates text only for a connected session and routes socket text events", async () => {
    const text = new FakeTextSession();
    const fixture = createFixture(
      fakeApi(),
      new FakeTokenStore("token"),
      { load: vi.fn().mockResolvedValue(manifest) },
      text,
    );

    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));
    expect(text.activeToken).toBe("token");

    fixture.events.receiveText(textReceived);
    fixture.events.receiveSnapshot(textSnapshot);
    fixture.events.receiveTextError(textError);
    expect(text.received).toEqual([textReceived]);
    expect(text.snapshots).toEqual([textSnapshot]);
    expect(text.errors).toEqual([textError]);

    fixture.controller.handleTextUnauthorized();
    expect(fixture.states.at(-1)?.kind).toBe("sessionLost");
    expect(fixture.store.saved).toBeUndefined();
    expect(text.activeToken).toBeUndefined();
  });

  it("uses bounded offline retries and lets the user start a fresh cycle", async () => {
    vi.useFakeTimers();
    const loader = { load: vi.fn().mockRejectedValue(new Error("offline")) };
    const fixture = createFixture(fakeApi(), new FakeTokenStore(), loader);

    fixture.controller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(fixture.states.at(-1)).toMatchObject({ kind: "offline", nextRetryInMs: 1_000 });
    await vi.advanceTimersByTimeAsync(1_000 + 2_000 + 4_000);
    expect(loader.load).toHaveBeenCalledTimes(4);
    expect(fixture.states.at(-1)).toEqual({
      kind: "offline",
      message: "Не удаётся связаться с DeviceBridge.",
      nextRetryInMs: undefined,
    });

    fixture.controller.retry();
    await vi.advanceTimersByTimeAsync(0);
    expect(loader.load).toHaveBeenCalledTimes(5);
    fixture.controller.dispose();
  });
});

function createFixture(
  api = fakeApi(),
  store = new FakeTokenStore(),
  loader = { load: vi.fn().mockResolvedValue(manifest) },
  textSession = new FakeTextSession(),
) {
  const states: SessionUiState[] = [];
  const events = new FakeEventChannel();
  const controller = new SessionController(
    loader,
    api,
    store,
    events,
    (state) => states.push(state),
    "Edge on Windows",
    textSession,
  );
  return { controller, states, api, store, events, textSession };
}

function fakeApi(): SessionApi {
  return {
    createChallenge: vi.fn().mockResolvedValue({
      protocolVersion: 1,
      challengeId: "challenge-1",
      expiresAtEpochMillis: 10_000,
      confirmTimeoutSeconds: 60,
      attemptsRemaining: 5,
    }),
    confirm: vi.fn().mockResolvedValue({
      protocolVersion: 1,
      sessionId: "session-1",
      token: "secret-token",
      serverTimeEpochMillis: 11_000,
    }),
    status: vi.fn().mockResolvedValue({
      protocolVersion: 1,
      sessionId: "session-1",
      connected: true,
      activeSessionCount: 1,
    }),
    close: vi.fn().mockResolvedValue(undefined),
  };
}

class FakeTokenStore implements SessionTokenStore {
  constructor(public saved?: string) {}
  read(): string | undefined { return this.saved; }
  save(token: string): void { this.saved = token; }
  clear(): void { this.saved = undefined; }
}

class FakeEventChannel implements SessionEventChannel {
  connectedWith?: string;
  private callbacks?: Parameters<SessionEventChannel["connect"]>[1];
  connect(token: string, callbacks: Parameters<SessionEventChannel["connect"]>[1]): void {
    this.connectedWith = token;
    this.callbacks = callbacks;
  }
  disconnect(): void { this.connectedWith = undefined; }
  lose(): void { this.callbacks?.onSessionLost(); }
  receiveText(event: TextReceivedEvent): void { this.callbacks?.onTextReceived?.(event); }
  receiveSnapshot(event: TextSnapshotEvent): void { this.callbacks?.onTextSnapshot?.(event); }
  receiveTextError(event: TextErrorEvent): void { this.callbacks?.onTextError?.(event); }
}

class FakeTextSession {
  activeToken?: string;
  readonly received: TextReceivedEvent[] = [];
  readonly snapshots: TextSnapshotEvent[] = [];
  readonly errors: TextErrorEvent[] = [];
  activate(token: string): void { this.activeToken = token; }
  deactivate(): void { this.activeToken = undefined; }
  receive(event: TextReceivedEvent): void { this.received.push(event); }
  applySnapshot(event: TextSnapshotEvent): void { this.snapshots.push(event); }
  receiveError(event: TextErrorEvent): void { this.errors.push(event); }
}

const textReceived: TextReceivedEvent = {
  protocolVersion: 1,
  messageId: "text-1",
  type: "text.received",
  timestamp: 1_000,
  content: "hello",
  contentKind: "TEXT",
  direction: "ANDROID_TO_BROWSER",
  senderLabel: "Телефон",
  status: "DELIVERED",
};

const textSnapshot: TextSnapshotEvent = {
  protocolVersion: 1,
  messageId: "snapshot-1",
  type: "text.snapshot",
  timestamp: 1_000,
  items: [textReceived],
};

const textError: TextErrorEvent = {
  protocolVersion: 1,
  messageId: "error-1",
  type: "text.error",
  timestamp: 1_000,
  relatedMessageId: "text-1",
  code: "SESSION_UNAVAILABLE",
};
