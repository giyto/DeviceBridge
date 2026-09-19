import type { SessionUiEffect, SessionUiState } from "./sessionController";
import { sessionPresentationState } from "./uiPresentationState";

export interface ShellActions {
  readonly onRetry: () => void;
  readonly onSubmitCode: (code: string, rememberBrowser: boolean) => void;
  readonly onDisconnect: () => void;
}

export interface ShellView {
  render(state: SessionUiState): void;
  consume(effect: SessionUiEffect): void;
  dispose(): void;
}

export function createShellView(
  documentRef: Document,
  actions: ShellActions,
): ShellView {
  const status = requiredElement<HTMLElement>(documentRef, '[data-role="status"]');
  const title = requiredElement<HTMLElement>(documentRef, '[data-role="status-title"]');
  const detail = requiredElement<HTMLElement>(documentRef, '[data-role="status-detail"]');
  const versionData = requiredElement<HTMLElement>(documentRef, '[data-role="version-data"]');
  const protocolVersion = requiredElement<HTMLElement>(documentRef, '[data-role="protocol-version"]');
  const assetVersion = requiredElement<HTMLElement>(documentRef, '[data-role="asset-version"]');
  const retryButton = requiredElement<HTMLButtonElement>(documentRef, '[data-action="retry"]');
  const form = requiredElement<HTMLFormElement>(documentRef, '[data-role="pairing-form"]');
  const codeInput = requiredElement<HTMLInputElement>(documentRef, "#pairing-code");
  const rememberBrowser = requiredElement<HTMLInputElement>(documentRef, "#remember-browser");
  const pairButton = requiredElement<HTMLButtonElement>(documentRef, '[data-action="pair"]');
  const disconnectButton = requiredElement<HTMLButtonElement>(
    documentRef,
    '[data-action="disconnect"]',
  );
  const sessionTitle = requiredElement<HTMLElement>(documentRef, '[data-role="session-title"]');
  const sessionDetail = requiredElement<HTMLElement>(documentRef, '[data-role="session-detail"]');
  const consumedEffectIds = new Set<string>();
  let previousKind: SessionUiState["kind"] = "checking";
  let restoreAfterRetry = false;

  const onRetry = (): void => {
    restoreAfterRetry = true;
    actions.onRetry();
  };
  const onDisconnect = (): void => actions.onDisconnect();
  const onInput = (): void => {
    codeInput.value = codeInput.value.replace(/\D/g, "").slice(0, 6);
    codeInput.setCustomValidity("");
  };
  const onSubmit = (event: SubmitEvent): void => {
    event.preventDefault();
    if (!/^\d{6}$/.test(codeInput.value)) {
      codeInput.setCustomValidity("Введите ровно шесть цифр");
      codeInput.reportValidity();
      return;
    }
    actions.onSubmitCode(codeInput.value, rememberBrowser.checked);
  };

  retryButton.addEventListener("click", onRetry);
  disconnectButton.addEventListener("click", onDisconnect);
  codeInput.addEventListener("input", onInput);
  form.addEventListener("submit", onSubmit);

  const consume = (effect: SessionUiEffect): void => {
    if (consumedEffectIds.has(effect.id)) return;
    consumedEffectIds.add(effect.id);
    switch (effect.kind) {
      case "clearPairingForm":
        codeInput.value = "";
        codeInput.setCustomValidity("");
        rememberBrowser.checked = false;
        break;
    }
  };
  const render = (state: SessionUiState): void => {
    status.dataset.state = state.kind;
    status.dataset.viewState = sessionPresentationState(state);
    versionData.hidden = true;
    retryButton.hidden = true;
    form.hidden = true;
    disconnectButton.hidden = true;
    codeInput.disabled = false;
    rememberBrowser.disabled = false;
    pairButton.disabled = false;
    pairButton.textContent = "Подключить браузер";
    pairButton.removeAttribute("aria-busy");
    retryButton.disabled = false;
    retryButton.textContent = "Проверить снова";

    if ("manifest" in state && state.manifest !== undefined) {
      protocolVersion.textContent = String(state.manifest.protocolVersion);
      assetVersion.textContent = state.manifest.webAssetVersion;
      versionData.hidden = false;
    }

    switch (state.kind) {
      case "checking":
        title.textContent = "Проверяем DeviceBridge…";
        detail.textContent = "Это займёт несколько секунд.";
        sessionTitle.textContent = "Подготовка подключения…";
        sessionDetail.textContent = "Проверяем локальный сервер на телефоне.";
        break;
      case "ready":
        title.textContent = "DeviceBridge доступен";
        detail.textContent = "Локальный сервер отвечает. Можно подключить этот браузер.";
        sessionTitle.textContent = "Подключите браузер";
        sessionDetail.textContent =
          `Введите код с телефона. Осталось попыток: ${state.challenge.attemptsRemaining}.`;
        form.hidden = false;
        break;
      case "submitting":
        title.textContent = "Проверяем код…";
        detail.textContent = "Не закрывайте эту вкладку.";
        sessionTitle.textContent = "Отправляем запрос";
        sessionDetail.textContent = "После проверки потребуется подтверждение на телефоне.";
        form.hidden = false;
        codeInput.disabled = true;
        rememberBrowser.disabled = true;
        pairButton.disabled = true;
        pairButton.textContent = "Проверяем код…";
        pairButton.setAttribute("aria-busy", "true");
        break;
      case "awaiting":
        title.textContent = "Ожидаем подтверждение";
        detail.textContent = "Запрос появился в приложении на телефоне.";
        sessionTitle.textContent = "Подтвердите браузер на телефоне";
        sessionDetail.textContent = "Разрешите или отклоните запрос в DeviceBridge.";
        form.hidden = false;
        codeInput.disabled = true;
        rememberBrowser.disabled = true;
        pairButton.disabled = true;
        pairButton.textContent = "Ожидаем подтверждение…";
        pairButton.setAttribute("aria-busy", "true");
        break;
      case "uncertain":
        title.textContent = "Результат подключения неизвестен";
        detail.textContent = state.message;
        sessionTitle.textContent = state.checking
          ? "Проверяем исходный запрос…"
          : "Проверьте исходный запрос";
        sessionDetail.textContent =
          "Код не отправляется повторно. DeviceBridge проверит уже созданный запрос.";
        retryButton.textContent = "Проверить результат";
        retryButton.hidden = state.checking;
        retryButton.disabled = state.checking;
        break;
      case "connected":
        title.textContent = "Безопасное подключение активно";
        detail.textContent = "Этот браузер подтверждён телефоном.";
        sessionTitle.textContent = "Браузер подключён";
        sessionDetail.textContent =
          `Активных браузеров: ${state.status.activeSessionCount}.`;
        disconnectButton.hidden = false;
        break;
      case "reconnecting":
        title.textContent = "Восстанавливаем подключение…";
        detail.textContent =
          `Попытка ${state.attempt}. Следующая проверка через ${state.nextRetryInMs / 1_000} сек.`;
        sessionTitle.textContent = "Связь с телефоном прервана";
        sessionDetail.textContent =
          "DeviceBridge повторяет подключение автоматически. Данные и черновики сохранены.";
        break;
      case "needsUserAction":
        title.textContent = "Нужно проверить подключение";
        detail.textContent = state.message;
        sessionTitle.textContent = "Автоматические попытки завершены";
        sessionDetail.textContent =
          "Проверьте, что телефон и компьютер находятся в одной сети, затем повторите проверку.";
        retryButton.textContent = "Проверить подключение";
        retryButton.hidden = false;
        break;
      case "blocked":
        title.textContent = "Попытки временно заблокированы";
        detail.textContent = state.message;
        sessionTitle.textContent = "Подождите перед повтором";
        sessionDetail.textContent = state.retryAfterSeconds === undefined
          ? "Повторите подключение позже."
          : `Повторите через ${state.retryAfterSeconds} сек.`;
        retryButton.hidden = false;
        break;
      case "expired":
        title.textContent = "Код или запрос истёк";
        detail.textContent = state.message;
        sessionTitle.textContent = "Нужен новый код";
        sessionDetail.textContent = "Получите актуальный код на телефоне и попробуйте снова.";
        retryButton.hidden = false;
        break;
      case "denied":
        title.textContent = "Подключение отклонено";
        detail.textContent = state.message;
        sessionTitle.textContent = "Браузер не подключён";
        sessionDetail.textContent = "Можно создать новый запрос, когда будете готовы.";
        retryButton.hidden = false;
        break;
      case "sessionLost":
        title.textContent = "Сессия завершена";
        detail.textContent = state.message;
        sessionTitle.textContent = "Подключитесь снова";
        sessionDetail.textContent = "Создайте новый запрос и подтвердите его на телефоне.";
        retryButton.hidden = false;
        break;
      case "offline":
        title.textContent = "DeviceBridge недоступен";
        detail.textContent = state.nextRetryInMs === undefined
          ? `${state.message} Проверьте адрес и состояние приложения на телефоне.`
          : `${state.message} Повторная проверка через ${state.nextRetryInMs / 1_000} сек.`;
        sessionTitle.textContent = "Нет связи с телефоном";
        sessionDetail.textContent = "Телефон и компьютер должны оставаться в одной локальной сети.";
        retryButton.hidden = false;
        break;
    }

    const pairingFailed =
      (previousKind === "submitting" || previousKind === "awaiting") &&
      state.kind === "ready";
    previousKind = state.kind;
    if (pairingFailed && !form.hidden && !codeInput.disabled) {
      codeInput.focus();
    } else if (restoreAfterRetry) {
      const target = !retryButton.hidden && !retryButton.disabled
        ? retryButton
        : !form.hidden && !codeInput.disabled
          ? codeInput
          : !disconnectButton.hidden && !disconnectButton.disabled
            ? disconnectButton
            : undefined;
      if (target !== undefined) {
        target.focus();
        restoreAfterRetry = false;
      }
    }
  };

  render({ kind: "checking" });

  return {
    render,
    consume,
    dispose: () => {
      retryButton.removeEventListener("click", onRetry);
      disconnectButton.removeEventListener("click", onDisconnect);
      codeInput.removeEventListener("input", onInput);
      form.removeEventListener("submit", onSubmit);
      consumedEffectIds.clear();
    },
  };
}

function requiredElement<T extends Element>(
  documentRef: Document,
  selector: string,
): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) throw new Error(`Required shell element is missing: ${selector}`);
  return element;
}
