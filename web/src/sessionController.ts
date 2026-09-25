import {
  SESSION_PROTOCOL_VERSION,
  SessionApiError,
  type SessionChallenge,
  type SessionConfirmation,
  type SessionStatus,
} from "./sessionApiClient";
import { ManifestCompatibilityError, type WebManifest } from "./webManifestClient";
import { AvailabilityWaiter } from "./availabilityWaiter";
import { isAbortError } from "./protocolGuards";
import { browserScheduler, type Scheduler } from "./scheduler";
import {
  defaultWaitingPorts,
  noFileSession,
  noTextSession,
  noTrustedCredentialStore,
  type FileSessionLifecycle,
  type ManifestLoader,
  type SessionApi,
  type SessionEventChannel,
  type SessionTokenStore,
  type TextSessionLifecycle,
  type TrustedCredentialStore,
  type WaitingPorts,
} from "./sessionPorts";

export type {
  FileSessionLifecycle,
  ManifestLoader,
  SessionApi,
  SessionEventChannel,
  SessionTokenStore,
  TextSessionLifecycle,
  TrustedCredentialStore,
  WaitingPorts,
} from "./sessionPorts";

export type SessionUiState =
  | Readonly<{ kind: "checking" }>
  | Readonly<{
      kind: "ready";
      manifest: WebManifest;
      challenge: SessionChallenge;
      /** Why the form is shown again: the phone came back, or it did not recognise this browser. */
      notice?: ReadyNotice;
    }>
  | Readonly<{ kind: "submitting"; manifest: WebManifest }>
  | Readonly<{ kind: "awaiting"; manifest: WebManifest }>
  | Readonly<{ kind: "uncertain"; manifest: WebManifest; message: string; checking: boolean }>
  | Readonly<{ kind: "connected"; manifest: WebManifest; status: SessionStatus }>
  | Readonly<{
      kind: "reconnecting";
      manifest: WebManifest;
      status: SessionStatus;
      attempt: number;
      nextRetryInMs: number;
    }>
  | Readonly<{
      kind: "needsUserAction";
      manifest: WebManifest;
      status: SessionStatus;
      message: string;
    }>
  | Readonly<{ kind: "blocked"; manifest: WebManifest; message: string; retryAfterSeconds?: number }>
  | Readonly<{ kind: "expired"; manifest: WebManifest; message: string }>
  | Readonly<{ kind: "denied"; manifest: WebManifest; message: string }>
  | Readonly<{ kind: "sessionLost"; manifest?: WebManifest; message: string }>
  | Readonly<{ kind: "offline"; message: string; nextRetryInMs?: number }>
  /** The quick retries gave up; the page keeps checking and connects by itself. */
  | Readonly<{ kind: "waiting" }>;

export type ReadyNotice = "phoneReturned" | "trustRejected";

export type SessionUiEffect = Readonly<{
  id: string;
  kind: "clearPairingForm";
}>;

const RETRY_DELAYS_MS = [1_000, 2_000, 4_000] as const;
const OFFLINE_MESSAGE = "Не удаётся связаться с DeviceBridge.";
const INCOMPATIBLE_VERSION_MESSAGE = "Версия DeviceBridge несовместима с этой страницей.";
const SESSION_ENDED_MESSAGE = "Сессия завершена на телефоне. Подключитесь снова.";

export class SessionController {
  private generation = 0;
  private abortController?: AbortController;
  private retryHandle?: unknown;
  private currentState: SessionUiState = { kind: "checking" };
  private challenge?: SessionChallenge;
  private activeToken?: string;
  private busy = false;
  private challengeRememberRequested = true;
  private trustedExchangeAttempted = false;
  /** The next pairing form follows a wait for the phone. */
  private phoneReturned = false;
  private readonly waiter: AvailabilityWaiter;
  private pairingRecovery?: Readonly<{
    manifest: WebManifest;
    challengeId: string;
  }>;

  constructor(
    private readonly manifestLoader: ManifestLoader,
    private readonly api: SessionApi,
    private readonly tokenStore: SessionTokenStore,
    private readonly events: SessionEventChannel,
    private readonly onStateChange: (state: SessionUiState) => void,
    private readonly onEffect: (effect: SessionUiEffect) => void,
    private readonly clientLabel: string,
    private readonly textSession: TextSessionLifecycle = noTextSession,
    private readonly fileSession: FileSessionLifecycle = noFileSession,
    private readonly trustedCredentialStore: TrustedCredentialStore = noTrustedCredentialStore,
    private readonly scheduler: Scheduler = browserScheduler,
    waitingPorts: WaitingPorts = defaultWaitingPorts(),
  ) {
    this.waiter = new AvailabilityWaiter(
      (signal) => this.manifestLoader.load(signal).then(() => true),
      scheduler,
      waitingPorts.visibility,
      waitingPorts.online,
    );
  }

