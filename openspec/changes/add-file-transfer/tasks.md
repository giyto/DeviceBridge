## 1. Browser feasibility gates

- [x] 1.1 Добавить изолированный Chromium integration fixture для нативной LAN HTTP загрузки по одноразовому grant; проверить Playwright-тестом, что download создаёт файл без Blob/ArrayBuffer и не разрывает основную session page.
- [x] 1.2 Автоматизировать повторный выбор скачанного файла и checksum acknowledgement в Chrome fixture; проверить success, wrong file, expired grant и reused grant до начала production implementation.
- [x] 1.3 Выбрать bundled incremental SHA-256 adapter для web после license, bundle-size и memory benchmark; проверить standard vectors, разные границы chunks и deterministic 500 МБ fixture без пропорционального роста JS heap.
- [x] 1.4 Зафиксировать результат feasibility gate в docs/verification/file-transfer.md; если native download или bounded SHA-256 не проходят, остановить change и описать требуемый пересмотр ТЗ вместо ослабления требований.

## 2. File protocol и domain

- [x] 2.1 Сначала добавить failing unit-тесты file protocol DTO для offer, upload-ready, progress, download grant, verification, cancellation, snapshot и ошибок; затем реализовать ограниченные Kotlinx Serialization models с protocolVersion, messageId, transferId и timestamp.
- [x] 2.2 Сначала покрыть тестами допустимые и запрещённые переходы queued/connecting/transferring/verifying/terminal; затем реализовать immutable domain state и reducer с монотонными bytes и необратимым terminal state.
- [x] 2.3 Реализовать file metadata validator с диапазоном 0..1 073 741 824 байта включительно; проверить unit-тестами 0 байт, 500 МБ, ровно 1 ГиБ, 1 ГиБ + 1 байт, MIME fallback, batch bounds, malformed identifiers и несоответствие фактического размера.
- [x] 2.4 Реализовать filename normalizer и collision resolver; проверить тестами path traversal, separators, dot segments, control/bidi symbols, пустое/длинное имя, extension и уникальные name (N).ext без перезаписи.
- [x] 2.5 Определить FileTransferRepository и use cases для observe/create/approve/cancel/retry/verify; проверить architecture-тестом, что domain не зависит от Ktor, Compose, ContentResolver или Android service.
- [x] 2.6 Реализовать two-direction FIFO scheduler и проверить coroutine-тестами одну active operation на направление, стабильный порядок multiple files, одновременные разные направления и независимый text flow.

## 3. Coordinator, ownership и event transport

- [x] 3.1 Реализовать process-wide FileTransferCoordinator для текущего server generation; проверить тестами создание items, bounded metadata, session ownership, idempotent messageId и конфликтующий replay.
- [x] 3.2 Обобщить text-only event hub в единый bounded per-session dispatcher; проверить тестами ordering, progress coalescing, приоритет terminal/control events, slow consumer и совместимость существующего text protocol.
- [x] 3.3 Реализовать file snapshot текущей session и проверить тестами refresh без дубликатов, отсутствие чужих items и запрет автоматического возобновления оборванного payload.
- [x] 3.4 Реализовать random single-use DownloadGrantRegistry с TTL 30 секунд и scope session + transfer + generation; проверить expiry, replay, revoke, stop и отсутствие session bearer в download URL.
- [x] 3.5 Реализовать cancellation/cleanup orchestration и проверить тестами queued cancel, active cancel, disconnect, session revoke, network error и server stop с закрытием jobs/streams и запуском следующего queued item.

## 4. Android storage и streaming adapters

