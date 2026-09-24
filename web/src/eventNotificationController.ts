import type { NotificationPreference } from "./browserNotificationPreferenceStore";
import type { FileMetadata, FileSnapshotItem, FileTransferStatus } from "./fileApiClient";
import { formatBytes } from "./fileTransferView";
import type { TextFeedItem } from "./sessionEventSocketClient";

export type NotificationPermissionState = "default" | "granted" | "denied";

export interface NotificationHandle {
  close(): void;
}

/** The browser's system notifications; absent where the page may not use them. */
export interface NotificationPort {
  permission(): NotificationPermissionState;
  requestPermission(): Promise<NotificationPermissionState>;
  show(
    title: string,
    options: { readonly body: string; readonly tag: string },
    onClick: () => void,
  ): NotificationHandle | null;
}

/** Whether the person is looking somewhere else than this tab. */
export interface AttentionPort {
  isElsewhere(): boolean;
  /** Calls [listener] whenever the tab may have become visible and focused again. */
  subscribe(listener: () => void): () => void;
}

export interface TitlePort {
  get(): string;
  set(title: string): void;
}

export interface NotificationPreferencePort {
  read(): NotificationPreference;
  save(preference: NotificationPreference): boolean;
}

export type RevealTarget =
  | { readonly kind: "text"; readonly messageId: string }
  | { readonly kind: "file"; readonly transferId: string }
  | { readonly kind: "files" }
  | { readonly kind: "session" };

export type EventNotificationPanelState =
  | { readonly kind: "unavailable" }
  | { readonly kind: "off" }
  | { readonly kind: "requesting" }
  | { readonly kind: "enabled"; readonly hideContent: boolean }
  | { readonly kind: "denied" };

export interface UploadStatusItem {
  readonly transferId: string;
  readonly direction: FileMetadata["direction"];
  readonly status: FileTransferStatus;
}

export interface EventNotificationControllerDependencies {
  /** Null on a plain HTTP page: the browser does not allow notifications there. */
  readonly notifications: NotificationPort | null;
  readonly attention: AttentionPort;
  readonly title: TitlePort;
  readonly preferences: NotificationPreferencePort;
  readonly reveal: (target: RevealTarget) => void;
  readonly render: (state: EventNotificationPanelState) => void;
}

const MAX_REMEMBERED_IDS = 500;
const TEXT_PREVIEW_LENGTH = 120;
const FILE_NAME_LENGTH = 64;
const CONNECTED_KINDS = new Set(["connected", "reconnecting"]);
const LOST_KINDS = new Set(["needsUserAction", "sessionLost", "offline"]);
const HIDDEN_BODY = "Откройте DeviceBridge, чтобы посмотреть.";
const BIDI_CONTROLS = /[؜‎‏‪-‮⁦-⁩]/g;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/g;

const TAG_TEXT = "devicebridge-text";
const TAG_FILES = "devicebridge-files";
const TAG_UPLOAD = "devicebridge-upload";
const TAG_CONNECTION = "devicebridge-connection";

/**
 * Tells the person about phone events while they look elsewhere: a counter in the tab title
 * always, and system notifications when the page is on HTTPS and they turned them on.
 * It knows nothing about the session, text or file controllers; main.ts feeds it their events.
 */
export class EventNotificationController {
  private readonly originalTitle: string;
  private preference: NotificationPreference;
  private requesting = false;
  private unsubscribe: (() => void) | null = null;
  private readonly handles = new Map<string, NotificationHandle>();

  private readonly seenTexts = new Set<string>();
  private readonly seenOffers = new Set<string>();
  private textBaselined = false;
  private filesBaselined = false;
  private readonly uploadStatuses = new Map<string, FileTransferStatus>();
  private sessionKind: string | null = null;

  // What happened since the person last looked at the tab.
  private unread = 0;
  private pendingTexts: TextFeedItem[] = [];
  private pendingOffers: FileMetadata[] = [];
  private uploadsCompleted = 0;
  private uploadsFailed = 0;

