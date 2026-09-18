## 1. Failure inventory и contracts

- [ ] 1.1 Составить в коде исчерпывающую таблицу источников lifecycle/session/text/file/persistence ошибок к требуемым stable failure codes и recovery actions; проверить unit-тестом, что каждый известный source case имеет mapping и только неизвестный case даёт `unknown_error`.
- [ ] 1.2 Добавить failing JVM tests для recoverable/terminal classification и privacy allowlist; проверить, что тестовые pairing code, bearer/trusted tokens, payload, raw URI/path и stack trace отсутствуют в user-facing details и logs.
- [ ] 1.3 Добавить failing Ktor contract tests для стабильного `errorCode` и безопасных optional details; проверить ответы invalid/expired pairing, unauthorized/revoked session, version mismatch, file limit и checksum failure без секретов.
- [ ] 1.4 Зафиксировать web contract fixtures для всех wire failure codes и forward-compatible unknown code; проверить Vitest-тестом ожидаемые severity и recovery actions.

## 2. Общая error/recovery модель

- [ ] 2.1 Реализовать domain `FailureCode`, severity/recoverability, `RecoveryAction`, allowlisted context и `UserFacingFailure`; проверить прохождение classification/privacy unit tests из раздела 1.
- [ ] 2.2 Реализовать feature-to-failure mappers для lifecycle, session, text, file и persistence без Android Resources в domain; проверить исчерпывающие mapping tests и отсутствие generic network error для известных причин.
- [ ] 2.3 Добавить protocol error DTO/mappers в Ktor routes с безопасным fallback для неизвестного кода; проверить Ktor contract tests и совместимость существующих success responses.
- [ ] 2.4 Реализовать web failure catalog и отображаемые русские сообщения отдельно от wire DTO; проверить Vitest-тестом одинаковые code/severity/action contracts между fixtures и view model.
- [ ] 2.5 Добавить reusable Android и web technical-details presentation с copy-safe санитизированным содержимым; проверить UI/DOM тестами, что details можно открыть с клавиатуры/TalkBack и в них нет тестовых секретов.

## 3. Server lifecycle recovery

- [ ] 3.1 Добавить reducer/coordinator tests для permission denied/revoked, отсутствия endpoint, bind/start failure, network loss и IP change; проверить distinct failure code, resource cleanup и отсутствие automatic restart.
- [ ] 3.2 Обновить lifecycle repository/coordinator так, чтобы failed/stop transition очищал stale endpoint и завершал sessions/operations старого generation; проверить unit/integration tests с delayed stale callbacks.
- [ ] 3.3 Реализовать явные recovery intents «Запросить разрешение», «Открыть настройки» и «Запустить снова», причём новый generation создаётся только через `StartServerUseCase`; проверить ViewModel tests и повторные start/stop cycles.
- [ ] 3.4 Обновить Home state для distinct lifecycle failures и актуальных connection instructions; проверить Compose tests для API-веток 29/37.1 и отсутствие stale address после смены сети.

## 4. Pairing, session и bounded reconnect

- [ ] 4.1 Добавить session coordinator/route tests для pending approval, approval timeout/deny, rate limit, session capacity, revoked session/trusted credential и protocol mismatch; проверить stable codes, privacy и отсутствие duplicate pairing request.
- [ ] 4.2 Реализовать uncertain pairing state и status recovery исходного request без автоматической повторной отправки code; проверить Ktor/Vitest integration test с разрывом после submit.
- [ ] 4.3 Добавить детерминированный reconnect policy с ограниченным exponential schedule, jitter seam, attempt/generation guard и terminal classification; проверить fake-clock Vitest tests для success, exhaustion, stop и revoke races.
- [ ] 4.4 Подключить bounded reconnect к web event channel с authoritative snapshot после успеха; проверить, что reconnect не вызывает pairing submit, text send, file offer/upload/download или trusted credential deletion при обычной сетевой ошибке.
- [ ] 4.5 Реализовать browser recovery UI для offline, needs-user-action и revoked/expired credential; проверить, что terminal credential удаляется адресно, draft сохраняется, а pairing запрашивается только после действия пользователя.

## 5. Text и link recovery