  start(): void {
    this.beginNewCycle();
  }

  retry(): void {
    if (this.currentState.kind === "waiting") {
      this.waiter.checkNow();
      return;
    }
    if (this.currentState.kind === "uncertain" && this.pairingRecovery !== undefined) {
      if (this.busy) return;
      this.busy = true;
      void this.recoverPairingConfirmation(this.generation, this.pairingRecovery);
      return;
    }
    this.beginNewCycle();
  }

  submitCode(code: string, rememberBrowserRequested = true): void {
    if (this.currentState.kind !== "ready" || this.busy) return;
    if (!/^\d{6}$/.test(code)) return;
    const generation = this.generation;
    const manifest = this.currentState.manifest;
    const challengeId = this.currentState.challenge.challengeId;
    this.busy = true;
    this.emit({ kind: "submitting", manifest });
    void this.confirm(
      generation,
      manifest,
      challengeId,
      code,
      rememberBrowserRequested,
    );
  }

  disconnect(): void {
    if (this.currentState.kind !== "connected" || this.busy) return;
    const token = this.activeToken ?? this.tokenStore.read();
    if (token === undefined) return;
    const generation = this.generation;
    const manifest = this.currentState.manifest;
    this.busy = true;
    void this.withRequest(async (signal) => {
      await this.api.close(token, signal).catch(() => undefined);
      if (!this.isCurrent(generation)) return;
      this.clearSession();
      this.busy = false;
      await this.createChallenge(generation, manifest);
    });
  }

  dispose(): void {
    this.generation += 1;
    this.cancelPending();
    this.events.disconnect();
    this.textSession.dispose();
    this.fileSession.deactivate();
  }

  handleTransferUnauthorized(): void {
    if (this.currentState.kind !== "connected") return;
    const manifest = this.currentState.manifest;
    this.generation += 1;
    this.cancelPending();
    this.busy = false;
    this.loseSession(manifest);
  }

  private beginNewCycle(keepFileDraft = false): void {
    this.generation += 1;
    this.cancelPending();
    this.events.disconnect();
    this.textSession.suspendSession();
    if (keepFileDraft) this.fileSession.suspendSession();
    else this.fileSession.deactivate();
    this.busy = false;
    this.challengeRememberRequested = true;
    this.trustedExchangeAttempted = false;
    this.pairingRecovery = undefined;
    void this.boot(this.generation, 0);
  }

  /**
   * The quick retries are over: keep asking quietly and start over once the phone answers.
   * Nothing the person did is repeated; the drafts stay for the next session.
   */
  private waitForPhone(): void {
    this.setTransfersAvailable(false);
    this.emit({ kind: "waiting" });
    this.waiter.start(() => {
      this.phoneReturned = true;
      this.beginNewCycle(true);
    });
  }

  private async boot(generation: number, retryIndex: number): Promise<void> {
    if (!this.isCurrent(generation)) return;
    await this.withRequest(async (signal) => {
      this.emit({ kind: "checking" });
      try {
        const manifest = await this.manifestLoader.load(signal);
        if (!this.isCurrent(generation)) return;
        if (manifest.protocolVersion !== SESSION_PROTOCOL_VERSION) {
          this.loseToIncompatibleVersion(manifest);
          return;
        }
        const token = this.tokenStore.read();
        if (token !== undefined) {
          try {
            const status = await this.api.status(token, signal);
            if (!this.isCurrent(generation)) return;
            this.connect(manifest, token, status);
            return;
          } catch (error: unknown) {
            if (!isUnauthorized(error)) throw error;
            this.clearSession(true);
          }
        }
        const trusted = this.trustedCredentialStore.read();
        let trustRejected = false;
        if (trusted !== undefined && !this.trustedExchangeAttempted) {
          this.trustedExchangeAttempted = true;
          try {
            const confirmation = await this.api.exchangeTrusted(
              trusted.credential,
              signal,
            );
            if (!this.isCurrent(generation)) return;
            this.tokenStore.save(confirmation.token);
            this.activeToken = confirmation.token;
            const status = await this.api.status(confirmation.token, signal);
            if (!this.isCurrent(generation)) return;
            this.connect(manifest, confirmation.token, status);
            return;
          } catch (error: unknown) {
            if (isTrustedCredentialRejected(error)) {
              this.trustedCredentialStore.clear();
              trustRejected = true;
            } else {
              throw error;
            }
          }
        }
        const notice: ReadyNotice | undefined = trustRejected
          ? "trustRejected"
          : this.phoneReturned ? "phoneReturned" : undefined;
        this.phoneReturned = false;
        await this.createChallenge(generation, manifest, true, notice);
      } catch (error: unknown) {
        if (!this.isCurrent(generation) || isAbortError(error)) return;
        if (error instanceof ManifestCompatibilityError) {
          this.loseToIncompatibleVersion();
          return;
        }
        this.scheduleOfflineRetry(generation, retryIndex);
      }
    });
  }

