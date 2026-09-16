import { SessionApiClient } from "./sessionApiClient";
import { SessionController } from "./sessionController";
import { SessionEventSocketClient } from "./sessionEventSocketClient";
import { BrowserSessionTokenStore } from "./sessionTokenStore";
import { createShellView } from "./shellView";
import { TextApiClient } from "./textApiClient";
import { TextTransferController } from "./textTransferController";
import { createTextTransferView } from "./textTransferView";
import { WebManifestClient } from "./webManifestClient";
import { createBrowserLabel } from "./browserIdentity";
import { createProtocolMessageId } from "./protocolMessageId";

let controller: SessionController;
let textController: TextTransferController;
const view = createShellView(document, {
  onRetry: () => controller.retry(),
  onSubmitCode: (code) => controller.submitCode(code),
  onDisconnect: () => controller.disconnect(),
});
const textView = createTextTransferView(document, {
  onDraftChange: (draft) => textController.updateDraft(draft),
  onSend: () => textController.sendDraft(),
  onRetry: (messageId) => textController.retry(messageId),
});
textController = new TextTransferController(
  new TextApiClient(),
  (state) => textView.render(state),
  createProtocolMessageId,
  () => Date.now(),
  () => controller.handleTextUnauthorized(),
);

controller = new SessionController(
  new WebManifestClient(),
  new SessionApiClient(),
  new BrowserSessionTokenStore(),
  new SessionEventSocketClient(),
  (state) => view.render(state),
  createBrowserLabel(navigator.userAgent, navigator.platform),
  textController,
);
controller.start();

globalThis.addEventListener(
  "beforeunload",
  () => {
    controller.dispose();
    textController.dispose();
    view.dispose();
    textView.dispose();
  },
  { once: true },
);
