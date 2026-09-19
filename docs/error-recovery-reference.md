# DeviceBridge: error and recovery reference

Этот документ описывает стабильные domain/wire failure codes. Пользовательский
интерфейс показывает локализованный заголовок и действие; интеграционный код
должен принимать решение по `errorCode`, а не разбирать текст `message`.

`RECOVERABLE` означает, что пользователь может исправить причину и повторить
действие. `TERMINAL` относится к текущей операции или session, а не ко всему
приложению: нужно изменить входные данные, создать новую операцию или выполнить
pairing заново.

## Wire contract

Session HTTP error содержит объект `error`; text/file WebSocket errors являются
versioned events. Для новых клиентов стабильным полем является:

```json
{
  "errorCode": "file_insufficient_space",
  "details": {
    "direction": "BROWSER_TO_ANDROID",
    "sizeCategory": "large"
  }
}
```

Protocol-specific `code` сохраняется для совместимости конкретного route/event.
Неизвестный `errorCode` должен отображаться как `unknown_error`, не приводя к
падению клиента.

Допустимые technical details ограничены полями `protocolVersion`,
`operationId`, `direction`, `sizeCategory` и `lifecycleState`. Pairing
code, bearer/trusted credential, payload, raw URI/path и stack trace запрещены.

## Lifecycle и сеть

| errorCode | Класс | Допустимое восстановление |
| --- | --- | --- |
| `unknown_error` | RECOVERABLE | Повторить; при повторении начать операцию заново |
| `local_network_permission_denied` | RECOVERABLE | Запросить разрешение |
| `local_network_permission_revoked` | RECOVERABLE | Открыть Android Settings |
| `no_lan_network` | RECOVERABLE | Подключиться к локальной сети |
| `ambiguous_lan_network` | RECOVERABLE | Оставить одно локальное подключение |
| `network_lost` | RECOVERABLE | Восстановить сеть и запустить сервер явно |
| `address_changed` | RECOVERABLE | Запустить сервер и открыть новый адрес |
| `foreground_start_not_allowed` | RECOVERABLE | Открыть приложение и повторить запуск |
| `server_start_failed` | RECOVERABLE | Проверить сеть и повторить запуск |
| `server_stop_timeout` | RECOVERABLE | Повторить остановку/перезапустить приложение |
| `session_capacity_reached` | RECOVERABLE | Отозвать ненужные browser sessions |
| `server_generation_closed` | TERMINAL | Запустить новый server generation |

## Pairing и session

| errorCode | Класс | Допустимое восстановление |
| --- | --- | --- |
| `pairing_request_expired` | TERMINAL | Получить новый код и выполнить pairing |
| `pairing_request_not_found` | TERMINAL | Создать новый pairing request |
| `invalid_pairing_code` | RECOVERABLE | Проверить актуальный шестизначный код |
| `invalid_trusted_credential` | TERMINAL | Очистить credential и выполнить pairing |
| `pairing_denied` | TERMINAL | Создать новый request и подтвердить на телефоне |
| `rate_limited` | RECOVERABLE | Дождаться окончания блокировки |
| `session_unauthorized` | TERMINAL | Выполнить pairing заново |
| `session_closed` | TERMINAL | Выбрать действующую session/подключиться заново |
| `protocol_version_unsupported` | TERMINAL | Обновить приложение или страницу |
| `invalid_payload` | TERMINAL | Обновить страницу и создать новую операцию |

Обычная транспортная ошибка не должна удалять trusted credential. Credential
удаляется адресно после подтверждённого reject/expiry; событие потери WebSocket
сначала проверяется защищённым status request.

## Текст

| errorCode | Класс | Допустимое восстановление |
| --- | --- | --- |
| `text_connection_lost` | RECOVERABLE | Manual retry неизменённой операции |
| `empty_content` | TERMINAL | Ввести содержимое |
| `content_too_large` | TERMINAL | Сократить текст до 100 КБ |
| `message_conflict` | TERMINAL | Создать новую операцию |
| `message_not_found` | TERMINAL | Начать отправку заново |

Retry неизменённого текста сохраняет `messageId`; после редактирования создаётся
новый идентификатор. Потеря acknowledgement не может автоматически давать статус
`DELIVERED`.

## Файлы

| errorCode | Класс | Допустимое восстановление |
| --- | --- | --- |
| `file_checksum_mismatch` | TERMINAL | Удалить partial output, выбрать исходник заново |
| `file_stream_failed` | RECOVERABLE | Проверить сеть и выполнить manual retry |
| `file_storage_unavailable` | RECOVERABLE | Выбрать доступную папку |
| `file_insufficient_space` | RECOVERABLE | Освободить место/выбрать другое хранилище |
| `file_capacity_reached` | RECOVERABLE | Уменьшить набор/дождаться очереди |
| `file_source_unavailable` | TERMINAL | Выбрать исходный файл заново |
| `invalid_transfer_id` | TERMINAL | Создать новую передачу |
| `invalid_file_name` | TERMINAL | Переименовать/выбрать другой файл |
| `invalid_file_size` | TERMINAL | Выбрать файл с определяемым размером |
| `file_too_large` | TERMINAL | Уменьшить файл/лимит выбора; максимум 1 ГиБ |
| `invalid_checksum` | TERMINAL | Выбрать исходный файл заново |
| `file_size_mismatch` | TERMINAL | Повторно выбрать изменившийся файл |
| `file_not_approved` | RECOVERABLE | Подтвердить на телефоне и выбрать destination |
| `transfer_cancelled` | TERMINAL | Завершить текущую попытку; начать/повторить через UI |

Terminalization для complete/cancel/fail обязана закрыть stream/descriptors,
освободить Wi-Fi lock и slot очереди. Повтор использует исходный `transferId`
только после новой проверки source metadata/destination; partial повреждённый
результат не становится доступным.

## Persistence и Settings

| errorCode | Класс | Допустимое восстановление |
| --- | --- | --- |
| `history_write_failed` | RECOVERABLE | Освободить место и повторить |
| `invalid_device_name` | TERMINAL | Исправить имя и сохранить |
| `invalid_retention_days` | TERMINAL | Указать допустимый срок |
| `invalid_file_limit` | TERMINAL | Указать лимит от 1 байта до 1 ГиБ |

Ошибка чтения History/Settings не должна отображаться как успешный empty/default.
Ошибка сохранения не меняет активное persisted значение; editable draft остаётся
для исправления и ручного retry.

## Observability и privacy

- Логи используют failure code и только allowlisted context.
- User-facing details должны быть безопасны для копирования в issue.
- Progress не является terminal result: `VERIFYING` не равен 100% success.
- Неизвестный future code получает безопасный fallback и не ломает UI.
- Recovery не выполняет повтор text/file/pairing операции автоматически.