- [x] 4.1 Реализовать Android source picker gateway на OpenMultipleDocuments и metadata reader; проверить instrumented fake-contract тестами multiple URI, size/MIME/name, unavailable provider и отсутствие broad storage permission.
- [x] 4.2 Расширить Share Target parser для ACTION_SEND и ACTION_SEND_MULTIPLE content URI; проверить тестами preview без auto-send, mixed valid/invalid URI и сохранение существующего text/plain flow.
- [x] 4.3 Реализовать destination gateway на ACTION_OPEN_DOCUMENT_TREE и scoped URI lease; проверить instrumented тестами approve, cancel, revoked folder, release lease и отсутствие скрытого persistent setting.
- [x] 4.4 Реализовать потоковое создание unique partial document и finalization; проверить provider-fake тестами collision, successful rename, delete on cancel/error и явный partial marker при невозможности delete.
- [x] 4.5 Реализовать Android chunked copy + MessageDigest adapter на Dispatchers.IO; проверить unit/instrumented тестами size, SHA-256, progress, cancellation и bounded buffer на deterministic stream.
- [x] 4.6 Реализовать fallback для непереносимого share URI через открытый descriptor либо no-backup streaming stage; проверить cleanup, недостаток места, permission loss и отсутствие payload в backup paths.
- [x] 4.7 Реализовать безопасное открытие completed content URI с временным read grant; проверить intent-тестами completed-only action, отсутствующий viewer и запрет file:// URI.

## 5. Production HTTP/WebSocket API

- [x] 5.1 Добавить защищённый POST /api/v1/files для batch offers; проверить Ktor integration-тестами authorization, schema/size bounds, idempotency, ownership и отсутствие output до Android approval.
- [x] 5.2 Добавить raw POST /api/v1/files/{transferId} upload stream; проверить 401/403/404, content length, premature EOF, oversize, cancellation, incremental SHA-256 и partial cleanup.
- [x] 5.3 Добавить POST download-grant и потоковый GET /api/v1/files/{transferId}; проверить safe Content-Disposition, no-referrer, grant TTL/replay, revoke, bytes progress и закрытие source descriptor при disconnect.
- [x] 5.4 Добавить verify acknowledgement и DELETE /api/v1/transfers/{id}; проверить size/SHA match, mismatch, duplicate verification, queued/active cancellation и запрет действий чужой session.
- [x] 5.5 Расширить events WebSocket file offers/progress/snapshot/terminal events; проверить совместную доставку text во время file stream, reconnect snapshot и bounded event rate.
- [x] 5.6 Обновить production route policy deny-by-default; проверить release/negative tests, что file routes открыты только по контракту, diagnostics/history/settings/trusted-browser routes остаются 404 и filesystem URI/path не раскрываются.

## 6. Android file flow

- [x] 6.1 Добавить File destination в Navigation Compose и включать quick action только при active browser session; проверить Compose-тестами stopped/no-session/single-session/multiple-session состояния.
- [x] 6.2 Реализовать FileViewModel с UiState/UiAction поверх use cases; проверить unit-тестами selection preview, explicit recipient, confirm, incoming approval, picker cancellation, progress, retry и session loss.
- [x] 6.3 Реализовать Compose FileScreen для выбора, preview, incoming offers и transfer cards; проверить Compose-тестами multiple items, states, bytes/percent/speed, cancel/retry/open и font scale без обрезания основных действий.
- [x] 6.4 Подключить ACTION_SEND/ACTION_SEND_MULTIPLE к file preview через navigation deep link/intent handling; проверить instrumented тестами отсутствие auto-send и обязательное подтверждение recipient.
- [x] 6.5 Связать destination picker с incoming batch и показывать недостаток места, revoked folder, checksum mismatch и partial cleanup понятными сообщениями; проверить UI-тестами все error states без stack traces.
- [x] 6.6 Обновить Home active transfers и foreground notification; проверить тестами безопасное имя, направление/progress, отсутствие content/path/checksum и корректный возврат к обычному server state.

## 7. Web file flow

