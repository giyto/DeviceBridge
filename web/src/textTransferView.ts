import type {
  TextTransferUiItem,
  TextTransferUiState,
} from "./textTransferController";
import {
  textItemPresentationState,
  textPresentationState,
} from "./uiPresentationState";
import {
  BrowserClipboardWriter,
  type ClipboardWriter,
} from "./clipboardWriter";
import {
  BrowserLinkOpener,
  canonicalHttpUrl,
  type LinkOpener,
} from "./linkOpener";

export interface TextTransferActions {
  readonly onDraftChange: (draft: string) => void;
  readonly onSend: () => void;
  readonly onRetry: (messageId: string) => void;
}

export interface TextTransferView {
  render(state: TextTransferUiState): void;
  dispose(): void;
}

export function createTextTransferView(
  documentRef: Document,
  actions: TextTransferActions,
  clipboard: ClipboardWriter = new BrowserClipboardWriter(),
  linkOpener: LinkOpener = new BrowserLinkOpener(),
): TextTransferView {
  const section = requiredElement<HTMLElement>(
    documentRef,
    '[data-role="text-transfer"]',
  );
  const form = requiredElement<HTMLFormElement>(documentRef, '[data-role="text-form"]');
  const draft = requiredElement<HTMLTextAreaElement>(documentRef, "#text-draft");
  const send = requiredElement<HTMLButtonElement>(documentRef, '[data-action="send-text"]');
  const error = requiredElement<HTMLElement>(documentRef, '[data-role="text-error"]');
  const feed = requiredElement<HTMLOListElement>(documentRef, '[data-role="text-feed"]');
  const empty = requiredElement<HTMLElement>(documentRef, '[data-role="text-feed-empty"]');
  const count = requiredElement<HTMLElement>(documentRef, '[data-role="text-feed-count"]');
  const announcer = requiredElement<HTMLElement>(documentRef, '[data-role="text-announcer"]');
  const itemCards = new Map<string, HTMLLIElement>();
  const itemFingerprints = new Map<string, string>();
  const knownStatuses = new Map<string, TextTransferUiItem["status"]>();
  let pendingRetryFocus: string | undefined;
  const focusAwareActions: TextTransferActions = {
    ...actions,
    onRetry: (messageId) => {
      pendingRetryFocus = messageId;
      actions.onRetry(messageId);
    },
  };

  const onInput = (): void => actions.onDraftChange(draft.value);
  const onSubmit = (event: SubmitEvent): void => {
    event.preventDefault();
    if (draft.value.trim().length === 0 || send.disabled) return;
    actions.onSend();
  };
  draft.addEventListener("input", onInput);
  form.addEventListener("submit", onSubmit);

  const render = (state: TextTransferUiState): void => {
    section.dataset.viewState = textPresentationState(state);
    if (state.kind === "inactive") {
      section.hidden = true;
      draft.value = "";
      draft.disabled = true;
      send.disabled = true;
      error.hidden = true;
      error.textContent = "";
      feed.replaceChildren();
      itemCards.clear();
      itemFingerprints.clear();
      knownStatuses.clear();
      pendingRetryFocus = undefined;
      announcer.textContent = "";
      count.textContent = "0";
      empty.hidden = false;
      return;
    }

    section.hidden = false;
    draft.disabled = state.sending;
    if (draft.value !== state.draft) draft.value = state.draft;
    send.disabled = !state.connectionAvailable || state.sending || state.draft.trim().length === 0;
    send.textContent = state.sending ? "Отправляем…" : "Отправить на телефон";
    error.hidden = state.error === undefined;
    error.textContent = state.error?.message ?? "";
    count.textContent = String(state.items.length);
    empty.hidden = state.items.length > 0;
    reconcileItems(
      documentRef,
      feed,
      state.items,
      focusAwareActions,
      clipboard,
      linkOpener,
      state.connectionAvailable,
      itemCards,
      itemFingerprints,
    );
    if (pendingRetryFocus !== undefined) {
      const pendingItem = state.items.find((item) => item.messageId === pendingRetryFocus);
      const card = itemCards.get(pendingRetryFocus);
      const target = card?.querySelector<HTMLButtonElement>(
        '.text-card__retry:not(:disabled), .text-card__copy:not(:disabled), .text-card__open:not(:disabled)',
      );
      if (target !== null && target !== undefined) {
        target.focus();
        queueMicrotask(() => {
          if (target.isConnected && !target.disabled) target.focus();
        });
        if (pendingItem?.status !== "SENDING") pendingRetryFocus = undefined;
      }
    }
    announceTextChanges(state.items, knownStatuses, announcer);
  };

  render({ kind: "inactive" });

  return {
    render,
    dispose: () => {
      draft.removeEventListener("input", onInput);
      form.removeEventListener("submit", onSubmit);
    },
  };
}

