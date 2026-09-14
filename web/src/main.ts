import { ConnectionController } from "./connectionController";
import { createShellView } from "./shellView";
import { WebManifestClient } from "./webManifestClient";

const client = new WebManifestClient();
let controller: ConnectionController;
const view = createShellView(document, () => controller.retry());

controller = new ConnectionController(client, (state) => view.render(state));
controller.start();

globalThis.addEventListener(
  "beforeunload",
  () => {
    controller.dispose();
    view.dispose();
  },
  { once: true },
);
