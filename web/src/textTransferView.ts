import type {
  TextTransferUiItem,
  TextTransferUiState,
} from "./textTransferController";
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

  const onInput = (): void => actions.onDraftChange(draft.value);
  const onSubmit = (event: SubmitEvent): void => {
    event.preventDefault();
    if (draft.value.trim().length === 0 || send.disabled) return;
    actions.onSend();
  };
  draft.addEventListener("input", onInput);
  form.addEventListener("submit", onSubmit);

  const render = (state: TextTransferUiState): void => {
    if (state.kind === "inactive") {
      section.hidden = true;
      draft.value = "";
      draft.disabled = true;
      send.disabled = true;
      error.hidden = true;
      error.textContent = "";
      feed.replaceChildren();
      count.textContent = "0";
      empty.hidden = false;
      return;
    }

    section.hidden = false;
    draft.disabled = state.sending;
    if (draft.value !== state.draft) draft.value = state.draft;
    send.disabled = state.sending || state.draft.trim().length === 0;
    send.textContent = state.sending ? "Отправляем…" : "Отправить на телефон";
    error.hidden = state.error === undefined;
    error.textContent = state.error?.message ?? "";
    count.textContent = String(state.items.length);
    empty.hidden = state.items.length > 0;
    feed.replaceChildren(
      ...state.items.map((item) =>
        createItem(documentRef, item, actions, clipboard, linkOpener)
      ),
    );
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
): HTMLLIElement {
  const card = documentRef.createElement("li");
  card.className = "text-card";
  card.dataset.messageId = item.messageId;
  card.dataset.direction = item.direction;
  card.dataset.status = item.status;

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
  const kind = documentRef.createElement("span");
  kind.textContent = item.contentKind === "LINK" ? "Ссылка" : "Текст";
  const status = documentRef.createElement("span");
  status.className = "text-card__status";
  status.textContent = statusLabel(item.status);
  footer.append(kind, status);

  if (canonicalHttpUrl(item.content, item.contentKind) !== undefined) {
    const open = documentRef.createElement("button");
    open.type = "button";
    open.className = "text-card__open";
    open.dataset.openMessageId = item.messageId;
    open.textContent = "Открыть ссылку";
    open.addEventListener("click", () => {
      linkOpener.open(item.content, item.contentKind);
    });
    footer.append(open);
  }

  const copy = documentRef.createElement("button");
  copy.type = "button";
  copy.className = "text-card__copy";
  copy.dataset.copyMessageId = item.messageId;
  copy.textContent = "Копировать";
  footer.append(copy);

  if (item.status === "FAILED") {
    const retry = documentRef.createElement("button");
    retry.type = "button";
    retry.className = "text-card__retry";
    retry.dataset.retryMessageId = item.messageId;
    retry.textContent = "Повторить";
    retry.addEventListener("click", () => actions.onRetry(item.messageId));
    footer.append(retry);
  }
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
  manualLabel.textContent = "Автокопирование недоступно — выделите текст вручную";
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
  return card;
}

function statusLabel(status: TextTransferUiItem["status"]): string {
  switch (status) {
    case "PENDING":
      return "Ожидает";
    case "SENDING":
      return "Отправляется";
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
