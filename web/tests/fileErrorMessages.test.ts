import { describe, expect, it } from "vitest";
import { FileApiError, type FileApiErrorCode } from "../src/fileApiClient";
import { fileCodeMessage } from "../src/fileTransferModel";

// Every code once, so a shared text cannot drift for one of the two surfaces.
const API_ERROR_MESSAGES: Record<FileApiErrorCode, string> = {
  UNAUTHORIZED: "Сессия браузера завершена.",
  FILE_TOO_LARGE: "Файл превышает лимит 1 ГиБ.",
  CHECKSUM_MISMATCH: "Контрольная сумма файла не совпала.",
  DESTINATION_UNAVAILABLE: "Папка назначения недоступна.",
  INSUFFICIENT_SPACE: "На устройстве недостаточно свободного места.",
  SOURCE_UNAVAILABLE: "Исходный файл недоступен или изменился.",
  NOT_APPROVED: "Передача ещё не подтверждена на телефоне.",
  CANCELLED: "Передача отменена.",
  SESSION_UNAVAILABLE: "Телефон или сессия сейчас недоступны.",
  MESSAGE_CONFLICT: "Команда передачи конфликтует с предыдущей.",
  UNSUPPORTED_VERSION: "Версия file protocol не поддерживается.",
  STREAM_FAILED: "Поток передачи был прерван.",
  INVALID_PAYLOAD: "Некорректная команда передачи файла.",
};

const PHONE_ERROR_MESSAGES: Record<FileApiErrorCode, string> = {
  UNAUTHORIZED: "Сессия браузера завершена.",
  FILE_TOO_LARGE: "Файл превышает установленный лимит 512 МиБ.",
  CHECKSUM_MISMATCH: "Контрольная сумма файла не совпала.",
  DESTINATION_UNAVAILABLE: "Папка назначения недоступна.",
  INSUFFICIENT_SPACE: "На устройстве недостаточно свободного места.",
  SOURCE_UNAVAILABLE: "Исходный файл недоступен или изменился.",
  NOT_APPROVED: "Подтвердите передачу на телефоне.",
  CANCELLED: "Передача отменена.",
  SESSION_UNAVAILABLE: "Сессия браузера завершена.",
  MESSAGE_CONFLICT: "Команда передачи конфликтует с предыдущей.",
  UNSUPPORTED_VERSION: "Версия file protocol не поддерживается.",
  STREAM_FAILED: "Поток передачи был прерван.",
  INVALID_PAYLOAD: "Некорректная файловая операция.",
};

describe("file error texts", () => {
  it.each(Object.entries(API_ERROR_MESSAGES))("names API error %s", (code, message) => {
    expect(new FileApiError(400, code as FileApiErrorCode).message).toBe(message);
  });

  it.each(Object.entries(PHONE_ERROR_MESSAGES))("names phone error %s", (code, message) => {
    expect(fileCodeMessage(code as FileApiErrorCode, 512 * 1024 ** 2)).toBe(message);
  });
});
