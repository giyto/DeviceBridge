export function isTextEntry(target: EventTarget | null): boolean {
  if (typeof HTMLTextAreaElement !== "undefined" && target instanceof HTMLTextAreaElement) return true;
  if (typeof HTMLInputElement !== "undefined" && target instanceof HTMLInputElement) {
    return target.type !== "file" && target.type !== "checkbox";
  }
  return target instanceof HTMLElement && target.isContentEditable;
}

const IMAGE_EXTENSIONS: Readonly<Record<string, string>> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/gif": "gif",
  "image/webp": "webp",
  "image/bmp": "bmp",
};

// Browsers name clipboard images generically ("image.png"); real file names are kept.
export function namePastedFiles(files: readonly File[], pastedAt: Date): File[] {
  const base = "Скриншот " + formatPasteTimestamp(pastedAt);
  let screenshots = 0;
  return files.map((file) => {
    if (!file.type.startsWith("image/") || !/^(image(\.\w+)?|blob)?$/i.test(file.name)) {
      return file;
    }
    screenshots += 1;
    const extension = IMAGE_EXTENSIONS[file.type] ?? file.name.split(".").at(-1) ?? "png";
    const suffix = screenshots > 1 ? ` (${screenshots})` : "";
    return new File([file], `${base}${suffix}.${extension}`, {
      type: file.type,
      lastModified: pastedAt.getTime(),
    });
  });
}

function formatPasteTimestamp(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}-${pad(date.getMinutes())}-${pad(date.getSeconds())}`;
}