  private async createChallenge(
    generation: number,
    manifest: WebManifest,
    rememberBrowserRequested = true,
    notice?: ReadyNotice,
  ): Promise<void> {
    try {
      const challenge = await this.api.createChallenge(
        this.clientLabel,
        rememberBrowserRequested,
        this.abortController?.signal,
      );
      if (this.isCurrent(generation)) {
        this.challenge = challenge;
        this.challengeRememberRequested = rememberBrowserRequested;
        this.emit(notice === undefined
          ? { kind: "ready", manifest, challenge }
          : { kind: "ready", manifest, challenge, notice });
      }
    } catch (error: unknown) {
      if (!this.isCurrent(generation) || isAbortError(error)) return;
      this.emitError(manifest, error);
    }
  }

  private async confirm(
    generation: number,
    manifest: WebManifest,
    challengeId: string,
    code: string,
    rememberBrowserRequested: boolean,
  ): Promise<void> {
    await Promise.resolve();
    if (!this.isCurrent(generation)) return;
    await this.withRequest(async (signal) => {
      try {
        let activeChallengeId = challengeId;
        if (rememberBrowserRequested !== this.challengeRememberRequested) {
          const challenge = await this.api.createChallenge(
            this.clientLabel,
            rememberBrowserRequested,
            signal,
          );
          if (!this.isCurrent(generation)) return;
          this.challenge = challenge;
          this.challengeRememberRequested = rememberBrowserRequested;
          activeChallengeId = challenge.challengeId;
        }
        this.emit({ kind: "awaiting", manifest });
        this.pairingRecovery = {
          manifest,
          challengeId: activeChallengeId,
        };
        const confirmation = await this.api.confirm(
          activeChallengeId,
          code,
          this.clientLabel,
          signal,
        );
        if (!this.isCurrent(generation)) return;
        await this.acceptConfirmation(generation, manifest, confirmation, signal);
      } catch (error: unknown) {
        if (!this.isCurrent(generation) || isAbortError(error)) return;
        if (error instanceof SessionApiError) {
          this.pairingRecovery = undefined;
          this.emitError(manifest, error);
        } else {
          this.emit({
            kind: "uncertain",
            manifest,
            message: "Ответ о подключении не получен. Проверьте исходный запрос.",
            checking: false,
          });
        }
      } finally {
        this.busy = false;
      }
    });
  }

  private async recoverPairingConfirmation(
    generation: number,
    recovery: Readonly<{ manifest: WebManifest; challengeId: string }>,
  ): Promise<void> {
    await this.withRequest(async (signal) => {
      this.emit({
        kind: "uncertain",
        manifest: recovery.manifest,
        message: "Проверяем результат исходного запроса…",
        checking: true,
      });
      try {
        const result = await this.api.recoverConfirmation(
          recovery.challengeId,
          this.clientLabel,
          signal,
        );
        if (!this.isCurrent(generation)) return;
        switch (result.state) {
          case "PENDING":
            this.emit({
              kind: "uncertain",
              manifest: recovery.manifest,
              message: "Запрос всё ещё ожидает решения на телефоне.",
              checking: false,
            });
            break;
          case "DENIED":
            this.pairingRecovery = undefined;
            this.emit({
              kind: "denied",
              manifest: recovery.manifest,
              message: "Подключение отклонено на телефоне.",
            });
            break;
          case "EXPIRED":
            this.pairingRecovery = undefined;
            this.emit({
              kind: "expired",
              manifest: recovery.manifest,
              message: "Код или запрос истёк.",
            });
            break;
          case "APPROVED":
            await this.acceptConfirmation(
              generation,
              recovery.manifest,
              result,
              signal,
            );
            break;
        }
      } catch (error: unknown) {
        if (!this.isCurrent(generation) || isAbortError(error)) return;
        if (error instanceof SessionApiError) {
          this.pairingRecovery = undefined;
          this.emitError(recovery.manifest, error);
        } else {
          this.emit({
            kind: "uncertain",
            manifest: recovery.manifest,
            message: "Результат пока недоступен. Проверьте сеть и повторите проверку.",
            checking: false,
          });
        }
      } finally {
        this.busy = false;
      }
    });
  }

