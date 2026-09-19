export type FailureSeverity = "recoverable" | "terminal";

export type RecoveryActionId =
  | "request_permission"
  | "open_settings"
  | "connect_to_local_network"
  | "start_server"
  | "retry"
  | "manage_sessions"
  | "pair_again"
  | "select_session"
  | "update_client"
  | "start_new_operation"
  | "edit_content"
  | "select_file"
  | "select_destination"
  | "reduce_selection"
  | "edit_setting";

export interface FailureDescriptor {
  readonly code: string;
  readonly severity: FailureSeverity;
  readonly recoveryActions: readonly RecoveryActionId[];
  readonly title: string;
  readonly message: string;
}

export const failureCatalog = {
  unknown_error: failure("unknown_error", "recoverable", ["retry"], "Не удалось выполнить действие", "Повторите попытку. Если ошибка сохраняется, начните операцию заново."),
  local_network_permission_denied: failure("local_network_permission_denied", "recoverable", ["request_permission"], "Нет доступа к локальной сети", "Разрешите DeviceBridge находить устройства в локальной сети."),
  local_network_permission_revoked: failure("local_network_permission_revoked", "recoverable", ["open_settings"], "Доступ к сети отозван", "Откройте настройки Android и верните разрешение локальной сети."),
  no_lan_network: failure("no_lan_network", "recoverable", ["connect_to_local_network"], "Локальная сеть недоступна", "Подключите телефон и компьютер к одной доверенной Wi-Fi сети."),
  ambiguous_lan_network: failure("ambiguous_lan_network", "recoverable", ["connect_to_local_network"], "Не удалось выбрать сеть", "Оставьте одно активное локальное подключение и повторите запуск."),
  network_lost: failure("network_lost", "recoverable", ["connect_to_local_network"], "Соединение с сетью потеряно", "Восстановите локальную сеть, затем запустите сервер снова."),
  address_changed: failure("address_changed", "recoverable", ["start_server"], "Адрес телефона изменился", "Запустите сервер снова и откройте новый адрес на компьютере."),
  foreground_start_not_allowed: failure("foreground_start_not_allowed", "recoverable", ["retry"], "Android не разрешил запуск", "Откройте DeviceBridge и повторите запуск сервера."),
  server_start_failed: failure("server_start_failed", "recoverable", ["retry"], "Сервер не запустился", "Проверьте сеть и повторите запуск."),
  server_stop_timeout: failure("server_stop_timeout", "recoverable", ["retry"], "Сервер не успел остановиться", "Повторите остановку или перезапустите приложение."),
  session_capacity_reached: failure("session_capacity_reached", "recoverable", ["manage_sessions"], "Слишком много подключений", "Отключите ненужный браузер и попробуйте снова."),
  server_generation_closed: failure("server_generation_closed", "terminal", ["start_server"], "Сеанс сервера завершён", "Запустите сервер и подключите браузер к новому сеансу."),
  pairing_request_expired: failure("pairing_request_expired", "terminal", ["pair_again"], "Время подключения истекло", "Получите новый код на телефоне и подключитесь снова."),
  pairing_request_not_found: failure("pairing_request_not_found", "terminal", ["pair_again"], "Запрос подключения не найден", "Начните подключение браузера заново."),
  text_connection_lost: failure("text_connection_lost", "recoverable", ["retry"], "Текст не отправлен", "Соединение прервалось. Проверьте его и повторите отправку вручную."),
  session_closed: failure("session_closed", "terminal", ["select_session"], "Подключение закрыто", "Выберите действующее подключение или подключите браузер заново."),
  protocol_version_unsupported: failure("protocol_version_unsupported", "terminal", ["update_client"], "Версии DeviceBridge не совпадают", "Обновите страницу или приложение до совместимой версии."),
  empty_content: failure("empty_content", "terminal", ["edit_content"], "Нечего отправлять", "Введите текст или ссылку."),
  content_too_large: failure("content_too_large", "terminal", ["edit_content"], "Текст слишком большой", "Сократите текст и отправьте его снова."),
  message_conflict: failure("message_conflict", "terminal", ["start_new_operation"], "Операция уже изменилась", "Создайте новую отправку."),
  message_not_found: failure("message_not_found", "terminal", ["start_new_operation"], "Операция не найдена", "Начните отправку заново."),
  file_checksum_mismatch: failure("file_checksum_mismatch", "terminal", ["select_file"], "Файл повреждён при передаче", "Выберите исходный файл снова и повторите отправку."),
  file_stream_failed: failure("file_stream_failed", "recoverable", ["retry"], "Передача файла прервалась", "Проверьте соединение и повторите передачу вручную."),
  file_storage_unavailable: failure("file_storage_unavailable", "recoverable", ["select_destination"], "Папка назначения недоступна", "Выберите доступную папку и продолжите передачу."),
  file_insufficient_space: failure("file_insufficient_space", "recoverable", ["select_destination"], "Недостаточно места", "Освободите место или выберите другое хранилище."),
  file_source_unavailable: failure("file_source_unavailable", "terminal", ["select_file"], "Исходный файл недоступен", "Выберите файл заново и создайте новую передачу."),
  file_capacity_reached: failure("file_capacity_reached", "recoverable", ["reduce_selection"], "Очередь файлов заполнена", "Уменьшите выбранный набор или дождитесь завершения текущих файлов."),
  invalid_transfer_id: failure("invalid_transfer_id", "terminal", ["start_new_operation"], "Передача устарела", "Начните передачу заново."),
  invalid_file_name: failure("invalid_file_name", "terminal", ["select_file"], "Имя файла не поддерживается", "Переименуйте или выберите другой файл."),
  invalid_file_size: failure("invalid_file_size", "terminal", ["select_file"], "Размер файла недоступен", "Выберите файл, размер которого можно определить."),
  file_too_large: failure("file_too_large", "terminal", ["reduce_selection"], "Файл превышает лимит", "Выберите файл меньшего размера или измените лимит в настройках."),
  invalid_checksum: failure("invalid_checksum", "terminal", ["select_file"], "Не удалось проверить файл", "Выберите исходный файл снова."),
  file_size_mismatch: failure("file_size_mismatch", "terminal", ["select_file"], "Размер файла изменился", "Выберите файл заново перед повторной отправкой."),
  history_write_failed: failure("history_write_failed", "recoverable", ["retry"], "История не сохранена", "Освободите место и повторите действие."),
  invalid_device_name: failure("invalid_device_name", "terminal", ["edit_setting"], "Некорректное имя устройства", "Исправьте имя и сохраните настройки снова."),
  invalid_retention_days: failure("invalid_retention_days", "terminal", ["edit_setting"], "Некорректный срок истории", "Укажите допустимый срок хранения."),
  invalid_file_limit: failure("invalid_file_limit", "terminal", ["edit_setting"], "Некорректный лимит файла", "Укажите допустимый максимальный размер файла."),
  invalid_payload: failure("invalid_payload", "terminal", ["start_new_operation"], "Некорректный запрос", "Обновите страницу и начните операцию заново."),
  invalid_pairing_code: failure("invalid_pairing_code", "recoverable", ["pair_again"], "Неверный код подключения", "Проверьте шестизначный код на телефоне и повторите ввод."),
  invalid_trusted_credential: failure("invalid_trusted_credential", "terminal", ["pair_again"], "Доверие браузера недействительно", "Подключите браузер заново с кодом с телефона."),
  pairing_denied: failure("pairing_denied", "terminal", ["pair_again"], "Подключение отклонено", "Начните подключение снова и подтвердите его на телефоне."),
  rate_limited: failure("rate_limited", "recoverable", ["retry"], "Слишком много попыток", "Подождите немного и повторите действие."),
  session_unauthorized: failure("session_unauthorized", "terminal", ["pair_again"], "Браузер больше не подключён", "Подключите браузер заново с кодом с телефона."),
  file_not_approved: failure("file_not_approved", "recoverable", ["select_destination"], "Передача ещё не разрешена", "Подтвердите файл на телефоне и выберите папку назначения."),
  transfer_cancelled: failure("transfer_cancelled", "terminal", ["start_new_operation"], "Передача отменена", "Чтобы отправить файл, создайте новую передачу."),
} as const satisfies Record<string, FailureDescriptor>;

export type KnownFailureCode = keyof typeof failureCatalog;

export function resolveFailure(code: unknown): FailureDescriptor {
  if (typeof code === "string" && code in failureCatalog) {
    return failureCatalog[code as KnownFailureCode];
  }
  return failureCatalog.unknown_error;
}

function failure(
  code: string,
  severity: FailureSeverity,
  recoveryActions: readonly RecoveryActionId[],
  title: string,
  message: string,
): FailureDescriptor {
  return { code, severity, recoveryActions, title, message };
}
