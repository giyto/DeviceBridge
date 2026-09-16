## Why

После завершения защищённой browser session и обмена текстом DeviceBridge всё ещё не решает вторую основную ежедневную задачу MVP — передачу одного или нескольких файлов между телефоном и компьютером без облака, USB и отдельного desktop-приложения. Следующий этап из ТЗ 2.0 и roadmap должен добавить двустороннюю потоковую передачу с контролируемой памятью, очередью, прогрессом, отменой и проверкой SHA-256.

## What Changes

- Добавить Android → Browser и Browser → Android передачу одного или нескольких файлов только внутри действующей авторизованной browser session.
- Ввести версионированный file protocol: предложение файла, потоковый upload/download, progress events, cancellation, terminal result и SHA-256 verification.
- Ограничить выполнение одной активной файловой операцией в каждом направлении; остальные элементы держать в наблюдаемой очереди, не блокируя text transfer.
- Добавить Android file flow с Storage Access Framework, явным выбором файлов и папки назначения, поддержкой ACTION_SEND/ACTION_SEND_MULTIPLE, безопасным разрешением конфликтов имён и открытием результата.
- Добавить browser file flow с input multiple, drag-and-drop, списком предложений, прогрессом, отменой и явной проверкой скачанного файла.
- Разрешить файлы от 0 байт до фиксированного максимума 1 ГиБ на один item; гарантировать обязательную проверку 500 МБ, потоковую обработку без буферизации полного содержимого, cleanup partial output и освобождение ресурсов после cancellation, disconnect, revoke или server stop.
- Обновить foreground notification и production route policy только для завершённых file endpoints; история, настройки, trusted browsers, автоматический retry и фоновая передача без открытой browser tab остаются за границами change.
- Зафиксировать ограничение LAN HTTP: File System Access API недоступен как обязательная основа сохранения, поэтому Android → Browser использует нативную загрузку браузера и отдельное явное повторное открытие скачанного файла для потоковой SHA-256 verification. Реализация MUST пройти Chromium integration gate до признания change завершённым.

## Capabilities

### New Capabilities

- file-transfer: двусторонний потоковый file protocol, очередь, прогресс, отмена, SHA-256, SAF, безопасные имена и lifecycle файловых операций.

### Modified Capabilities

- android-app-shell: действие «Файлы», Android file flow, Share Target и карточки активных передач становятся доступными для выбранной browser session.
- browser-web-interface: web shell получает доступные upload/download flows, drag-and-drop, очередь и проверяемые состояния передачи.
- secure-browser-session: file routes и одноразовый download grant привязываются к конкретной активной session и не расширяют её полномочия.
- server-lifecycle: production server публикует завершённые file routes, отражает активную файловую операцию, управляет Wi-Fi lock и гарантированно отменяет/очищает transfer resources.

## Impact

- Android: новые domain models/repository/use cases, file coordinator, SAF adapters, Compose file flow, Share Target parsing, notification state и Hilt bindings.
- Server/API: защищённые POST /api/v1/files, GET /api/v1/files/{id}, DELETE /api/v1/transfers/{id}, file events и ограниченный одноразовый download grant.
- Web: file selection/drag-and-drop, XHR upload progress, native download handoff, incremental SHA-256 verification и transfer UI.
- Dependencies: сохраняются Ktor 3.5.2/CIO, Kotlin Coroutines, Kotlinx Serialization и нативный TypeScript stack; допустима небольшая проверяемая incremental SHA-256 реализация без runtime CDN и без service worker.
- Verification: unit, server integration, web, Compose/instrumented и ручная LAN-матрица API 29/API 37.1, Chrome/Edge, 0 байт, 500 МБ, отказ свыше 1 ГиБ, multiple files, cancellation, disconnect и checksum mismatch.