function createItem(
  documentRef: Document,
  item: TextTransferUiItem,
  actions: TextTransferActions,
  clipboard: ClipboardWriter,
  linkOpener: LinkOpener,
  connectionAvailable: boolean,
): HTMLLIElement {
  const card = documentRef.createElement("li");
  card.className = "text-card";
  card.dataset.messageId = item.messageId;
  card.dataset.direction = item.direction;
  card.dataset.status = item.status;
  card.dataset.viewState = textItemPresentationState(item.status);

  const metadata = documentRef.createElement("div");
  metadata.className = "text-card__metadata";

  const sender = documentRef.createElement("strong");
  sender.textContent = item.senderLabel;
  const direction = documentRef.createElement("span");
  direction.textContent = item.direction === "ANDROID_TO_BROWSER"
    ? "С телефона"
    : "На телефон";
  const timestamp = documentRef.createElement("time");
  const date = new Date(item.timestamp);
  timestamp.dateTime = date.toISOString();
  timestamp.textContent = new Intl.DateTimeFormat("ru", {
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
  metadata.append(sender, direction, timestamp);

  const content = documentRef.createElement("p");
  content.className = "text-card__content";
  content.textContent = item.content;

  const footer = documentRef.createElement("div");
  footer.className = "text-card__footer";
  const summary = documentRef.createElement("div");
  summary.className = "text-card__summary";
  const actionRow = documentRef.createElement("div");
  actionRow.className = "text-card__actions";
  actionRow.setAttribute("role", "group");
  actionRow.setAttribute("aria-label", "Действия с сообщением");
  const kind = documentRef.createElement("span");
  kind.textContent = item.contentKind === "LINK" ? "Ссылка" : "Текст";
  const status = documentRef.createElement("span");
  status.className = "text-card__status";
  status.textContent = statusLabel(item.status);
  summary.append(kind, status);
  footer.append(summary, actionRow);

  if (canonicalHttpUrl(item.content, item.contentKind) !== undefined) {
    const open = documentRef.createElement("button");
    open.type = "button";
    open.className = "text-card__open";
    open.dataset.openMessageId = item.messageId;
    open.textContent = "Открыть ссылку";
    open.addEventListener("click", () => {
      linkOpener.open(item.content, item.contentKind);
    });
    actionRow.append(open);
  }

  const copy = documentRef.createElement("button");
  copy.type = "button";
  copy.className = "text-card__copy";
  copy.dataset.copyMessageId = item.messageId;
  copy.textContent = "Копировать";
  actionRow.append(copy);


  const copyStatus = documentRef.createElement("span");
  copyStatus.className = "text-card__copy-status";
  copyStatus.dataset.role = "copy-status";
  copyStatus.setAttribute("role", "status");
  copyStatus.setAttribute("aria-live", "polite");

  const manualCopy = documentRef.createElement("div");
  manualCopy.className = "manual-copy";
  manualCopy.dataset.role = "manual-copy";
  manualCopy.hidden = true;
  const manualLabel = documentRef.createElement("label");
  const manualId = `manual-copy-${item.messageId}`;
  manualLabel.htmlFor = manualId;
  manualLabel.textContent = "Автокопирование недоступно - выделите текст вручную";
  const manualField = documentRef.createElement("textarea");
  manualField.id = manualId;
  manualField.readOnly = true;
  manualField.rows = 3;
  manualField.value = item.content;
  manualCopy.append(manualLabel, manualField);

  copy.addEventListener("click", () => {
    copy.disabled = true;
    copyStatus.textContent = "Копируем…";
    void clipboard.write(item.content).then((copied) => {
      copy.disabled = false;
      if (copied) {
        copyStatus.textContent = "Скопировано";
        manualCopy.hidden = true;
        return;
      }
      copyStatus.textContent = "Скопируйте текст вручную";
      manualCopy.hidden = false;
      manualField.focus();
      manualField.select();
    });
  });

  card.append(metadata, content, footer, copyStatus, manualCopy);
  updateItemState(documentRef, card, item, actions, connectionAvailable);
  return card;
}

function reconcileItems(
  documentRef: Document,
  feed: HTMLOListElement,
  items: readonly TextTransferUiItem[],
  actions: TextTransferActions,
  clipboard: ClipboardWriter,
  linkOpener: LinkOpener,
  connectionAvailable: boolean,
  cards: Map<string, HTMLLIElement>,
  fingerprints: Map<string, string>,
): void {
  const activeIds = new Set(items.map((item) => item.messageId));
  for (const [messageId, card] of cards) {
    if (activeIds.has(messageId)) continue;
    card.remove();
    cards.delete(messageId);
    fingerprints.delete(messageId);
  }

  items.forEach((item, index) => {
    const fingerprint = immutableItemFingerprint(item);
    let card = cards.get(item.messageId);
    if (card === undefined || fingerprints.get(item.messageId) !== fingerprint) {
      card?.remove();
      card = createItem(
        documentRef,
        item,
        actions,
        clipboard,
        linkOpener,
        connectionAvailable,
      );
      cards.set(item.messageId, card);
      fingerprints.set(item.messageId, fingerprint);
    } else {
      updateItemState(documentRef, card, item, actions, connectionAvailable);
    }
    const currentAtIndex = feed.children.item(index);
    if (currentAtIndex !== card) feed.insertBefore(card, currentAtIndex);
  });
}

function updateItemState(
  documentRef: Document,
  card: HTMLLIElement,
  item: TextTransferUiItem,
  actions: TextTransferActions,
  connectionAvailable: boolean,
): void {
  const direction = directionLabel(item.direction);
  const kind = item.contentKind === "LINK" ? "Ссылка" : "Текст";
  const status = statusLabel(item.status);
  card.dataset.direction = item.direction;
  card.dataset.status = item.status;
  card.dataset.viewState = textItemPresentationState(item.status);
  card.setAttribute("aria-label", `${direction}. ${kind}. ${status}.`);
  card.querySelector<HTMLElement>(".text-card__status")!.textContent = status;

  const actionsRow = card.querySelector<HTMLDivElement>(".text-card__actions")!;
  const canRetry =
    (item.status === "FAILED" || item.status === "UNCERTAIN") &&
    item.retryable === true;
  let retry = actionsRow.querySelector<HTMLButtonElement>(".text-card__retry");
  if (!canRetry) {
    retry?.remove();
    return;
  }
  if (retry === null) {
    retry = documentRef.createElement("button");
    retry.type = "button";
    retry.className = "text-card__retry";
    retry.dataset.retryMessageId = item.messageId;
    retry.textContent = "Повторить";
    retry.addEventListener("click", () => actions.onRetry(item.messageId));
    actionsRow.append(retry);
  }
  retry.disabled = !connectionAvailable;
}

function announceTextChanges(
  items: readonly TextTransferUiItem[],
  knownStatuses: Map<string, TextTransferUiItem["status"]>,
  announcer: HTMLElement,
): void {
  const activeIds = new Set(items.map((item) => item.messageId));
  let announcement: string | undefined;
  for (const item of items) {
    const previousStatus = knownStatuses.get(item.messageId);
    if (previousStatus === undefined || previousStatus !== item.status) {
      announcement = `${item.senderLabel}. ${directionLabel(item.direction)}. ${statusLabel(item.status)}.`;
    }
    knownStatuses.set(item.messageId, item.status);
  }
  for (const messageId of knownStatuses.keys()) {
    if (!activeIds.has(messageId)) knownStatuses.delete(messageId);
  }
  if (announcement !== undefined) announcer.textContent = announcement;
}
function immutableItemFingerprint(item: TextTransferUiItem): string {
  return JSON.stringify([
    item.messageId,
    item.timestamp,
    item.content,
    item.contentKind,
    item.direction,
    item.senderLabel,
  ]);
}

function directionLabel(direction: TextTransferUiItem["direction"]): string {
  return direction === "ANDROID_TO_BROWSER" ? "С телефона" : "На телефон";
}
function statusLabel(status: TextTransferUiItem["status"]): string {
  switch (status) {
    case "PENDING":
      return "Ожидает";
    case "SENDING":
      return "Отправляется";
    case "UNCERTAIN":
      return "Результат неизвестен";
    case "DELIVERED":
      return "Доставлено";
    case "FAILED":
      return "Ошибка";
  }
}

function requiredElement<T extends Element>(
  documentRef: Document,
  selector: string,
): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) {
    throw new Error(`Required text transfer element is missing: ${selector}`);
  }
  return element;
}
