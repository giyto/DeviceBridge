import type { ConnectionState } from "./connectionController";

export interface ShellView {
  render(state: ConnectionState): void;
  dispose(): void;
}

export function createShellView(
  documentRef: Document,
  onRetry: () => void,
): ShellView {
  const status = requiredElement<HTMLElement>(documentRef, '[data-role="status"]');
  const title = requiredElement<HTMLElement>(documentRef, '[data-role="status-title"]');
  const detail = requiredElement<HTMLElement>(documentRef, '[data-role="status-detail"]');
  const versionData = requiredElement<HTMLElement>(
    documentRef,
    '[data-role="version-data"]',
  );
  const protocolVersion = requiredElement<HTMLElement>(
    documentRef,
    '[data-role="protocol-version"]',
  );
  const assetVersion = requiredElement<HTMLElement>(
    documentRef,
    '[data-role="asset-version"]',
  );
  const retryButton = requiredElement<HTMLButtonElement>(
    documentRef,
    '[data-action="retry"]',
  );

  retryButton.addEventListener("click", onRetry);

  const render = (state: ConnectionState): void => {
    status.dataset.state = state.kind;
    versionData.hidden = true;
    retryButton.hidden = true;

    switch (state.kind) {
      case "checking":
        title.textContent = "Проверяем DeviceBridge…";
        detail.textContent = "Это займёт несколько секунд.";
        break;
      case "available":
        title.textContent = "DeviceBridge доступен";
        detail.textContent = "Локальный web server отвечает и готов к следующему этапу.";
        protocolVersion.textContent = String(state.manifest.protocolVersion);
        assetVersion.textContent = state.manifest.webAssetVersion;
        versionData.hidden = false;
        break;
      case "unavailable":
        title.textContent = "Связь потеряна";
        detail.textContent =
          state.nextRetryInMs === undefined
            ? `${state.message} Проверьте адрес или состояние приложения на телефоне.`
            : `${state.message} Повторная проверка через ${state.nextRetryInMs / 1_000} сек.`;
        retryButton.hidden = false;
        break;
    }
  };

  render({ kind: "checking" });

  return {
    render,
    dispose: () => retryButton.removeEventListener("click", onRetry),
  };
}

function requiredElement<T extends Element>(
  documentRef: Document,
  selector: string,
): T {
  const element = documentRef.querySelector<T>(selector);
  if (element === null) {
    throw new Error(`Required shell element is missing: ${selector}`);
  }
  return element;
}
