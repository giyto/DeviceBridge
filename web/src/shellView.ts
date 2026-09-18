import type { SessionUiState } from "./sessionController";

export interface ShellActions {
  readonly onRetry: () => void;
  readonly onSubmitCode: (code: string, rememberBrowser: boolean) => void;
  readonly onDisconnect: () => void;
}

export interface ShellView {
  render(state: SessionUiState): void;
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

  const onRetry = (): void => actions.onRetry();
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

  const render = (state: SessionUiState): void => {
    status.dataset.state = state.kind;
    versionData.hidden = true;
    retryButton.hidden = true;
    form.hidden = true;
    disconnectButton.hidden = true;
    codeInput.disabled = false;
    rememberBrowser.disabled = false;
    pairButton.disabled = false;

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
        break;
      case "connected":
        title.textContent = "Безопасное подключение активно";
        detail.textContent = "Этот браузер подтверждён телефоном.";
        sessionTitle.textContent = "Браузер подключён";
        sessionDetail.textContent =
          `Активных браузеров: ${state.status.activeSessionCount}.`;
        disconnectButton.hidden = false;
        codeInput.value = "";
        rememberBrowser.checked = false;
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
        codeInput.value = "";
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
  };

  render({ kind: "checking" });

  return {
    render,
    dispose: () => {
      retryButton.removeEventListener("click", onRetry);
      disconnectButton.removeEventListener("click", onDisconnect);
      codeInput.removeEventListener("input", onInput);
      form.removeEventListener("submit", onSubmit);
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
