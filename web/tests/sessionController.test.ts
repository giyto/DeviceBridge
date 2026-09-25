import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SessionController,
  type SessionApi,
  type SessionEventChannel,
  type SessionTokenStore,
  type TrustedCredentialStore,
  type SessionUiState,
  type SessionUiEffect,
  type WaitingPorts,
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
    expect(fixture.fileSession.effectiveFileLimitBytes).toBe(1_073_741_824);
    expect(fixture.events.connectedWith).toBe("secret-token");
    expect(fixture.trustedStore.saved).toBeUndefined();
    expect(fixture.effects).toEqual([{
      id: "clear-pairing-form:session-1",
      kind: "clearPairingForm",
    }]);
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

  it("recovers an uncertain pairing result without resubmitting the code", async () => {
    const api = fakeApi();
    api.confirm = vi.fn().mockRejectedValueOnce(new TypeError("connection lost"));
    api.recoverConfirmation = vi.fn()
      .mockResolvedValueOnce({ protocolVersion: 1, state: "PENDING" })
      .mockResolvedValueOnce({
        protocolVersion: 1,
        state: "APPROVED",
        sessionId: "session-recovered",
        token: "recovered-token",
        serverTimeEpochMillis: 12_000,
      });
    const fixture = createFixture(api);
    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("ready"));

    fixture.controller.submitCode("123456");
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("uncertain"));

    fixture.controller.retry();
    await vi.waitFor(() => expect(api.recoverConfirmation).toHaveBeenCalledOnce());
    expect(fixture.states.at(-1)?.kind).toBe("uncertain");

    fixture.controller.retry();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));

    expect(api.confirm).toHaveBeenCalledOnce();
    expect(api.createChallenge).toHaveBeenCalledOnce();
    expect(api.recoverConfirmation).toHaveBeenCalledTimes(2);
    expect(api.recoverConfirmation).toHaveBeenCalledWith(
      "challenge-1",
      "Edge on Windows",
      expect.any(AbortSignal),
    );
    expect(fixture.store.saved).toBe("recovered-token");
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
        effectiveFileLimitBytes: 1_073_741_824,
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


  it("reports bounded event reconnect then revalidates the authoritative session", async () => {
    const api = fakeApi();
    const store = new FakeTokenStore("token");
    const fixture = createFixture(api, store);
    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));

    fixture.events.reconnect(1, 1_000);
    expect(fixture.states.at(-1)).toMatchObject({
      kind: "reconnecting",
      attempt: 1,
      nextRetryInMs: 1_000,
    });
    expect(store.saved).toBe("token");
    expect(api.confirm).not.toHaveBeenCalled();
    expect(api.createChallenge).not.toHaveBeenCalled();
    expect(fixture.textSession.connectionAvailable).toBe(false);
    expect(fixture.fileSession.connectionAvailable).toBe(false);

    fixture.events.authenticate();
    await vi.waitFor(() => expect(api.status).toHaveBeenCalledTimes(2));
    expect(fixture.states.at(-1)?.kind).toBe("connected");
    expect(api.confirm).not.toHaveBeenCalled();
    expect(api.createChallenge).not.toHaveBeenCalled();
    expect(fixture.textSession.connectionAvailable).toBe(true);
    expect(fixture.fileSession.connectionAvailable).toBe(true);
  });

  it("stops after the event reconnect budget is exhausted without deleting credentials", async () => {
    const api = fakeApi();
    const store = new FakeTokenStore("token");
    const trusted = new FakeTrustedStore({
      credential: "trusted-token",
      expiresAtEpochMillis: 99_000,
    });
    const fixture = createFixture(
      api,
      store,
      { load: vi.fn().mockResolvedValue(manifest) },
      new FakeTextSession(),
      new FakeFileSession(),
      trusted,
    );
    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));

    fixture.events.lose("reconnect_exhausted");

    expect(fixture.states.at(-1)?.kind).toBe("waiting");
    expect(store.saved).toBe("token");
    expect(trusted.saved?.credential).toBe("trusted-token");
    expect(api.status).toHaveBeenCalledOnce();
    expect(api.createChallenge).not.toHaveBeenCalled();
    expect(fixture.fileSession.connectionAvailable).toBe(false);
    fixture.controller.dispose();
  });

  it("waits for the phone, then signs in with the remembered browser without a code", async () => {
    vi.useFakeTimers();
    const api = fakeApi();
    api.status = vi.fn(async (token: string) => {
      if (token === "old-token") throw new SessionApiError(401, "UNAUTHORIZED", "Старая сессия");
      return {
        protocolVersion: 1,
        sessionId: "session-2",
        connected: true,
        activeSessionCount: 1,
        effectiveFileLimitBytes: 1_073_741_824,
        deviceName: "Pixel",
      };
    });
    const loader = { load: vi.fn().mockRejectedValue(new TypeError("offline")) };
    const fileSession = new FakeFileSession();
    const fixture = createFixture(
      api,
      new FakeTokenStore("old-token"),
      loader,
      new FakeTextSession(),
      fileSession,
      new FakeTrustedStore({ credential: "trusted-token", expiresAtEpochMillis: 99_000 }),
    );

    fixture.controller.start();
    await vi.advanceTimersByTimeAsync(1_000 + 2_000 + 4_000);
    expect(fixture.states.at(-1)).toEqual({ kind: "waiting" });
    await vi.advanceTimersByTimeAsync(3_000);
    expect(loader.load).toHaveBeenCalledTimes(5);
    expect(fixture.states.at(-1)).toEqual({ kind: "waiting" });

    loader.load.mockResolvedValue(manifest);
    await vi.advanceTimersByTimeAsync(3_000);

    expect(fixture.states.at(-1)?.kind).toBe("connected");
    expect(api.exchangeTrusted).toHaveBeenCalledOnce();
    expect(api.createChallenge).not.toHaveBeenCalled();
    expect(api.confirm).not.toHaveBeenCalled();
    expect(fixture.store.saved).toBe("trusted-session-token");
    expect(fileSession.suspended).toBe(1);
    fixture.controller.dispose();
  });

  it("without a remembered browser the form says the phone is back and asks to remember", async () => {
    vi.useFakeTimers();
    const api = fakeApi();
    const loader = { load: vi.fn().mockRejectedValue(new TypeError("offline")) };
    const fixture = createFixture(api, new FakeTokenStore(), loader);

    fixture.controller.start();
    await vi.advanceTimersByTimeAsync(7_000);
    loader.load.mockResolvedValue(manifest);
    fixture.controller.retry();
    await vi.advanceTimersByTimeAsync(0);

    expect(fixture.states.at(-1)).toMatchObject({ kind: "ready", notice: "phoneReturned" });
    expect(api.createChallenge).toHaveBeenCalledWith("Edge on Windows", true, expect.any(AbortSignal));
    expect(api.confirm).not.toHaveBeenCalled();
    fixture.controller.dispose();
  });

  it("checks less often while the tab is hidden and at once when it comes back", async () => {
    vi.useFakeTimers();
    const visibility = new FakeVisibility(false);
    const loader = { load: vi.fn().mockRejectedValue(new TypeError("offline")) };
    const fixture = createFixture(
      fakeApi(),
      new FakeTokenStore(),
      loader,
      new FakeTextSession(),
      new FakeFileSession(),
      new FakeTrustedStore(),
      { visibility, online: { onOnline: () => () => undefined } },
    );

    fixture.controller.start();
    await vi.advanceTimersByTimeAsync(7_000);
    expect(loader.load).toHaveBeenCalledTimes(4);
    await vi.advanceTimersByTimeAsync(14_000);
    expect(loader.load).toHaveBeenCalledTimes(4);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(loader.load).toHaveBeenCalledTimes(5);

    visibility.show();
    await vi.advanceTimersByTimeAsync(0);
    expect(loader.load).toHaveBeenCalledTimes(6);
    fixture.controller.dispose();
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

    fixture.controller.handleTransferUnauthorized();
    expect(fixture.states.at(-1)?.kind).toBe("sessionLost");
    expect(fixture.store.saved).toBeUndefined();
    expect(text.activeToken).toBeUndefined();
    expect(text.suspendCount).toBe(1);
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
    // After the quick retries the page keeps waiting instead of giving up.
    expect(fixture.states.at(-1)).toEqual({ kind: "waiting" });

    fixture.controller.retry();
    await vi.advanceTimersByTimeAsync(0);
    expect(loader.load).toHaveBeenCalledTimes(5);
    // One question at a time: a second press while one is in flight asks nothing more.
    let finish: (value: WebManifest) => void = () => undefined;
    loader.load.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    fixture.controller.retry();
    fixture.controller.retry();
    await vi.advanceTimersByTimeAsync(0);
    expect(loader.load).toHaveBeenCalledTimes(6);
    finish(manifest);
    fixture.controller.dispose();
  });

  it("recovers through trusted exchange once after sessionStorage and saves a fresh tab token", async () => {
    const api = fakeApi();
    const trusted = new FakeTrustedStore({
      credential: "trusted-token",
      expiresAtEpochMillis: 99_000,
    });
    const fixture = createFixture(
      api,
      new FakeTokenStore(),
      { load: vi.fn().mockResolvedValue(manifest) },
      new FakeTextSession(),
      new FakeFileSession(),
      trusted,
    );

    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));

    expect(api.exchangeTrusted).toHaveBeenCalledWith("trusted-token", expect.any(AbortSignal));
    expect(api.createChallenge).not.toHaveBeenCalled();
    expect(fixture.store.saved).toBe("trusted-session-token");
    expect(trusted.saved?.credential).toBe("trusted-token");
  });

  it("clears revoked trust then pairs, while a network failure does not exchange in a retry loop", async () => {
    const revokedApi = fakeApi();
    revokedApi.exchangeTrusted = vi.fn().mockRejectedValue(
      new SessionApiError(401, "UNAUTHORIZED", "Отозвано"),
    );
    const revokedTrust = new FakeTrustedStore({
      credential: "revoked-token",
      expiresAtEpochMillis: 99_000,
    });
    const revoked = createFixture(
      revokedApi,
      new FakeTokenStore(),
      { load: vi.fn().mockResolvedValue(manifest) },
      new FakeTextSession(),
      new FakeFileSession(),
      revokedTrust,
    );

    revoked.controller.start();
    await vi.waitFor(() => expect(revoked.states.at(-1)?.kind).toBe("ready"));
    expect(revokedTrust.saved).toBeUndefined();
    expect(revokedApi.createChallenge).toHaveBeenCalledOnce();
    expect(revoked.states.at(-1)).toMatchObject({ kind: "ready", notice: "trustRejected" });

    vi.useFakeTimers();
    const offlineApi = fakeApi();
    offlineApi.exchangeTrusted = vi.fn().mockRejectedValue(new TypeError("offline"));
    offlineApi.createChallenge = vi.fn().mockRejectedValue(new TypeError("offline"));
    const offline = createFixture(
      offlineApi,
      new FakeTokenStore(),
      { load: vi.fn().mockResolvedValue(manifest) },
      new FakeTextSession(),
      new FakeFileSession(),
      new FakeTrustedStore({ credential: "trusted-token", expiresAtEpochMillis: 99_000 }),
    );
    offline.controller.start();
    await vi.advanceTimersByTimeAsync(1_000 + 2_000);
    // The quick retries do not exchange again; only a new cycle after waiting does.
    expect(offlineApi.exchangeTrusted).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(4_000);
    expect(offlineApi.exchangeTrusted).toHaveBeenCalledTimes(2);
    offline.controller.dispose();
  });

  it("requests remember on submit and persists only a credential approved by the phone", async () => {
    const api = fakeApi();
    api.confirm = vi.fn().mockResolvedValue({
      protocolVersion: 1,
      sessionId: "session-remembered",
      token: "session-token",
      serverTimeEpochMillis: 11_000,
      trustedCredential: "trusted-token",
      trustedCredentialExpiresAtEpochMillis: 99_000,
    });
    const trusted = new FakeTrustedStore();
    const fixture = createFixture(
      api,
      new FakeTokenStore(),
      { load: vi.fn().mockResolvedValue(manifest) },
      new FakeTextSession(),
      new FakeFileSession(),
      trusted,
    );
    fixture.controller.start();
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("ready"));

    fixture.controller.submitCode("123456", true);
    await vi.waitFor(() => expect(fixture.states.at(-1)?.kind).toBe("connected"));

    expect(api.createChallenge).toHaveBeenLastCalledWith(
      "Edge on Windows",
      true,
      expect.any(AbortSignal),
    );
    expect(trusted.saved).toEqual({
      credential: "trusted-token",
      expiresAtEpochMillis: 99_000,
    });
  });
});

