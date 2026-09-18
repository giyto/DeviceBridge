import { SessionApiClient } from "./sessionApiClient";
import { SessionController } from "./sessionController";
import { SessionEventSocketClient } from "./sessionEventSocketClient";
import { BrowserSessionTokenStore } from "./sessionTokenStore";
import { BrowserTrustedCredentialStore } from "./browserTrustedCredentialStore";
import { createShellView } from "./shellView";
import { TextApiClient } from "./textApiClient";
import { TextTransferController } from "./textTransferController";
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

let controller: SessionController;
let textController: TextTransferController;
let fileController: FileTransferController;
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
    view.dispose();
    textView.dispose();
    fileView.dispose();
  },
  { once: true },
);