  private async acceptConfirmation(
    generation: number,
    manifest: WebManifest,
    confirmation: SessionConfirmation,
    signal: AbortSignal,
  ): Promise<void> {
    if (
      confirmation.trustedCredential !== undefined &&
      confirmation.trustedCredentialExpiresAtEpochMillis !== undefined
    ) {
      try {
        this.trustedCredentialStore.save({
          credential: confirmation.trustedCredential,
          expiresAtEpochMillis: confirmation.trustedCredentialExpiresAtEpochMillis,
        });
      } catch {
        this.trustedCredentialStore.clear();
      }
    }
    this.tokenStore.save(confirmation.token);
    this.activeToken = confirmation.token;
    const status = await this.api.status(confirmation.token, signal);
    if (!this.isCurrent(generation)) return;
    this.pairingRecovery = undefined;
    this.connect(manifest, confirmation.token, status);
  }

  private connect(manifest: WebManifest, token: string, status: SessionStatus): void {
    this.activeToken = token;
    this.activateTransfers(token, status);
    this.onEffect({
      id: `clear-pairing-form:${status.sessionId}`,
      kind: "clearPairingForm",
    });
    this.emit({ kind: "connected", manifest, status });
    const generation = this.generation;
    let recovering = false;
    this.events.connect(token, {
      onReconnecting: (attempt, delayMs) => {
        if (!this.isCurrent(generation)) return;
        recovering = true;
        this.setTransfersAvailable(false);
        this.emit({
          kind: "reconnecting",
          manifest,
          status,
          attempt,
          nextRetryInMs: delayMs,
        });
      },
      onAuthenticated: () => {
        if (!this.isCurrent(generation) || !recovering) return;
        recovering = false;
        void this.revalidateSessionAfterEventReconnect(generation, manifest, token, status);
      },
      onSessionLost: (reason) => {
        if (!this.isCurrent(generation)) return;
        this.setTransfersAvailable(false);
        if (reason === "reconnect_exhausted") {
          recovering = false;
          this.events.disconnect();
          this.waitForPhone();
          return;
        }
        void this.revalidateSessionAfterEventLoss(generation, manifest, token);
      },
      onTextReceived: (event) => {
        if (this.isCurrent(generation)) this.textSession.receive(event);
      },
      onTextSnapshot: (event) => {
        if (this.isCurrent(generation)) this.textSession.applySnapshot(event);
      },
      onTextError: (event) => {
        if (this.isCurrent(generation)) this.textSession.receiveError(event);
      },
      onFileOffer: (event) => {
        if (this.isCurrent(generation)) this.fileSession.receiveOffer(event);
      },
      onFileProgress: (event) => {
        if (this.isCurrent(generation)) this.fileSession.receiveProgress(event);
      },
      onFileSnapshot: (event) => {
        if (this.isCurrent(generation)) this.fileSession.applySnapshot(event);
      },
      onFileError: (event) => {
        if (this.isCurrent(generation)) this.fileSession.receiveError(event);
      },
    });
  }

  private async revalidateSessionAfterEventReconnect(
    generation: number,
    manifest: WebManifest,
    token: string,
    previousStatus: SessionStatus,
  ): Promise<void> {
    await this.withRequest(async (signal) => {
      try {
        const status = await this.api.status(token, signal);
        if (!this.isCurrent(generation)) return;
        this.activeToken = token;
        this.activateTransfers(token, status);
        this.emit({ kind: "connected", manifest, status });
      } catch (error: unknown) {
        if (!this.isCurrent(generation) || isAbortError(error)) return;
        if (isUnauthorized(error)) {
          this.loseSession(manifest);
          return;
        }
        this.emit({
          kind: "needsUserAction",
          manifest,
          status: previousStatus,
          message: "Соединение восстановлено, но проверить сессию не удалось. Повторите попытку.",
        });
      }
    });
  }

  private async revalidateSessionAfterEventLoss(
    generation: number,
    manifest: WebManifest,
    token: string,
  ): Promise<void> {
    await this.withRequest(async (signal) => {
      try {
        const status = await this.api.status(token, signal);
        if (!this.isCurrent(generation)) return;
        this.connect(manifest, token, status);
      } catch (error: unknown) {
        if (!this.isCurrent(generation) || isAbortError(error)) return;
        if (isUnauthorized(error)) {
          this.loseSession(manifest);
          return;
        }
        this.scheduleOfflineRetry(generation, 0);
      }
    });
  }

