import { SessionApiClient } from "./sessionApiClient";
import { SessionController } from "./sessionController";
import { SessionEventSocketClient } from "./sessionEventSocketClient";
import { BrowserSessionTokenStore } from "./sessionTokenStore";
import { BrowserTrustedCredentialStore } from "./browserTrustedCredentialStore";
import { createShellView } from "./shellView";
import { TextApiClient } from "./textApiClient";
import { TextTransferController } from "./textTransferController";
import { BrowserTextDraftStore } from "./browserTextDraftStore";
import { createTextTransferView } from "./textTransferView";
import { WebManifestClient } from "./webManifestClient";
import { createBrowserLabel } from "./browserIdentity";
import { createProtocolMessageId } from "./protocolMessageId";
import { FileApiClient } from "./fileApiClient";
import { FileTransferController } from "./fileTransferController";
import { createFileTransferView } from "./fileTransferView";
import { hashFileStreaming } from "./fileVerifier";
import { NativeFileDownloader } from "./nativeFileDownloader";
import { XhrFileUploader } from "./xhrFileUploader";
import { BrowserThemePreferenceStore } from "./browserThemePreferenceStore";
import {
  createDocumentThemeApplication,
  ThemeController,
} from "./themeController";
import { createThemeControl } from "./themeControl";
import { BrowserSecurityWarningPreferenceStore } from "./browserSecurityWarningPreferenceStore";
import { createSecurityWarningController } from "./securityWarningController";

let controller: SessionController;
let textController: TextTransferController;
let fileController: FileTransferController;
let themeController: ThemeController;
const documentThemeApplication = createDocumentThemeApplication(document);
const themeControl = createThemeControl(
  document,
  (preference) => themeController.setPreference(preference),
);
themeController = new ThemeController(
  new BrowserThemePreferenceStore(),
  (application) => {
    documentThemeApplication(application);
    themeControl.render(application);
  },
  window,
);
themeController.start();
const securityWarningController = createSecurityWarningController(
  document,
  new BrowserSecurityWarningPreferenceStore(),
);
securityWarningController.start();
const view = createShellView(document, {
  onRetry: () => controller.retry(),
  onSubmitCode: (code, rememberBrowser) => controller.submitCode(code, rememberBrowser),
  onDisconnect: () => controller.disconnect(),
});
const textView = createTextTransferView(document, {
  onDraftChange: (draft) => textController.updateDraft(draft),
  onSend: () => textController.sendDraft(),
  onRetry: (messageId) => textController.retry(messageId),
});
const fileView = createFileTransferView(document, {
  onSelect: (files) => fileController.addFiles(files),
  onConfirm: () => void fileController.confirmSelection(),
  onRemoveDraft: (key) => fileController.removeDraft(key),
  onClearDraft: () => fileController.clearDraft(),
  onCancel: (transferId) => void fileController.cancel(transferId),
  onRetry: (transferId) => void fileController.retry(transferId),
  onDownload: (transferId) => void fileController.download(transferId),
});
textController = new TextTransferController(
  new TextApiClient(),
  (state) => textView.render(state),
  createProtocolMessageId,
  () => Date.now(),
  () => controller.handleTextUnauthorized(),
  new BrowserTextDraftStore(),
);
fileController = new FileTransferController(
  new FileApiClient(),
  new XhrFileUploader(),
  new NativeFileDownloader(document),
  hashFileStreaming,
  (state) => fileView.render(state),
  createProtocolMessageId,
  () => Date.now(),
  () => controller.handleFileUnauthorized(),
);

controller = new SessionController(
  new WebManifestClient(),
  new SessionApiClient(),
  new BrowserSessionTokenStore(),
  new SessionEventSocketClient(),
  (state) => view.render(state),
  (effect) => view.consume(effect),
  createBrowserLabel(navigator.userAgent, navigator.platform),
  textController,
  fileController,
  new BrowserTrustedCredentialStore(),
);
controller.start();

globalThis.addEventListener(
  "beforeunload",
  () => {
    controller.dispose();
    textController.dispose();
    fileController.dispose();
    themeController.dispose();
    themeControl.dispose();
    securityWarningController.dispose();
    view.dispose();
    textView.dispose();
    fileView.dispose();
  },
  { once: true },
);
