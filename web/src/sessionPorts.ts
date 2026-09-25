import type {
  SessionChallenge,
  SessionConfirmation,
  SessionConfirmationRecovery,
  SessionStatus,
} from "./sessionApiClient";
import type { WebManifest } from "./webManifestClient";
import {
  documentVisibilityPort,
  windowOnlinePort,
  type OnlinePort,
  type VisibilityPort,
} from "./availabilityWaiter";
import type {
  FileErrorEvent,
  FileOfferEvent,
  FileProgressEvent,
  FileSnapshotEvent,
} from "./fileApiClient";
import type {
  SessionEventCallbacks,
  TextErrorEvent,
  TextReceivedEvent,
  TextSnapshotEvent,
} from "./sessionEventSocketClient";

export interface ManifestLoader {
  load(signal?: AbortSignal): Promise<WebManifest>;
}

export interface SessionApi {
  createChallenge(
    clientLabel: string,
    rememberBrowserRequested?: boolean,
    signal?: AbortSignal,
  ): Promise<SessionChallenge>;
  confirm(
    challengeId: string,
    code: string,
    clientLabel: string,
    signal?: AbortSignal,
  ): Promise<SessionConfirmation>;
  recoverConfirmation(
    challengeId: string,
    clientLabel: string,
    signal?: AbortSignal,
  ): Promise<SessionConfirmationRecovery>;
  exchangeTrusted(
    trustedCredential: string,
    signal?: AbortSignal,
  ): Promise<SessionConfirmation>;
  status(token: string, signal?: AbortSignal): Promise<SessionStatus>;
  close(token: string, signal?: AbortSignal): Promise<void>;
}

export interface SessionTokenStore {
  read(): string | undefined;
  save(token: string): void;
  clear(): void;
}

export interface TrustedCredentialStore {
  read(): { readonly credential: string; readonly expiresAtEpochMillis: number } | undefined;
  save(value: { readonly credential: string; readonly expiresAtEpochMillis: number }): void;
  clear(): void;
}

export interface SessionEventChannel {
  connect(token: string, callbacks: SessionEventCallbacks): void;
  disconnect(): void;
}

export interface TextSessionLifecycle {
  activate(token: string, sessionScopeId: string): void;
  deactivate(): void;
  suspendSession(): void;
  dispose(): void;
  setConnectionAvailable(available: boolean): void;
  receive(event: TextReceivedEvent): void;
  applySnapshot(event: TextSnapshotEvent): void;
  receiveError(event: TextErrorEvent): void;
}

export interface FileSessionLifecycle {
  activate(token: string, effectiveFileLimitBytes: number): void;
  deactivate(): void;
  /** Ends the session but keeps the files chosen for sending for the next one. */
  suspendSession(): void;
  setConnectionAvailable(available: boolean): void;
  receiveOffer(event: FileOfferEvent): void;
  receiveProgress(event: FileProgressEvent): void;
  applySnapshot(event: FileSnapshotEvent): void;
  receiveError(event: FileErrorEvent): void;
}

export const noTextSession: TextSessionLifecycle = {
  activate: () => undefined,
  deactivate: () => undefined,
  suspendSession: () => undefined,
  dispose: () => undefined,
  setConnectionAvailable: () => undefined,
  receive: () => undefined,
  applySnapshot: () => undefined,
  receiveError: () => undefined,
};
export const noFileSession: FileSessionLifecycle = {
  activate: () => undefined,
  deactivate: () => undefined,
  suspendSession: () => undefined,
  setConnectionAvailable: () => undefined,
  receiveOffer: () => undefined,
  receiveProgress: () => undefined,
  applySnapshot: () => undefined,
  receiveError: () => undefined,
};
export const noTrustedCredentialStore: TrustedCredentialStore = {
  read: () => undefined,
  save: () => undefined,
  clear: () => undefined,
};

export interface WaitingPorts {
  readonly visibility: VisibilityPort;
  readonly online: OnlinePort;
}

export function defaultWaitingPorts(): WaitingPorts {
  if (typeof document === "undefined" || typeof window === "undefined") {
    return {
      visibility: { isVisible: () => true, onChange: () => () => undefined },
      online: { onOnline: () => () => undefined },
    };
  }
  return { visibility: documentVisibilityPort(document), online: windowOnlinePort(window) };
}