  /** Runs one request under the controller [cancelPending] aborts, and forgets it when done. */
  private async withRequest(run: (signal: AbortSignal) => Promise<void>): Promise<void> {
    const controller = new AbortController();
    this.abortController = controller;
    try {
      await run(controller.signal);
    } finally {
      if (this.abortController === controller) this.abortController = undefined;
    }
  }

  private activateTransfers(token: string, status: SessionStatus): void {
    this.textSession.activate(token, status.sessionId);
    this.fileSession.activate(token, status.effectiveFileLimitBytes);
    this.setTransfersAvailable(true);
  }

  private setTransfersAvailable(available: boolean): void {
    this.textSession.setConnectionAvailable(available);
    this.fileSession.setConnectionAvailable(available);
  }

  /** The phone no longer knows this session: keep the text draft and ask to pair again. */
  private loseSession(manifest: WebManifest): void {
    this.clearSession(true);
    this.emit({ kind: "sessionLost", manifest, message: SESSION_ENDED_MESSAGE });
  }

  private loseToIncompatibleVersion(manifest?: WebManifest): void {
    this.clearSession();
    this.emit({
      kind: "sessionLost",
      ...(manifest === undefined ? {} : { manifest }),
      message: INCOMPATIBLE_VERSION_MESSAGE,
    });
  }

  private emitError(manifest: WebManifest, error: unknown): void {
    if (!(error instanceof SessionApiError)) {
      this.waitForPhone();
      return;
    }
    switch (error.code) {
      case "RATE_LIMITED":
      case "CAPACITY_REACHED":
        this.emit({
          kind: "blocked",
          manifest,
          message: error.message,
          retryAfterSeconds: error.retryAfterSeconds,
        });
        break;
      case "DENIED":
        this.emit({ kind: "denied", manifest, message: error.message });
        break;
      case "EXPIRED":
        this.emit({ kind: "expired", manifest, message: error.message });
        break;
      case "UNAUTHORIZED":
      case "SESSION_CLOSED":
        this.clearSession(true);
        this.emit({ kind: "sessionLost", manifest, message: error.message });
        break;
      case "UNSUPPORTED_VERSION":
        this.clearSession();
        this.emit({ kind: "sessionLost", manifest, message: error.message });
        break;
      case "INVALID_CODE":
      case "INVALID_PAYLOAD":
        if (this.challenge === undefined) {
          this.emit({ kind: "expired", manifest, message: error.message });
        } else {
          this.challenge = {
            ...this.challenge,
            attemptsRemaining: error.attemptsRemaining ?? this.challenge.attemptsRemaining,
          };
          this.emit({ kind: "ready", manifest, challenge: this.challenge });
        }
        break;
    }
  }

  private scheduleOfflineRetry(generation: number, retryIndex: number): void {
    const nextRetryInMs = RETRY_DELAYS_MS[retryIndex];
    if (nextRetryInMs === undefined) {
      this.waitForPhone();
      return;
    }
    this.emit({ kind: "offline", message: OFFLINE_MESSAGE, nextRetryInMs });
    this.retryHandle = this.scheduler.setTimeout(() => {
      this.retryHandle = undefined;
      void this.boot(generation, retryIndex + 1);
    }, nextRetryInMs);
  }

  private clearSession(preserveTextDraft = false): void {
    this.activeToken = undefined;
    this.tokenStore.clear();
    this.events.disconnect();
    if (preserveTextDraft) {
      this.textSession.suspendSession();
    } else {
      this.textSession.deactivate();
    }
    this.fileSession.deactivate();
  }

  private cancelPending(): void {
    this.waiter.stop();
    this.abortController?.abort();
    this.abortController = undefined;
    if (this.retryHandle !== undefined) {
      this.scheduler.clearTimeout(this.retryHandle);
      this.retryHandle = undefined;
    }
  }

  private emit(state: SessionUiState): void {
    this.currentState = state;
    this.onStateChange(state);
  }

  private isCurrent(generation: number): boolean {
    return generation === this.generation;
  }
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof SessionApiError && error.code === "UNAUTHORIZED";
}

function isTrustedCredentialRejected(error: unknown): boolean {
  return error instanceof SessionApiError &&
    (error.code === "UNAUTHORIZED" || error.code === "EXPIRED");
}
