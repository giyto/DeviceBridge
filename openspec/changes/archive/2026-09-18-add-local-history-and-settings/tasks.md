## 1. Persistence foundation

- [x] 1.1 Добавить совместимые Room, Room KTX и Preferences DataStore зависимости в version catalog/app и проверить `.\gradlew.bat :app:dependencies :app:compileDebugKotlin`.
- [x] 1.2 Определить domain-модели и небольшие `HistoryRepository`, `SettingsRepository`, `TrustedBrowserRepository` interfaces без Android/Room типов и проверить их JVM compilation.
- [x] 1.3 Создать Room entities/DAO/database версии 1 для `history_records` и `trusted_browsers` со stable string enums и unique operation index; проверить DAO instrumented tests на insert/query/conflict.
- [x] 1.4 Реализовать entity-domain mappers с безопасными nullable file/text полями и проверить round-trip unit tests для всех типов, направлений и terminal statuses.
- [x] 1.5 Создать Preferences DataStore keys и repository для device name, retention days, destination URI и effective file limit; проверить defaults и сохранение в repository tests.
- [x] 1.6 Подключить database, DataStore, repositories и application scopes через Hilt, проверить запуск/пересоздание Activity без duplicate singleton instances.

## 2. Local history recording

- [x] 2.1 Реализовать идемпотентную запись terminal history по `operationId + kind` и проверить, что повторный callback не создаёт вторую record.
- [x] 2.2 Реализовать plain-text preview максимум 200 Unicode code points без HTML-интерпретации и проверить emoji, surrogate pairs, multiline и 100-КБ input unit tests.
- [x] 2.3 Подключить text/link terminal results к history recorder и проверить delivered/failed records без полного payload и без повторной отправки при storage error.
- [x] 2.4 Подключить file completed/cancelled/failed results к history recorder и проверить сохранение metadata/checksum без URI, path, grant и file bytes.
- [x] 2.5 Изолировать history write failure от фактического transfer result и показать отдельный безопасный persistence event; проверить fake repository failure tests.
- [x] 2.6 Реализовать retention cleanup при старте repository и после insert для диапазона 1–365 дней, проверить UTC boundary и сохранение новых records.
- [x] 2.7 Реализовать delete-one и clear-all так, чтобы они не вызывали file/storage operations и не отменяли active transfers; проверить repository integration tests.

## 3. Android history UI

- [x] 3.1 Создать History use cases, `HistoryViewModel`, filters и saved UI state; проверить reducer/ViewModel tests для loading, content, empty и error.
- [x] 3.2 Заменить placeholder `HistoryScreen` реальным списком от новых к старым и проверить Compose test с text/link/file records.
- [x] 3.3 Добавить доступные filters по направлению, типу и status, проверить их комбинации и empty-filter state в Compose tests.
- [x] 3.4 Добавить экран/диалог деталей с безопасными metadata без ложного действия открытия недоступного файла и проверить content descriptions.
- [x] 3.5 Добавить подтверждаемое удаление одной record и полной истории, проверить success/failure states и отсутствие optimistic false success.

## 4. Settings and SAF destination

- [x] 4.1 Реализовать validation device name, retention period и file limit 1..1 ГиБ, проверить invalid input не заменяет последнее допустимое значение.
- [x] 4.2 Создать Settings use cases и `SettingsViewModel` с отдельными saving/error states, проверить process recreation через repository-backed state.
- [x] 4.3 Заменить placeholder `SettingsScreen` controls для имени, retention, file limit, destination и trusted browsers; проверить font scale, light/dark theme и keyboard/accessibility semantics.
- [x] 4.4 Реализовать выбор default tree URI через SAF с `takePersistableUriPermission`, проверить cancel сохраняет прежнюю destination.
- [x] 4.5 Проверять доступность сохранённой destination перед входящим payload и возвращать picker flow при revoke/provider error; проверить fake ContentResolver tests.
- [x] 4.6 Реализовать выбор «использовать текущую папку / изменить» для incoming offer и обновлять default только после отдельного согласия; проверить Compose flow tests.