  constructor(private readonly dependencies: EventNotificationControllerDependencies) {
    this.originalTitle = dependencies.title.get();
    this.preference = dependencies.preferences.read();
  }

  start(): void {
    this.unsubscribe = this.dependencies.attention.subscribe(() => {
      if (!this.dependencies.attention.isElsewhere()) this.markSeen();
    });
    this.renderPanel();
  }

  /** Must be called from the button's click: browsers ask for permission only then. */
  async enable(): Promise<void> {
    const notifications = this.dependencies.notifications;
    if (notifications === null || this.requesting) return;
    let permission = notifications.permission();
    if (permission === "default") {
      this.requesting = true;
      this.renderPanel();
      try {
        permission = await notifications.requestPermission();
      } catch {
        permission = notifications.permission();
      } finally {
        this.requesting = false;
      }
    }
    if (permission === "granted") this.savePreference({ ...this.preference, enabled: true });
    this.renderPanel();
  }

  disable(): void {
    this.savePreference({ ...this.preference, enabled: false });
    this.closeAll();
    this.renderPanel();
  }

  setHideContent(hideContent: boolean): void {
    this.savePreference({ ...this.preference, hideContent });
    this.renderPanel();
  }

  /** A text the phone sent while the page was connected. */
  textReceived(item: TextFeedItem): void {
    if (item.direction !== "ANDROID_TO_BROWSER") return;
    if (!remember(this.seenTexts, item.messageId)) return;
    this.announceText(item);
  }

  /** The whole feed, sent on every (re)connection; only the first one is history. */
  textSnapshot(items: readonly TextFeedItem[]): void {
    const fresh = items.filter(
      (item) => item.direction === "ANDROID_TO_BROWSER" && remember(this.seenTexts, item.messageId),
    );
    if (!this.textBaselined) {
      this.textBaselined = true;
      return;
    }
    fresh.forEach((item) => this.announceText(item));
  }

  filesOffered(items: readonly FileMetadata[]): void {
    const fresh = items.filter(
      (item) => item.direction === "ANDROID_TO_BROWSER" && remember(this.seenOffers, item.transferId),
    );
    if (fresh.length > 0) this.announceOffer(fresh);
  }

  fileSnapshot(items: readonly FileSnapshotItem[]): void {
    const fresh = items
      .filter((item) => item.metadata.direction === "ANDROID_TO_BROWSER")
      .filter((item) => remember(this.seenOffers, item.metadata.transferId))
      .filter((item) => item.status === "CONNECTING")
      .map((item) => item.metadata);
    if (!this.filesBaselined) {
      this.filesBaselined = true;
      return;
    }
    if (fresh.length > 0) this.announceOffer(fresh);
  }

  /** The current file list; an upload to the phone that just finished is reported. */
  uploadsChanged(items: readonly UploadStatusItem[]): void {
    let completed = 0;
    let failed = 0;
    for (const item of items) {
      if (item.direction !== "BROWSER_TO_ANDROID") continue;
      const previous = this.uploadStatuses.get(item.transferId);
      this.uploadStatuses.set(item.transferId, item.status);
      if (previous === undefined || isTerminal(previous)) continue;
      if (item.status === "COMPLETED") completed += 1;
      if (item.status === "FAILED") failed += 1;
    }
    if (completed + failed > 0) this.announceUploads(completed, failed);
  }

  /** Only a loss the page could not recover from by itself is worth a notification. */
  sessionChanged(kind: string): void {
    const previous = this.sessionKind;
    this.sessionKind = kind;
    if (previous === null || !CONNECTED_KINDS.has(previous) || !LOST_KINDS.has(kind)) return;
    if (!this.countUnread()) return;
    this.show(
      TAG_CONNECTION,
      "Связь с телефоном потеряна",
      kind === "sessionLost"
        ? "Сессия завершена на телефоне. Подключитесь снова."
        : "Проверьте, что сервер DeviceBridge на телефоне запущен.",
      { kind: "session" },
    );
  }

  dispose(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.closeAll();
    this.dependencies.title.set(this.originalTitle);
  }

