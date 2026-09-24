import { SessionApiClient } from "./sessionApiClient";
import {
  SessionController,
  type FileSessionLifecycle,
  type TextSessionLifecycle,
} from "./sessionController";
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
import { certificateSetupUrl, probeCertificateTrust } from "./certificateTrustProbe";
import { BrowserNotificationPreferenceStore } from "./browserNotificationPreferenceStore";
import {
  createBrowserNotificationPort,
  createDocumentAttentionPort,
  createDocumentReveal,
  createDocumentTitlePort,
  EventNotificationController,
} from "./eventNotificationController";
import { createEventNotificationView } from "./eventNotificationView";

let controller: SessionController;
let textController: TextTransferController;
let fileController: FileTransferController;
let themeController: ThemeController;
let notificationController: EventNotificationController;
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
  window.location.protocol === "https:"
    ? {
        probeTrust: () => probeCertificateTrust(navigator.serviceWorker),
        setupUrl: certificateSetupUrl(window.location),
      }
    : null,
);
securityWarningController.start();
const notificationView = createEventNotificationView(document, {
  onEnable: () => void notificationController.enable(),
  onDisable: () => notificationController.disable(),
  onHideContentChange: (hideContent) => notificationController.setHideContent(hideContent),
});
notificationController = new EventNotificationController({
  notifications: createBrowserNotificationPort(window.location, document),
  attention: createDocumentAttentionPort(document, window),
  title: createDocumentTitlePort(document),
  preferences: new BrowserNotificationPreferenceStore(),
  reveal: createDocumentReveal(document),
  render: (state) => notificationView.render(state),
});
notificationController.start();
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
  (state) => {
    fileView.render(state);
    if (state.kind === "active") {
      notificationController.uploadsChanged(
        state.transfers.map((item) => ({
          transferId: item.metadata.transferId,
          direction: item.metadata.direction,
          status: item.status,
        })),
      );
    }
  },
  createProtocolMessageId,
  () => Date.now(),
  () => controller.handleFileUnauthorized(),
);

// Events from the phone also reach the notifications; the controllers stay unaware of them.
const notifyingTextSession: TextSessionLifecycle = {
  activate: (token, sessionScopeId) => textController.activate(token, sessionScopeId),
  deactivate: () => textController.deactivate(),
  suspendSession: () => textController.suspendSession(),
  dispose: () => textController.dispose(),
  setConnectionAvailable: (available) => textController.setConnectionAvailable(available),
  receive: (event) => {
    textController.receive(event);
    notificationController.textReceived(event);
  },
  applySnapshot: (event) => {
    textController.applySnapshot(event);
    notificationController.textSnapshot(event.items);
  },
  receiveError: (event) => textController.receiveError(event),
};
const notifyingFileSession: FileSessionLifecycle = {
  activate: (token, effectiveFileLimitBytes) =>
    fileController.activate(token, effectiveFileLimitBytes),
  deactivate: () => fileController.deactivate(),
  setConnectionAvailable: (available) => fileController.setConnectionAvailable(available),
  receiveOffer: (event) => {
    fileController.receiveOffer(event);
    notificationController.filesOffered(event.items);
  },
  receiveProgress: (event) => fileController.receiveProgress(event),
  applySnapshot: (event) => {
    fileController.applySnapshot(event);
    notificationController.fileSnapshot(event.items);
  },
  receiveError: (event) => fileController.receiveError(event),
};

controller = new SessionController(
  new WebManifestClient(),
  new SessionApiClient(),
  new BrowserSessionTokenStore(),
  new SessionEventSocketClient(),
  (state) => {
    view.render(state);
    notificationController.sessionChanged(state.kind);
  },
  (effect) => view.consume(effect),
  createBrowserLabel(navigator.userAgent, navigator.platform),
  notifyingTextSession,
  notifyingFileSession,
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
    notificationController.dispose();
    notificationView.dispose();
    view.dispose();
    textView.dispose();
    fileView.dispose();
  },
  { once: true },
);
