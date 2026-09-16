export interface ClipboardWriter {
  write(content: string): Promise<boolean>;
}

export interface ClipboardEnvironment {
  readonly isSecureContext: boolean;
  readonly clipboard?: {
    writeText(content: string): Promise<void>;
  };
}

export class BrowserClipboardWriter implements ClipboardWriter {
  constructor(
    private readonly environment: ClipboardEnvironment = browserEnvironment(),
  ) {}

  async write(content: string): Promise<boolean> {
    if (!this.environment.isSecureContext || this.environment.clipboard === undefined) {
      return false;
    }
    try {
      await this.environment.clipboard.writeText(content);
      return true;
    } catch {
      return false;
    }
  }
}

function browserEnvironment(): ClipboardEnvironment {
  return {
    isSecureContext: globalThis.isSecureContext === true,
    clipboard: globalThis.navigator?.clipboard,
  };
}