  private announceText(item: TextFeedItem): void {
    if (!this.countUnread()) return;
    this.pendingTexts.push(item);
    const more = this.pendingTexts.length - 1;
    const body = this.preference.hideContent ? HIDDEN_BODY : preview(item.content);
    this.show(
      TAG_TEXT,
      item.contentKind === "LINK" ? "Ссылка с телефона" : "Текст с телефона",
      body + (more > 0 ? "\nИ ещё " + more : ""),
      { kind: "text", messageId: item.messageId },
    );
  }

  private announceOffer(items: readonly FileMetadata[]): void {
    if (!this.countUnread()) return;
    this.pendingOffers.push(...items);
    const all = this.pendingOffers;
    const first = all[0];
    if (first === undefined) return;
    const size = formatBytes(all.reduce((sum, item) => sum + item.sizeBytes, 0));
    const body = this.preference.hideContent
      ? countOfFiles(all.length) + " - " + size
      : safeFileName(first.displayName) + (all.length > 1 ? " и ещё " + (all.length - 1) : "") +
        " - " + size;
    this.show(TAG_FILES, "Файлы с телефона", body, { kind: "file", transferId: first.transferId });
  }

  private announceUploads(completed: number, failed: number): void {
    if (!this.countUnread()) return;
    this.uploadsCompleted += completed;
    this.uploadsFailed += failed;
    const done = this.uploadsCompleted;
    const lost = this.uploadsFailed;
    this.show(
      TAG_UPLOAD,
      lost > 0 ? "Передача на телефон прервалась" : "Файлы переданы на телефон",
      lost > 0 ? "Передано " + done + ", не удалось " + lost : "Передано " + countOfFiles(done),
      { kind: "files" },
    );
  }

  /** Counts an event the person has not seen; false when they are looking at the tab. */
  private countUnread(): boolean {
    if (!this.dependencies.attention.isElsewhere()) return false;
    this.unread += 1;
    this.dependencies.title.set("(" + this.unread + ") " + this.originalTitle);
    return true;
  }

  private show(tag: string, title: string, body: string, target: RevealTarget): void {
    const notifications = this.dependencies.notifications;
    if (notifications === null || !this.preference.enabled) return;
    if (notifications.permission() !== "granted") return;
    this.handles.get(tag)?.close();
    const handle = notifications.show(title, { body, tag }, () => {
      this.markSeen();
      this.dependencies.reveal(target);
    });
    if (handle === null) {
      this.handles.delete(tag);
    } else {
      this.handles.set(tag, handle);
    }
  }

  private markSeen(): void {
    this.unread = 0;
    this.pendingTexts = [];
    this.pendingOffers = [];
    this.uploadsCompleted = 0;
    this.uploadsFailed = 0;
    this.closeAll();
    this.dependencies.title.set(this.originalTitle);
  }

  private closeAll(): void {
    this.handles.forEach((handle) => handle.close());
    this.handles.clear();
  }

  private savePreference(preference: NotificationPreference): void {
    this.preference = preference;
    this.dependencies.preferences.save(preference);
  }

  private renderPanel(): void {
    this.dependencies.render(this.panelState());
  }

  private panelState(): EventNotificationPanelState {
    const notifications = this.dependencies.notifications;
    if (notifications === null) return { kind: "unavailable" };
    if (this.requesting) return { kind: "requesting" };
    const permission = notifications.permission();
    if (permission === "denied") return { kind: "denied" };
    if (permission === "granted" && this.preference.enabled) {
      return { kind: "enabled", hideContent: this.preference.hideContent };
    }
    return { kind: "off" };
  }
}

/** Adds [id]; false when it was already known. Keeps only the newest ids. */
function remember(ids: Set<string>, id: string): boolean {
  if (ids.has(id)) return false;
  ids.add(id);
  if (ids.size > MAX_REMEMBERED_IDS) {
    const oldest = ids.values().next().value;
    if (oldest !== undefined) ids.delete(oldest);
  }
  return true;
}

