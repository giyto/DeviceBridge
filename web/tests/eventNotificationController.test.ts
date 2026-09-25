import { describe, expect, it, vi } from "vitest";
import type { NotificationPreference } from "../src/browserNotificationPreferenceStore";
import {
  createBrowserNotificationPort,
  EventNotificationController,
  type EventNotificationPanelState,
  type NotificationPermissionState,
  type RevealTarget,
} from "../src/eventNotificationController";
import type { FileMetadata, FileSnapshotItem } from "../src/fileApiClient";
import type { TextFeedItem } from "../src/sessionEventSocketClient";

const TITLE = "DeviceBridge - локальная связь";

describe("EventNotificationController", () => {
  it("is unavailable on a plain HTTP page but still counts unread events in the title", () => {
    const fixture = setup({ notifications: false });
    fixture.hide();

    fixture.controller.textSnapshot([]);
    fixture.controller.textReceived(text("m1"));
    fixture.controller.filesOffered([file("f1")]);

    expect(fixture.panel.at(-1)).toEqual({ kind: "unavailable" });
    expect(fixture.title).toBe("(2) " + TITLE);
    expect(fixture.shown).toEqual([]);

    fixture.show();
    expect(fixture.title).toBe(TITLE);
  });

  it("asks for permission only when enabled and remembers the choice", async () => {
    const fixture = setup({ permission: "default" });
    expect(fixture.panel.at(-1)).toEqual({ kind: "off" });
    expect(fixture.requests).toBe(0);

    fixture.nextPermission = "granted";
    await fixture.controller.enable();

    expect(fixture.requests).toBe(1);
    expect(fixture.panel).toContainEqual({ kind: "requesting" });
    expect(fixture.panel.at(-1)).toEqual({ kind: "enabled", hideContent: false });
    expect(fixture.saved.at(-1)).toEqual({ enabled: true, hideContent: false });

    fixture.controller.disable();
    expect(fixture.panel.at(-1)).toEqual({ kind: "off" });
    expect(fixture.saved.at(-1)).toEqual({ enabled: false, hideContent: false });
  });

  it("reports a refused permission honestly", async () => {
    const fixture = setup({ permission: "default" });
    fixture.nextPermission = "denied";

    await fixture.controller.enable();

    expect(fixture.panel.at(-1)).toEqual({ kind: "denied" });
    expect(fixture.saved).toEqual([]);
  });

  it("shows a text only while the tab is not looked at", () => {
    const fixture = setup({ enabled: true });
    fixture.controller.textSnapshot([]);

    fixture.controller.textReceived(text("m1"));
    expect(fixture.shown).toEqual([]);

    fixture.hide();
    fixture.controller.textReceived(text("m2", "Привет <b>мир</b>"));
    expect(fixture.shown).toEqual([
      { title: "Текст с телефона", body: "Привет <b>мир</b>", tag: "devicebridge-text" },
    ]);
  });

  it("ignores texts sent from this browser and texts it already knows", () => {
    const fixture = setup({ enabled: true });
    fixture.controller.textSnapshot([]);
    fixture.hide();

    fixture.controller.textReceived({ ...text("m1"), direction: "BROWSER_TO_ANDROID" });
    fixture.controller.textReceived(text("m2"));
    fixture.controller.textReceived(text("m2"));

    expect(fixture.shown).toHaveLength(1);
  });

  it("treats the first snapshot as history and a later one as news", () => {
    const fixture = setup({ enabled: true });
    fixture.hide();

    fixture.controller.textSnapshot([text("old")]);
    expect(fixture.shown).toEqual([]);

    // After a reconnection the snapshot brings what arrived meanwhile, and the old items again.
    fixture.controller.textSnapshot([text("old"), text("new")]);
    expect(fixture.shown.map((it) => it.body)).toEqual(["Текст new"]);
  });

  it("merges several texts into one notification and resets on return", () => {
    const fixture = setup({ enabled: true });
    fixture.controller.textSnapshot([]);
    fixture.hide();

    fixture.controller.textReceived(text("m1"));
    fixture.controller.textReceived({ ...text("m2", "https://example.com"), contentKind: "LINK" });

    expect(fixture.shown.at(-1)).toEqual({
      title: "Ссылка с телефона",
      body: "https://example.com\nИ ещё 1",
      tag: "devicebridge-text",
    });
    expect(fixture.closed).toBe(1);

    fixture.show();
    expect(fixture.closed).toBe(2);
    fixture.hide();
    fixture.controller.textReceived(text("m3"));
    expect(fixture.shown.at(-1)?.body).toBe("Текст m3");
    expect(fixture.title).toBe("(1) " + TITLE);
  });

  it("shortens long texts and can hide the content", () => {
    const fixture = setup({ enabled: true });
    fixture.controller.textSnapshot([]);
    fixture.hide();

    fixture.controller.textReceived(text("m1", "\u202e" + "а".repeat(300)));
    expect(fixture.shown.at(-1)?.body).toHaveLength(121);
    expect(fixture.shown.at(-1)?.body.endsWith("…")).toBe(true);

    fixture.controller.setHideContent(true);
    fixture.controller.textReceived(text("m2", "секрет"));
    expect(fixture.shown.at(-1)?.body).not.toContain("секрет");
    expect(fixture.panel.at(-1)).toEqual({ kind: "enabled", hideContent: true });
  });

  it("offers files as one notification with a safe name and the total size", () => {
    const fixture = setup({ enabled: true });
    fixture.controller.fileSnapshot([]);
    fixture.hide();

    fixture.controller.filesOffered([
      file("f1", "C:\\secret\\report\u202e.pdf", 1024 * 1024),
      file("f2", "b.txt", 512 * 1024),
      file("f3", "c.txt", 512 * 1024),
    ]);

    const size = (2).toFixed(1) + " МБ";
    expect(fixture.shown).toEqual([
      { title: "Файлы с телефона", body: "report.pdf и ещё 2 - " + size, tag: "devicebridge-files" },
    ]);

    fixture.controller.setHideContent(true);
    fixture.controller.filesOffered([file("f4", "d.txt", 0)]);
    expect(fixture.shown.at(-1)?.body).toBe("4 файла - " + size);
  });

  it("offers only files it has not seen in later snapshots", () => {
    const fixture = setup({ enabled: true });
    fixture.hide();
    fixture.controller.fileSnapshot([snapshot("old", "CONNECTING")]);

    fixture.controller.fileSnapshot([
      snapshot("old", "CONNECTING"),
      snapshot("new", "CONNECTING"),
      snapshot("done", "COMPLETED"),
    ]);

    expect(fixture.shown.map((it) => it.body)).toEqual(["new.txt - 10 Б"]);
  });

  it("reports finished uploads to the phone, counting failures", () => {
    const fixture = setup({ enabled: true });
    fixture.hide();

    fixture.controller.uploadsChanged([upload("u1", "TRANSFERRING"), upload("u2", "QUEUED"), upload("u3", "COMPLETED")]);
    expect(fixture.shown).toEqual([]);

    fixture.controller.uploadsChanged([upload("u1", "COMPLETED"), upload("u2", "TRANSFERRING")]);
    expect(fixture.shown.at(-1)).toEqual({
      title: "Файлы переданы на телефон",
      body: "Передано 1 файл",
      tag: "devicebridge-upload",
    });

    fixture.controller.uploadsChanged([upload("u1", "COMPLETED"), upload("u2", "FAILED")]);
    expect(fixture.shown.at(-1)).toEqual({
      title: "Передача на телефон прервалась",
      body: "Передано 1, не удалось 1",
      tag: "devicebridge-upload",
    });
  });

  it("tells about a lost connection once, not about a reconnection", () => {
    const fixture = setup({ enabled: true });
    fixture.hide();

    for (const kind of ["checking", "connected", "reconnecting", "connected"]) {
      fixture.controller.sessionChanged(kind);
    }
    expect(fixture.shown).toEqual([]);

    fixture.controller.sessionChanged("reconnecting");
    fixture.controller.sessionChanged("needsUserAction");
    fixture.controller.sessionChanged("offline");
    expect(fixture.shown.map((it) => it.title)).toEqual(["Связь с телефоном потеряна"]);
  });

  it("waiting for the phone counts as a lost connection", () => {
    const fixture = setup({ enabled: true });
    fixture.hide();

    fixture.controller.sessionChanged("connected");
    fixture.controller.sessionChanged("reconnecting");
    fixture.controller.sessionChanged("waiting");

    expect(fixture.shown.map((it) => it.title)).toEqual(["Связь с телефоном потеряна"]);
  });

  it("a click returns to the tab and shows the event", () => {
    const fixture = setup({ enabled: true });
    fixture.controller.textSnapshot([]);
    fixture.hide();
    fixture.controller.textReceived(text("m1"));

    fixture.click();

    expect(fixture.revealed).toEqual([{ kind: "text", messageId: "m1" }]);
    expect(fixture.title).toBe(TITLE);
  });

  it("shows nothing while turned off but keeps counting", () => {
    const fixture = setup({ permission: "granted" });
    fixture.controller.textSnapshot([]);
    fixture.hide();

    fixture.controller.textReceived(text("m1"));

    expect(fixture.shown).toEqual([]);
    expect(fixture.title).toBe("(1) " + TITLE);
  });
});