- [x] 7.1 Добавить TypeScript file protocol types и API client для offer, grant, verify, cancel и snapshot; проверить Vitest-тестами Authorization, strict response validation, session loss и safe errors.
- [x] 7.2 Реализовать XHR raw upload adapter с progress/abort и File body; проверить Vitest/browser fixture тестами monotonic bytes, network error, cancellation и отсутствие ложного completed до server terminal event.
- [x] 7.3 Реализовать native download handoff по одноразовому grant; проверить Chrome/Edge integration-тестами attachment filename, expired/reused grant, server disconnect и отсутствие bearer token в URL/history.
- [x] 7.4 Реализовать chunked verification выбранного downloaded file через StreamingSha256; проверить size mismatch, checksum mismatch, cancellation, 500 МБ memory bound и acknowledgement только после match.
- [x] 7.5 Реализовать FileTransferController с two-direction queue state, snapshot deduplication и explicit retry; проверить Vitest-тестами multiple files, refresh, revoke, protocol error и text availability во время transfer.
- [x] 7.6 Реализовать file input multiple, drag-and-drop и preview на общем validation path; проверить DOM-тестами keyboard alternative, unsupported/oversize items и отсутствие auto-upload.
- [x] 7.7 Реализовать incoming offers и transfer cards с progress, speed, cancel, retry, download и verify actions; проверить DOM-тестами все states и отсутствие HTML interpretation в filenames/errors.
- [x] 7.8 Обновить responsive/light/dark styles и accessibility announcements; проверить contract tests на viewport 360/1920, focus visibility, accessible names, aria-live без progress spam и отсутствие горизонтальной прокрутки основных действий.

## 8. Lifecycle, DI и resource safety

- [x] 8.1 Подключить coordinator, storage adapters, dispatcher и repository через Hilt scopes текущей архитектуры; проверить HiltGraphContractTest и unit-тестом единственный instance на server generation.
- [x] 8.2 Встроить file cleanup в start/stop/error ordering ServerLifecycleCoordinator; проверить тестами запрет новых offers, cancellation до socket close, очистку queues/grants/partial resources и чистый следующий generation.
- [x] 8.3 Реализовать reference-counted Wi-Fi lock adapter только для transferring; проверить fake-тестами acquire первого stream, одновременные направления, release последнего, cancellation/error/stop и отсутствие lock в queued/verifying.
- [x] 8.4 Проверить resource leak contract повторными start/upload/download/cancel/stop циклами; подтвердить освобождение ports, descriptors, coroutine jobs, temporary files и Wi-Fi lock.

## 9. Automated verification

- [x] 9.1 Запустить npm test и npm run build; зафиксировать число suites/tests и проверить, что production assets не содержат CDN, service worker или external runtime requests.
- [x] 9.2 Запустить Gradle unit tests и assembleDebug; подтвердить существующие text/session/lifecycle regressions и новые protocol/storage/server/UI tests.
- [x] 9.3 Запустить connectedDebugAndroidTest на API 29 и API 37.1; подтвердить picker/share/navigation/notification flows без version-specific permission regression.
- [x] 9.4 Выполнить deterministic 500 МБ Browser → Android и Android → Browser instrumentation на API 29/API 37.1, отдельную 0-byte передачу и boundary rejection свыше 1 ГиБ; зафиксировать SHA-256, peak/retained memory, elapsed time и UI responsiveness.
- [x] 9.5 Проверить cancellation на queued/transferring/verifying, browser disconnect, session revoke, network loss, server stop, size mismatch, checksum mismatch, insufficient storage и inaccessible folder; подтвердить отсутствие ложного completed и recoverable следующий transfer.
- [x] 9.6 Выполнить security/privacy review route matrix; подтвердить ownership isolation, grant entropy/TTL/single-use, no token/path/content logs, no external endpoints и сохранение LAN HTTP warning.

## 10. Manual acceptance и завершение OpenSpec

- [ ] 10.1 На физическом телефоне и компьютере проверить в Chrome и Edge single/multiple uploads/downloads, drag-and-drop, Android picker, ACTION_SEND/ACTION_SEND_MULTIPLE, progress, speed, cancel, retry, open file и manual checksum verification.
- [ ] 10.2 Проверить при активной file transfer двусторонний text flow, несколько browser sessions, refresh текущей вкладки, revoke, остановку/перезапуск server и отсутствие доступа из чужой session.
- [ ] 10.3 Проверить keyboard-only, screen reader announcements, light/dark theme, viewport 360–1920 и крупный системный шрифт; записать результаты и известные ограничения в docs/verification/file-transfer.md.
- [ ] 10.4 После подтверждённой ручной приёмки отметить выполненные tasks, запустить openspec validate add-file-transfer --strict, синхронизировать main specs и архивировать change через OpenSpec archive workflow.
