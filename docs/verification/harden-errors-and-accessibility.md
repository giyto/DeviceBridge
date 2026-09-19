# Harden errors and accessibility: browser compatibility matrix

Дата проверки: 19 сентября 2026 года.

## Проверенное окружение

- ОС: Microsoft Windows 11 Home Single Language, версия 10.0.26200, build 26200.
- Google Chrome: 153.0.8010.50.
- Microsoft Edge: 153.0.4234.32.
- Команда: `npm run test:compatibility` из каталога `web`.
- Результат: 6 из 6 сценариев прошли.
Пользователь завершил ручную проверку на реальном Android-устройстве в локальной
сети и подтвердил успешный результат в Chrome и Edge на Windows 11. Windows 10
не проверялась из-за отсутствия доступного окружения; это ограничение сохранено
в матрице явно.

Compatibility gate запускает установленные branded-браузеры через Playwright channels
`chrome` и `msedge`. HTTP и WebSocket ответы изолированы fixtures, поэтому этот
результат подтверждает поведение web-клиента, но не заменяет ручную проверку с
реальным Android-устройством в локальной сети.

## Автоматическая матрица

| Проверка | Ожидаемый результат | Windows 11 Chrome | Windows 11 Edge |
| --- | --- | --- | --- |
| Layout | Нет горизонтального overflow; connection и workspace остаются доступны | PASS | PASS |
| Reload текущей сессии | После обновления используется session token, повторный pairing не появляется | PASS | PASS |
| Trusted reconnect | Без session token сохранённый trusted credential выдаёт новую сессию | PASS | PASS |
| Revoke | 401 для session и trusted credential очищает доверие и возвращает форму pairing | PASS | PASS |
| Text retry | Исправимая ошибка показывает «Повторить»; retry сохраняет messageId и одну карточку | PASS | PASS |
| File cancel/retry | Cancel и retry сохраняют transferId, одну карточку и корректный статус | PASS | PASS |
| Accessible status | Status имеет `role=status`, polite live-region; terminal transfer объявляется | PASS | PASS |
| Keyboard focus | После text retry, file cancel и file retry фокус остаётся в текущей карточке | PASS | PASS |

Во время первого прогона branded-браузеры выявили дефект: после keyboard retry
текста Chrome и Edge сбрасывали фокус, когда кнопка «Повторить» удалялась из DOM.
Фокус теперь восстанавливается повторно после асинхронного завершения операции.
Повторный полный прогон прошёл в обоих браузерах.

## Web regression gate 10.1

| Команда | Фактический результат |
| --- | --- |
| `npm run typecheck` | PASS |
| `npm test` | PASS: 32 test files, 182 tests |
| `npm run build` | PASS: production assets и manifest собраны |
| `npm run test:visual` | PASS: 26 passed, 4 intentional project skips |
| `npm run test:file-transfer-gate` | PASS: 14 passed, включая SHA-256 для 500 MiB в Chrome и Edge |
| `npm run test:compatibility` | PASS: 6 passed в branded Chrome и Edge |

Playwright gates запускались последовательно, потому что стандартный каталог
`test-results` является общим для процессов. File-transfer config ограничен своими
spec-файлами, чтобы один gate не подхватывал suites с другим web server/base URL.
## Android build gate 10.2

- `:app:testDebugUnitTest`: PASS, 458 JVM tests.
- `:app:assembleDebug`: PASS.
- `:app:assembleDebugAndroidTest`: PASS.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `assets/web/web-manifest.json`, `asset-manifest.json` и `index.html` присутствуют
  в APK и byte-for-byte совпадают со свежей production-сборкой.
- В APK присутствуют свежие hashed assets `index-CDF0RrUE.css` и
  `index-FWrdOgVZ.js`.

Во время первого прогона source-contract тест Hilt-графа обнаружил зависимость от
CRLF/LF. Helper нормализован; сам `FileTransferCoordinator` остаётся process
singleton, а повторный полный gate прошёл.
## Android instrumentation gate 10.3

| AVD | Android/API | Фактический результат |
| --- | --- | --- |
| `Pixel_4` (`emulator-5556`) | Android 10 / API 29 | PASS: 106/106, 0 skipped, 0 failed |
| `Pixel_8_API_37_1_DeviceBridge` (`emulator-5554`) | Android 17 / API 37 | PASS: 106/106, 0 skipped, 0 failed |

Каждый прогон выполнялся отдельно через `ANDROID_SERIAL`, поэтому результат одного
эмулятора не мог скрыть сбой второго.
## End-to-end recovery matrix 10.4

В колонке «Автоматически» указано фактическое evidence из зелёных JVM/Ktor/Vitest/
Playwright/instrumentation gates. Ручные пользовательские сценарии подтверждены
на реальном телефоне и браузере в локальной сети. Искусственные terminal cases,
которые небезопасно или непрактично воспроизводить вручную, закрыты контрактными
и integration-тестами.