describe("createBrowserNotificationPort", () => {
  const documentRef = {
    querySelector: () => ({ href: "/assets/favicon-test.svg" }),
  } as unknown as Document;

  it("is absent on HTTP and where the browser has no notifications", () => {
    expect(createBrowserNotificationPort({ protocol: "http:" }, documentRef, scopeWith(FakeNotification))).toBeNull();
    expect(createBrowserNotificationPort({ protocol: "https:" }, documentRef, scopeWith(undefined))).toBeNull();
  });

  it("shows a same-origin icon and returns to the tab on click", () => {
    const focus = vi.fn();
    const port = createBrowserNotificationPort(
      { protocol: "https:" },
      documentRef,
      { ...scopeWith(FakeNotification), focus } as unknown as typeof globalThis,
    )!;
    const onClick = vi.fn();

    port.show("Текст с телефона", { body: "Привет", tag: "devicebridge-text" }, onClick);
    const created = FakeNotification.created.at(-1)!;
    created.onclick?.();

    expect(created.options).toEqual({ body: "Привет", tag: "devicebridge-text", icon: "/assets/favicon-test.svg" });
    expect(focus).toHaveBeenCalledOnce();
    expect(created.closed).toBe(true);
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("gives up quietly where only a service worker may notify", () => {
    class Throwing {
      static permission = "granted";
      constructor() {
        throw new TypeError("Illegal constructor");
      }
    }
    const port = createBrowserNotificationPort({ protocol: "https:" }, documentRef, scopeWith(Throwing))!;

    expect(port.show("x", { body: "y", tag: "z" }, () => undefined)).toBeNull();
  });
});

class FakeNotification {
  static permission = "granted";
  static created: FakeNotification[] = [];
  static requestPermission = async () => "granted";
  onclick: (() => void) | null = null;
  closed = false;

  constructor(readonly title: string, readonly options: NotificationOptions) {
    FakeNotification.created.push(this);
  }

  close(): void {
    this.closed = true;
  }
}

function scopeWith(notification: unknown): typeof globalThis {
  return { Notification: notification, focus: () => undefined } as unknown as typeof globalThis;
}

interface Shown {
  readonly title: string;
  readonly body: string;
  readonly tag: string;
}

function setup(options: {
  readonly notifications?: boolean;
  readonly permission?: NotificationPermissionState;
  readonly enabled?: boolean;
}) {
  const state = {
    elsewhere: false,
    listeners: [] as (() => void)[],
    permission: options.permission ?? (options.enabled ? "granted" : "default") as NotificationPermissionState,
    nextPermission: "default" as NotificationPermissionState,
    requests: 0,
    shown: [] as Shown[],
    clicks: [] as (() => void)[],
    closed: 0,
    title: TITLE,
    saved: [] as NotificationPreference[],
    panel: [] as EventNotificationPanelState[],
    revealed: [] as RevealTarget[],
  };
  const controller = new EventNotificationController({
    notifications: options.notifications === false
      ? null
      : {
          permission: () => state.permission,
          requestPermission: async () => {
            state.requests += 1;
            state.permission = state.nextPermission;
            return state.permission;
          },
          show: (title, { body, tag }, onClick) => {
            state.shown.push({ title, body, tag });
            state.clicks.push(onClick);
            return { close: () => { state.closed += 1; } };
          },
        },
    attention: {
      isElsewhere: () => state.elsewhere,
      subscribe: (listener) => {
        state.listeners.push(listener);
        return () => undefined;
      },
    },
    title: { get: () => state.title, set: (title) => { state.title = title; } },
    preferences: {
      read: () => ({ enabled: options.enabled ?? false, hideContent: false }),
      save: (preference) => {
        state.saved.push(preference);
        return true;
      },
    },
    reveal: (target) => state.revealed.push(target),
    render: (panel) => state.panel.push(panel),
  });
  controller.start();
  return Object.assign(state, {
    controller,
    hide: () => { state.elsewhere = true; },
    show: () => {
      state.elsewhere = false;
      state.listeners.forEach((listener) => listener());
    },
    click: () => state.clicks.at(-1)?.(),
  });
}

function text(messageId: string, content = "Текст " + messageId): TextFeedItem {
  return {
    messageId,
    timestamp: 1,
    content,
    contentKind: "TEXT",
    direction: "ANDROID_TO_BROWSER",
    senderLabel: "Телефон",
    status: "DELIVERED",
  };
}

function file(transferId: string, displayName = transferId + ".txt", sizeBytes = 10): FileMetadata {
  return {
    transferId,
    displayName,
    sizeBytes,
    mimeType: "text/plain",
    sha256: "a".repeat(64),
    direction: "ANDROID_TO_BROWSER",
  };
}

function snapshot(transferId: string, status: FileSnapshotItem["status"]): FileSnapshotItem {
  return { metadata: file(transferId), status, bytesTransferred: 0, speedBytesPerSecond: 0 };
}

function upload(transferId: string, status: FileSnapshotItem["status"]) {
  return { transferId, direction: "BROWSER_TO_ANDROID" as const, status };
}