function createFixture(
  api = fakeApi(),
  store = new FakeTokenStore(),
  loader = { load: vi.fn().mockResolvedValue(manifest) },
  textSession = new FakeTextSession(),
  fileSession = new FakeFileSession(),
  trustedStore = new FakeTrustedStore(),
  waitingPorts?: WaitingPorts,
) {
  const states: SessionUiState[] = [];
  const effects: SessionUiEffect[] = [];
  const events = new FakeEventChannel();
  const controller = new SessionController(
    loader,
    api,
    store,
    events,
    (state) => states.push(state),
    (effect) => effects.push(effect),
    "Edge on Windows",
    textSession,
    fileSession,
    trustedStore,
    {
      setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
      clearTimeout: (handle) => globalThis.clearTimeout(handle as number),
    },
    waitingPorts ?? {
      visibility: { isVisible: () => true, onChange: () => () => undefined },
      online: { onOnline: () => () => undefined },
    },
  );
  return { controller, states, effects, api, store, events, textSession, fileSession, trustedStore };
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
    recoverConfirmation: vi.fn().mockResolvedValue({
      protocolVersion: 1,
      state: "PENDING",
    }),
    exchangeTrusted: vi.fn().mockResolvedValue({
      protocolVersion: 1,
      sessionId: "trusted-session",
      token: "trusted-session-token",
      serverTimeEpochMillis: 11_000,
    }),
    status: vi.fn().mockResolvedValue({
      protocolVersion: 1,
      sessionId: "session-1",
      connected: true,
      activeSessionCount: 1,
      effectiveFileLimitBytes: 1_073_741_824,
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

class FakeTrustedStore implements TrustedCredentialStore {
  constructor(public saved?: { credential: string; expiresAtEpochMillis: number }) {}
  read() { return this.saved; }
  save(value: { credential: string; expiresAtEpochMillis: number }): void {
    this.saved = value;
  }
  clear(): void { this.saved = undefined; }
}

class FakeVisibility {
  private listeners: Array<() => void> = [];
  constructor(private visible: boolean) {}
  isVisible(): boolean { return this.visible; }
  onChange(listener: () => void): () => void {
    this.listeners.push(listener);
    return () => { this.listeners = this.listeners.filter((item) => item !== listener); };
  }
  show(): void {
    this.visible = true;
    for (const listener of this.listeners) listener();
  }
}

class FakeEventChannel implements SessionEventChannel {
  connectedWith?: string;
  private callbacks?: Parameters<SessionEventChannel["connect"]>[1];
  connect(token: string, callbacks: Parameters<SessionEventChannel["connect"]>[1]): void {
    this.connectedWith = token;
    this.callbacks = callbacks;
  }
  disconnect(): void { this.connectedWith = undefined; }
  lose(reason: "authorization" | "reconnect_exhausted" = "authorization"): void {
    this.callbacks?.onSessionLost(reason);
  }
  reconnect(attempt: number, delayMs: number): void { this.callbacks?.onReconnecting?.(attempt, delayMs); }
  authenticate(): void { this.callbacks?.onAuthenticated?.(); }
  receiveText(event: TextReceivedEvent): void { this.callbacks?.onTextReceived?.(event); }
  receiveSnapshot(event: TextSnapshotEvent): void { this.callbacks?.onTextSnapshot?.(event); }
  receiveTextError(event: TextErrorEvent): void { this.callbacks?.onTextError?.(event); }
}

class FakeTextSession {
  activeToken?: string;
  connectionAvailable = true;
  suspendCount = 0;
  disposeCount = 0;
  readonly received: TextReceivedEvent[] = [];
  readonly snapshots: TextSnapshotEvent[] = [];
  readonly errors: TextErrorEvent[] = [];
  activate(token: string): void { this.activeToken = token; }
  deactivate(): void { this.activeToken = undefined; }
  setConnectionAvailable(available: boolean): void { this.connectionAvailable = available; }
  suspendSession(): void {
    if (this.activeToken === undefined) return;
    this.activeToken = undefined;
    this.connectionAvailable = false;
    this.suspendCount += 1;
  }
  dispose(): void {
    this.activeToken = undefined;
    this.disposeCount += 1;
  }
  receive(event: TextReceivedEvent): void { this.received.push(event); }
  applySnapshot(event: TextSnapshotEvent): void { this.snapshots.push(event); }
  receiveError(event: TextErrorEvent): void { this.errors.push(event); }
}

class FakeFileSession {
  activeToken?: string;
  connectionAvailable = true;
  effectiveFileLimitBytes?: number;
  activate(token: string, effectiveFileLimitBytes?: number): void {
    this.activeToken = token;
    this.effectiveFileLimitBytes = effectiveFileLimitBytes;
  }
  deactivate(): void { this.activeToken = undefined; }
  suspended = 0;
  suspendSession(): void { this.suspended += 1; this.activeToken = undefined; }
  setConnectionAvailable(available: boolean): void { this.connectionAvailable = available; }
  receiveOffer(): void {}
  receiveProgress(): void {}
  applySnapshot(): void {}
  receiveError(): void {}
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