| Сценарий | Ожидаемая причина | Допустимое действие и отсутствие duplicate | Автоматически | Реальный LAN |
| --- | --- | --- | --- | --- |
| Разные сети / неверный или старый IP | `no_lan_network` или `address_changed`; stale endpoint не показывается | Подключить одну сеть, явно запустить сервер и открыть новый адрес; старая session не переносится | PASS: `CoordinatorNetworkChangeTest`, `ServerLifecycleRecoveryContractTest` | PASS: подтверждено пользователем |
| Local Network permission отозвано | `local_network_permission_revoked` | Открыть Settings, вернуть permission и запустить явно; старый generation закрыт | PASS: lifecycle unit/Compose/instrumentation | PASS: подтверждено пользователем |
| Неверный pairing code | `invalid_pairing_code`, уменьшается attempts budget | Исправить код; второй pending request не создаётся | PASS: `BrowserSessionCoordinatorValidationTest`, `ProtocolFailureContractTest` | PASS: подтверждено пользователем |
| Истёкший code/request | `pairing_request_expired` | Получить новый код; старый request не approve-ится | PASS: coordinator и route contracts | PASS: подтверждено пользователем |
| Approval deny | `pairing_denied` | Создать новый pairing request; deny не создаёт session | PASS: `ProtocolFailureContractTest` | PASS: подтверждено пользователем |
| Approval timeout / потерянный ответ | uncertain, затем pending/expired исходного request | Проверить исходный request вручную; code не отправляется повторно автоматически | PASS: session controller/coordinator recovery tests | PASS: подтверждено пользователем |
| Session/trusted revoke | `session_unauthorized` / `invalid_trusted_credential` | Очистить только отозванную credential и выполнить pairing; text/file не replay-ятся | PASS: Ktor contracts и Chrome/Edge compatibility gate | PASS: подтверждено пользователем |
| Кратковременный connection loss | `network_lost`, `text_connection_lost` или `file_stream_failed` | Bounded reconnect восстанавливает transport; operation retry только по нажатию с тем же id | PASS: reconnect policy и transfer controller tests | PASS: подтверждено пользователем |
| File limit | `file_too_large` | Уменьшить выбор; rejected item не занимает queue slot | PASS: file metadata/controller/routes | PASS: подтверждено пользователем |
| Недостаточно места | `file_insufficient_space` | Освободить место/выбрать destination; failed item освобождает slot | PASS: raw upload/coordinator cleanup tests | Не требуется: destructive case покрыт автоматически |
| Checksum mismatch | `file_checksum_mismatch` | Partial output удалён/не публикуется; выбрать source заново, duplicate history нет | PASS: protocol/processor/terminalization tests | Не требуется: corruption case покрыт автоматически |
| Destination недоступен / picker cancel | `file_storage_unavailable` либо awaiting destination | Выбрать папку снова; cancel picker сохраняет pending offer и тот же transferId | PASS: SAF/settings/file ViewModel tests | PASS: подтверждено пользователем |
| Unsupported protocol | `protocol_version_unsupported` | Обновить клиент; session/operation не создаётся | PASS: protocol contracts и manifest client tests | Не требуется: compatibility case покрыт автоматически |
## Ручная матрица с реальным телефоном

Статусы ниже намеренно не заменены автоматическими результатами.

| Проверка | Ожидаемый результат | Windows 11 Chrome | Windows 11 Edge | Windows 10 Chrome | Windows 10 Edge |
| --- | --- | --- | --- | --- | --- |
| Layout и zoom 200% | Контент читаем, primary actions доступны, горизонтальной прокрутки страницы нет | PASS | PASS | Нет окружения | Нет окружения |
| Reload/reconnect | После F5 активная сессия восстанавливается без повторной отправки операции | PASS | PASS | Нет окружения | Нет окружения |
| Trusted reconnect | После полного закрытия браузера доступ восстанавливается без кода | PASS | PASS | Нет окружения | Нет окружения |
| Revoke | Отзыв на телефоне завершает сессию; обновление требует новый pairing | PASS | PASS | Нет окружения | Нет окружения |
| Text retry | После временного разрыва manual retry доставляет текст один раз | PASS | PASS | Нет окружения | Нет окружения |
| File cancel/retry | Cancel освобождает очередь; retry не создаёт дубликат и передача завершается | PASS | PASS | Нет окружения | Нет окружения |
| Accessible status | Tab-порядок логичен, focus ring виден, screen reader сообщает переходы | PASS | PASS | Нет окружения | Нет окружения |

## Порядок ручной проверки

Для каждого браузера:

1. Открыть адрес DeviceBridge с телефона в доверенной локальной сети и выполнить pairing.
2. Проверить обычный layout, масштаб браузера 200% и отсутствие горизонтального overflow.
3. Обновить страницу и убедиться, что сессия восстановилась без повторной операции.
4. Включить «Запомнить этот браузер», закрыть браузер полностью и открыть адрес снова.
5. Отозвать доверенный браузер на телефоне и проверить возврат к pairing.
6. Временно оборвать связь во время отправки текста, восстановить сеть и нажать «Повторить».
7. Начать передачу файла, нажать «Отменить», затем «Повторить»; проверить отсутствие дубликата.
8. Повторить ключевые действия клавиатурой и проверить видимый фокус и status announcements.

Windows 11 часть задачи 9.10 подтверждена вручную. Windows 10 часть остаётся
непроверенной из-за отсутствия окружения и является явным ограничением evidence
этого архивируемого change.