## 5. Effective file limit

- [x] 5.1 Передать snapshot effective limit из Settings use case в Android selection и server `FileMetadataValidator`, всегда clamp к hard limit; проверить boundary 0, limit и limit+1.
- [x] 5.2 Добавить effective limit в авторизованный status/capability DTO без публикации остальных settings и проверить serialization/route tests.
- [x] 5.3 Применить полученный limit в browser draft validation и понятном сообщении, сохранив server как окончательный authority; проверить web unit tests.
- [x] 5.4 Зафиксировать validation snapshot для уже принятого active transfer и проверить, что изменение setting влияет только на новые drafts/offers.

## 6. Trusted browser storage and Android session flow

- [x] 6.1 Реализовать генерацию raw trusted credential и Keystore-backed HMAC verifier с constant-time comparison; проверить валидный, неверный и изменённый token security tests.
- [x] 6.2 Реализовать TrustedBrowser repository с metadata, expiry максимум 30 дней, last-used и cleanup, проверить expiry и отсутствие raw secret в Room.
- [x] 6.3 Расширить pairing protocol полем `rememberBrowserRequested` и результатом optional credential, проверить backward-compatible serialization и size/version validation.
- [x] 6.4 Добавить Android decisions `AllowOnce`, `AllowAndRemember`, `Reject` и явный текст trust request в pending UI; проверить один request не создаёт две sessions/records.
- [x] 6.5 Реализовать trusted exchange в `BrowserSessionCoordinator`, создающий новый generation-scoped session с `trustedBrowserId`; проверить credential не работает как bearer token.
- [x] 6.6 Реализовать expiry/revoke-one/revoke-all с закрытием только производных sessions/WebSockets и проверить остальные ordinary/trusted sessions продолжают работу.
- [x] 6.7 Добавить same-origin `POST /api/v1/session/trusted` с Host/Origin, payload и rate limits; проверить success, expired, revoked, malformed и cross-origin integration cases.
- [x] 6.8 Добавить trusted browser list/revoke controls в Android Settings и проверить metadata отображаются без секрета, а UI меняется только после repository result.

## 7. Browser trusted reconnect

- [x] 7.1 Создать versioned `BrowserTrustedCredentialStore` отдельно от session token store, проверить `localStorage` validation/save/read/clear tests.
- [x] 7.2 Расширить session API client для trust request и trusted exchange без token в URL/cookie/DOM, проверить HTTP payload и error mapping tests.
- [x] 7.3 Обновить startup recovery в session controller: sessionStorage → однократный trusted exchange → pairing, проверить revoked/expired очищает credential, а network error не создаёт retry loop.
- [x] 7.4 Добавить доступную опцию «Запомнить этот браузер» и состояния Android approval в pairing UI, проверить обычный pairing не создаёт persistent credential.
- [x] 7.5 Проверить reload/browser restart/new server generation: trusted browser восстанавливается без кода, а очищенный site data возвращается к pairing.

## 8. Android editable file draft

- [x] 8.1 Ввести draft model/id и source lease без `transferId`, изменить selection preparer и проверить ни один draft item не появляется в executable queue.
- [x] 8.2 Реализовать append и безопасный dedupe повторных picker/share items, проверить разные files с одинаковым именем не объединяются ошибочно.
- [x] 8.3 Добавить `RemoveDraftItem` и `ClearDraft` actions с освобождением staged source, проверить исходный Content URI/file никогда не удаляется.
- [x] 8.4 Сохранить текущий draft при cancel повторного picker и проверить после cancel можно добавить или отправить оставшиеся items.
- [x] 8.5 Перенести source ownership и выдать transferId только при confirm; проверить accepted items уходят в FIFO, rejected остаются draft с error и повторный action не дублирует batch.
- [x] 8.6 Добавить в Compose preview доступные действия удаления каждого item, «Очистить» и «Добавить файлы», проверить send disabled для пустого draft.
- [x] 8.7 Реализовать cleanup orphan staged files при remove, terminal result, server stop и следующем app start; проверить private no-backup directory не накапливает sources.

