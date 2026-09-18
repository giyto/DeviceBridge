import { FileTransferController } from "../src/fileTransferController";
import { createFileTransferView } from "../src/fileTransferView";

let controller: FileTransferController;
const view = createFileTransferView(document, {
  onSelect: (files) => controller.addFiles(files),
  onConfirm: () => void controller.confirmSelection(),
  onRemoveDraft: (key) => controller.removeDraft(key),
  onClearDraft: () => controller.clearDraft(),
  onCancel: () => undefined,
  onRetry: () => undefined,
  onDownload: () => undefined,
});
let sequence = 0;
controller = new FileTransferController(
  {
    offer: async (_token, command) => ({
      protocolVersion: 1,
      messageId: `snapshot-${++sequence}`,
      type: "file.snapshot",
      timestamp: Date.now(),
      items: command.items.map((metadata) => ({
        metadata,
        status: "CONNECTING",
        bytesTransferred: 0,
        speedBytesPerSecond: 0,
      })),
    }),
    requestDownloadGrant: async () => { throw new Error("unused"); },
    cancel: async () => { throw new Error("unused"); },
    retry: async () => { throw new Error("unused"); },
  },
  { upload: async () => { throw new Error("unused"); } },
  { start: () => undefined },
  async () => "a".repeat(64),
  (state) => view.render(state),
  () => `gate-${++sequence}`,
);
controller.activate("gate-token");