- [ ] 5.1 Добавить tests для text states draft/sending/uncertain/delivered/failed и manual retry; проверить, что network loss до acknowledgement не создаёт delivered state.
- [ ] 5.2 Реализовать сохранение исходного `messageId` при retry неизменённого payload и новый id после редактирования; проверить Android/Ktor/Vitest integration tests без duplicate item и duplicate history record.
- [ ] 5.3 Разделить recovery для session loss, empty/oversize payload, unsupported link и protocol mismatch; проверить UI tests, что retry доступен только для исправимой неизменённой операции.
- [ ] 5.4 Сохранить безопасный text draft при Activity recreation и browser reconnect только в текущем generation/tab; проверить recreation/reload tests и очистку после success, discard и lifecycle stop.

## 6. File queue, cancellation и retry

- [ ] 6.1 Добавить reducer/scheduler tests для network failure, insufficient space, inaccessible destination, checksum mismatch, file limit, source loss, session revoke и protocol mismatch; проверить distinct terminal reasons и освобождение queue slot.
- [ ] 6.2 Свести complete/cancel/fail в единый terminalization path, закрывающий streams/descriptors, Wi-Fi lock и scheduler slot; проверить unit tests, что второй queued item продолжает работу после failure/cancel первого.
- [ ] 6.3 Реализовать cleanup partial output и недоступность повреждённого результата после checksum/space/network failure; проверить fake provider tests для успешного удаления и provider, не поддерживающего удаление.
- [ ] 6.4 Реализовать manual retry внутри исходного `transferId` после повторной проверки source metadata/destination; проверить tests для idempotent retry, source change terminal result и отсутствия duplicate queue/history item.
- [ ] 6.5 Сохранить offer/draft в `awaiting_destination` после отмены SAF picker и terminalize только по явной отмене или истечению source/session; проверить Compose/integration tests повторного открытия picker.
- [ ] 6.6 Обновить progress model: неопределённый этап вместо ложных 100%, terminal state не возвращается в active; проверить reducer и Android/web rendering tests для transferring/verifying/completed/cancelled/failed.

## 7. History, Settings и SAF recovery

- [ ] 7.1 Добавить repository/ViewModel tests, различающие initial loading, successful empty/content и read failure для History/Settings; проверить, что storage error не превращается в empty/default success.
- [ ] 7.2 Реализовать явные persistence results и retry intents для History/Settings; проверить UI tests error→loading→content/empty и отсутствие duplicate writes.
- [ ] 7.3 Разделить persisted settings и editable draft, сохраняя active value при failed save; проверить DataStore/ViewModel tests для failure, retry и recreation.
- [ ] 7.4 Валидировать persistable SAF grant и доступ до активации tree URI, а revocation переводить destination в unavailable без очистки history/trust/settings; проверить fake ContentResolver и instrumentation tests.
- [ ] 7.5 Связать pending upload offer с повторным выбором destination без duplicate transfer; проверить integration test revoke→picker cancel→new folder→manual continue.

## 8. Android visual system, screens и accessibility

- [ ] 8.1 Зафиксировать semantic Material 3 tokens для light/dark colors, typography, spacing 4/8, shapes, borders/elevation, focus и status meanings; проверить theme unit/Compose tests и отсутствие внешних UI-ресурсов.
- [ ] 8.2 Реализовать reusable Compose `StateSurface`, `FailureCard`, action hierarchy, empty/loading placeholders и operational card primitives; проверить semantics и screenshot tests для default/pressed/focused/disabled/loading/error/success/cancelled states.
- [ ] 8.3 Добавить onboarding/connection guidance для первого запуска, stopped server, running without sessions и troubleshooting; проверить Compose tests наличия локальной сети, доверенной сети, актуального address/pairing шага и отсутствия требований аккаунта/desktop backend.
- [ ] 8.4 Перестроить Home в connection dashboard со status surface, address/pairing block, sessions, quick actions и active transfers; проверить visual hierarchy tests и сворачивание инструкции после подключения с возможностью открыть её снова.
- [ ] 8.5 Реализовать adaptive top-level navigation: bottom navigation на phone и navigation rail на large screen с единым destination/selection state; проверить resize/recreation tests без повторной команды и потери текущего экрана.
- [ ] 8.6 Перестроить Text в session feed с direction/sender/time/status, устойчивыми link actions и доступным composer; проверить длинный URL, font scale 200%, TalkBack order и отсутствие перекрытия кнопок.
- [ ] 8.7 Перестроить Files в operational cards с type icon, metadata, stage/progress и только применимыми cancel/retry/open/save actions; проверить длинное filename, все terminal states и отсутствие ложных 100%/ETA.
- [ ] 8.8 Привести History и Settings к спокойной плотности, логическим sections, полезным empty states и inline save/validation feedback; проверить content/empty/loading/error и failed-save screenshot/semantics tests.
- [ ] 8.9 Разделить persistent `UiState` и consumable `UiEffect` для permission/settings/SAF/snackbar/focus flows; проверить ViewModel/Compose tests, что recreation и recomposition не повторяют start, picker, transfer или retry.
- [ ] 8.10 Добавить live-region announcements только для connection/pairing/transfer stage/terminal transitions и уважать Android reduced-animation setting; проверить semantics tests, что progress updates не захватывают focus и motion не является единственным feedback.
- [ ] 8.11 Проверить Home, Text, Files, History и Settings на phone/large-screen, light/dark и font scale 200%; исправить clipped actions, overflow и unstable layout, подтвердив Compose screenshot/layout assertions.
- [ ] 8.12 Пройти Android accessibility scanner/Compose semantics audit для интерактивных элементов и декоративных изображений; сохранить checklist и исправить blocker findings, подтвердив `:app:connectedDebugAndroidTest` на API 29 и API 37.1.