## 9. Browser editable file draft

- [x] 9.1 Заменить replace-selection на stable `DraftFile` state и методы add/remove/clear с безопасным dedupe, проверить input и drag-and-drop дают одинаковый результат.
- [x] 9.2 Сохранять draft при cancel file dialog и сбрасывать input value после change, проверить удалённый ранее file можно выбрать снова.
- [x] 9.3 Удалять из draft только принятые server offers, оставляя rejected items с error, проверить partial confirm и повтор не создают duplicate transfer.
- [x] 9.4 Добавить доступные remove/clear/add controls и disabled empty confirm в web view, проверить Tab focus, screen-reader names и viewport 360/768/1920.
- [x] 9.5 Расширить Vitest и Playwright file gate сценариями multiple selection, remove one, clear all, cancel dialog и choose-after-remove в Chrome/Edge.

## 10. DeviceBridge launcher icon

- [x] 10.1 Создать оригинальный repo-native знак DeviceBridge «два устройства + bridge/link» в adaptive safe zone и проверить читаемость на малом launcher size.
- [x] 10.2 Заменить template foreground/background и legacy launcher resources, добавить monochrome layer для themed icons и проверить manifest/resource merge.
- [x] 10.3 Проверить app label `DeviceBridge`, round/standard/system masks и отсутствие внешних image resources через Android resource tests/lint.
- [x] 10.4 Выполнить ручную проверку установленной иконки на API 29 и API 37.1, включая themed icon на поддерживаемом launcher, и сохранить результат в verification doc.

## 11. Privacy, backup and failure isolation

- [x] 11.1 Исключить Room database, DataStore, trusted material и staged sources из cloud backup/device transfer rules; проверить merged rules для debug/release.
- [x] 11.2 Проверить production route policy: history/settings/trusted-management LAN routes возвращают 404, а только trusted exchange публикуется по контракту.
- [x] 11.3 Добавить tests, что logs, errors, notifications и UI не содержат raw trusted credential, session token, full text, source URI или filesystem path.
- [x] 11.4 Изолировать database open/query failure от server lifecycle и transfer coordinators; проверить приложение запускается, transfer работает, а History показывает recoverable error.
- [x] 11.5 Проверить web CSP/offline policy после добавления localStorage flow: нет external requests/scripts, user content остаётся plain text.

## 12. Integrated verification and documentation

- [x] 12.1 Запустить `.\gradlew.bat :app:testDebugUnitTest` и исправить все Android JVM regressions.
- [x] 12.2 Запустить `npm test --prefix web`, `npm run typecheck --prefix web` и `npm run build --prefix web`, проверить все suites и deterministic bundled assets.
- [x] 12.3 Запустить `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest` и проверить Room schemas/resources/web assets упакованы без stale output.
- [x] 12.4 Запустить instrumented/Compose tests на API 29 и API 37.1 для history/settings/SAF/trust/draft flows и зафиксировать результаты.
- [x] 12.5 Повторить streaming/file regression, включая 500-МБ файл, cancel/retry и text во время transfer, проверить persistence и settings не увеличивают memory пропорционально payload.
- [x] 12.6 Провести ручную матрицу Android ↔ Chrome/Edge: history, filters/delete, settings restart, trusted reconnect/revoke, default folder, limit, multiple draft remove/clear/reselect и icon; зафиксировать acceptance в `docs/verification`.
- [x] 12.7 Выполнить `openspec validate add-local-history-and-settings --strict`, сверить все требования с ТЗ и отметить задачи выполненными только после автоматической и пользовательской ручной приёмки.

  Строгая валидация, сверка с ТЗ и пользовательская ручная приёмка выполнены 2026-09-18.