function isTerminal(status: FileTransferStatus): boolean {
  return status === "COMPLETED" || status === "FAILED" || status === "CANCELLED";
}

function preview(content: string): string {
  const clean = content
    .replace(BIDI_CONTROLS, "")
    .replace(CONTROL_CHARACTERS, " ")
    .replace(/\s+/g, " ")
    .trim();
  return clean.length > TEXT_PREVIEW_LENGTH
    ? clean.slice(0, TEXT_PREVIEW_LENGTH).trimEnd() + "…"
    : clean;
}

function safeFileName(name: string): string {
  const leaf = name.split(/[\\/]/).pop() ?? "";
  const clean = leaf.replace(BIDI_CONTROLS, "").replace(CONTROL_CHARACTERS, "").trim();
  return clean.slice(0, FILE_NAME_LENGTH) || "Файл";
}

function countOfFiles(count: number): string {
  const lastTwo = count % 100;
  const last = count % 10;
  const word = lastTwo >= 11 && lastTwo <= 14
    ? "файлов"
    : last === 1
      ? "файл"
      : last >= 2 && last <= 4
        ? "файла"
        : "файлов";
  return count + " " + word;
}

/** System notifications of this browser, or null where DeviceBridge may not use them. */
export function createBrowserNotificationPort(
  location: Pick<Location, "protocol">,
  documentRef: Document,
  scope: typeof globalThis = globalThis,
): NotificationPort | null {
  // A plain http:// address is not a secure context for the browser; 127.0.0.1 during
  // development would be, so the protocol decides, as it does for the product.
  if (location.protocol !== "https:" || typeof scope.Notification !== "function") return null;
  const NotificationClass = scope.Notification;
  return {
    permission: () => NotificationClass.permission,
    requestPermission: () => NotificationClass.requestPermission(),
    show: (title, options, onClick) => {
      try {
        const icon = documentRef.querySelector<HTMLLinkElement>('link[rel="icon"]')?.href;
        const notification = new NotificationClass(title, { ...options, icon });
        notification.onclick = () => {
          scope.focus();
          notification.close();
          onClick();
        };
        return notification;
      } catch {
        // Some browsers, like Chrome on Android, allow notifications only from a service worker.
        return null;
      }
    },
  };
}

export function createDocumentAttentionPort(documentRef: Document, windowRef: Window): AttentionPort {
  return {
    isElsewhere: () => documentRef.visibilityState === "hidden" || !documentRef.hasFocus(),
    subscribe: (listener) => {
      documentRef.addEventListener("visibilitychange", listener);
      windowRef.addEventListener("focus", listener);
      return () => {
        documentRef.removeEventListener("visibilitychange", listener);
        windowRef.removeEventListener("focus", listener);
      };
    },
  };
}

export function createDocumentTitlePort(documentRef: Document): TitlePort {
  return {
    get: () => documentRef.title,
    set: (title) => {
      documentRef.title = title;
    },
  };
}

/** Scrolls to the card an event is about and gives it focus. */
export function createDocumentReveal(documentRef: Document): (target: RevealTarget) => void {
  return (target) => {
    const element = findTarget(documentRef, target);
    if (element === null) return;
    element.scrollIntoView({ block: "center" });
    if (!element.hasAttribute("tabindex")) element.setAttribute("tabindex", "-1");
    element.focus({ preventScroll: true });
  };
}

function findTarget(documentRef: Document, target: RevealTarget): HTMLElement | null {
  switch (target.kind) {
    case "text":
      return [...documentRef.querySelectorAll<HTMLElement>("[data-message-id]")]
        .find((element) => element.dataset.messageId === target.messageId) ?? null;
    case "file":
      return [...documentRef.querySelectorAll<HTMLElement>("[data-transfer-id]")]
        .find((element) => element.dataset.transferId === target.transferId) ?? null;
    case "files":
      return documentRef.querySelector<HTMLElement>('[data-role="file-transfer-list"]');
    case "session":
      return documentRef.querySelector<HTMLElement>('[data-role="session-panel"]');
  }
}