## 9. Web visual system, recovery и accessibility

- [ ] 9.1 Зафиксировать CSS semantic tokens для layered light/dark surfaces, typography, spacing, shapes, borders, focus и status meanings, согласованные по смыслу с Android; проверить Vitest/CSS contract tests и отсутствие внешних fonts/CDN/runtime styling dependencies.
- [ ] 9.2 Перевести web controllers/views на явные loading/empty/ready/disabled/offline/error/cancelled states и consumable effects; проверить Vitest DOM tests, что render не повторяет network commands.
- [ ] 9.3 Перестроить shell в двухколоночный wide layout «connection/device + workspace» и одноколоночный narrow layout с неизменным DOM reading order; проверить Playwright screenshots при 360/768/1920 px и zoom 200% без horizontal page overflow.
- [ ] 9.4 Привести text/link feed и composer к общим direction/status/action semantics; проверить long URL, copy/open actions, keyboard order и сохранение draft при reconnect.
- [ ] 9.5 Привести file cards к общим metadata/stage/progress/action semantics; проверить long filename, cancel/retry/download states и отсутствие controls за границами card.
- [ ] 9.6 Добавить semantic landmarks, правильную heading hierarchy, нативные controls и единые `aria-live` status/alert regions; проверить DOM accessibility assertions для connection, pairing, text и file states.
- [ ] 9.7 Реализовать focus restoration после pairing error, retry, cancel и закрытия details/dialog; проверить Playwright keyboard flow Tab/Shift+Tab/Enter/Space без mouse input.
- [ ] 9.8 Добавить короткие state/item/progress transitions и `prefers-reduced-motion` fallback; проверить Playwright, что reduced motion отключает необязательные transitions, но не скрывает feedback.
- [ ] 9.9 Проверить light/dark, hover/pressed/focused/disabled/loading и long-content states; подтвердить screenshot assertions и отсутствие layout shift, скрывающего primary action.
- [ ] 9.10 Проверить Chrome и Edge на Windows 10/11: layout, reload/reconnect, trusted reconnect, revoke, text retry, file cancel/retry и accessible status; заполнить manual matrix с ожидаемым и фактическим результатом.

## 10. Интеграция, регрессия и документация

- [ ] 10.1 Выполнить `npm run typecheck --prefix web`, `npm test --prefix web`, `npm run build --prefix web` и Playwright gates; исправить все failures и сохранить успешный вывод в отчёте change.
- [ ] 10.2 Выполнить `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; исправить все failures и проверить, что APK содержит свежий `web-manifest.json` и собранные assets.
- [ ] 10.3 Выполнить `./gradlew.bat :app:connectedDebugAndroidTest` отдельно на API 29 и API 37.1; проверить lifecycle, pairing, text/file, persistence и Compose accessibility regression без skipped blocker tests.
- [ ] 10.4 Провести end-to-end recovery matrix: разные сети/неверный IP, permission revoke, invalid/expired code, approval deny/timeout, session revoke, connection loss, file limit/space/checksum/destination и unsupported protocol; для каждого case подтвердить точную причину, допустимое действие и отсутствие duplicate operation.
- [ ] 10.5 Обновить пользовательские инструкции и техническую документацию по connection troubleshooting, error codes, manual retry, cancellation, accessibility и ограничениям локального HTTP; проверить соответствие ТЗ и отсутствие обещаний функций этапа 10.
- [ ] 10.6 Выполнить `openspec validate harden-errors-and-accessibility --strict`, сверить все acceptance evidence и отметить checkbox завершённым только после успешной автоматической и ручной проверки.
