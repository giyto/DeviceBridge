## 1. Baseline и security decision

- [ ] 1.1 Зафиксировать текущий release baseline: build types, versionName/versionCode, signing source, packaged web assets, manifest permissions, route graph и runtime network destinations; проверить отчёт командами сборки/inspection и убедиться, что он не содержит секретов.
- [ ] 1.2 Создать threat/control/evidence matrix для каждой обязательной меры раздела 12.2 ТЗ и LAN-only residual risks; проверить, что у каждой строки есть owner, автоматический или ручной gate и blocking severity.
- [ ] 1.3 Зафиксировать ADR о выборе варианта №4 — одно Android-приложение и LAN-only HTTP/WebSocket — с отклонёнными TLS/WebRTC/desktop alternatives; проверить согласованность с proposal, delta spec и ТЗ без обещания шифрования транспорта.

## 2. Release build и идентичность artifact

- [ ] 2.1 Добавить failing Android/route tests, подтверждающие отсутствие `/diagnostics/*`, fixture data, developer controls и подробных ошибок в release variant при сохранении diagnostics в debug; проверить, что тесты падают до разделения surface.
- [ ] 2.2 Разделить debug/release runtime configuration и route registration без изменения production protocol: diagnostics доступны только debug, release возвращает безопасный `404`; проверить variant-specific tests и APK inspection.
- [ ] 2.3 Настроить release packaging так, чтобы production web build и воспроизводимый `webAssetVersion` всегда создавались до APK; проверить clean `assembleRelease`, содержимое assets и совпадение manifest hash с фактическим bundle.
- [ ] 2.4 Настроить release signing только из внешних локальных/CI-параметров без debug fallback и без ключей/password в repository; проверить, что отсутствующая конфигурация даёт явный distribution blocker, а подписанный candidate проходит `apksigner verify`.
- [ ] 2.5 Добавить проверку монотонных versionCode, непустого versionName, commit SHA, SHA-256 APK и web asset version; проверить генерацию компактного release metadata report для одного candidate.

## 3. Security gates и минимальное hardening

- [ ] 3.1 Расширить negative contract tests для публичных/защищённых HTTP и WebSocket routes, Host/Origin/CORS и безопасных ошибок; проверить, что unauthorized payload не обрабатывается и ответы не раскрывают session или пользовательские данные.
- [ ] 3.2 Проверить pairing expiry/attempt limits/phone approval, session generation, trusted credential encryption/revoke/expiry и restart behavior; добавить только отсутствующие tests/fixes и подтвердить отсутствие повторного доступа после revoke.
- [ ] 3.3 Проверить text/file boundaries: HTML-safe rendering, MIME/size/name/path validation, single-use grants, checksum, cancel/retry и terminal result; подтвердить тестами отсутствие traversal, duplicate operation и ложного `completed`.
- [ ] 3.4 Проверить server lifecycle при stop, process recreation, смене сети и отзыве local-network permission; подтвердить, что listener и credentials прекращают предоставлять доступ в запрещённом состоянии.
- [ ] 3.5 Добавить release scans для private key/password/token patterns, пользовательского payload, абсолютных локальных путей, внешних URL, analytics/CDN/service worker и debug markers; проверить, что безопасная fixture не вызывает ложный blocker, а запрещённая fixture блокирует gate.
- [ ] 3.6 Провести dependency review Android и web lock state с фиксацией источника, даты и findings; устранить либо документированно классифицировать findings и запретить verdict `READY` при unresolved critical/high риске.

## 4. Автоматическая release-проверка

- [ ] 4.1 Выполнить Android JVM, Ktor, Compose, lint и release assemble gates; исправить regressions и сохранить точные команды/версии/результаты в verification report.
- [ ] 4.2 Выполнить web typecheck, unit, build, visual, compatibility и file-transfer gates в Chrome/Edge; проверить production bundle на отсутствие внешнего runtime-трафика и debug-only UI.
- [ ] 4.3 Проверить собранный APK: release certificate, debuggable flag, permissions, packaged routes/assets, web manifest, native libraries, secret/external-URL scan и SHA-256; подтвердить связь результатов с одним commit и artifact.
- [ ] 4.4 Установить release candidate как clean install и как update поверх предыдущей совместимо подписанной сборки; проверить сохранение settings, trusted browsers, history и SAF destination без скрытого сброса.

## 5. Целевая acceptance-матрица

- [ ] 5.1 Выполнить release smoke/instrumentation на API 29 и API 37.1: start/stop/notification, permission recovery, pairing, text/link, multiple files, Share Target, history/settings и lifecycle; отметить каждую обязательную ячейку только фактическим `PASS`, `FAIL`, `NOT RUN` или `BLOCKED`.
- [ ] 5.2 Проверить текущий release candidate в актуальных Chrome и Edge на Windows 10 и Windows 11: layout, warning, pairing/approval/revoke, trusted reconnect, text/file flows, cancel/retry, keyboard/accessibility и отсутствие external requests; сохранить ожидаемый и фактический результат.
- [ ] 5.3 Передать файл не менее 500 МБ в обе стороны и проверить SHA-256, memory/streaming behavior, cancel, network interruption и отсутствие ложного terminal success; зафиксировать checksum и результат для Android/browser matrix.
- [ ] 5.4 Провести финальную egress-проверку в среде без интернета и с наблюдением network requests; подтвердить, что основные flows работают локально и ни Android app, ни web UI не обращаются к внешнему backend, analytics или CDN.

## 6. Документация и release verdict

- [ ] 6.1 Обновить ТЗ, user guide и security documentation: LAN-only модель, отсутствие шифрования HTTP, trusted-network warning, защищаемые данные, signing setup без секретов и действия при revoke/error; проверить согласованность формулировок Android и web.
- [ ] 6.2 Создать release checklist и итоговый verification report с commit, version metadata, APK/web hashes, окружениями, всеми gates, manual matrix, findings и residual risks; проверить, что отчёт не содержит credentials, payload и приватных путей.
- [ ] 6.3 Вычислить verdict по правилу fail-closed: `READY` только при отсутствии unresolved critical/high findings, наличии release-подписи и `PASS` во всех обязательных ячейках; иначе оставить конкретный `BLOCKED` без подмены непроверенного результата успехом.
- [ ] 6.4 Выполнить `openspec validate prepare-mvp-security-release --strict`, сверить каждый scenario delta spec с evidence и отметить change завершённым только после успешной валидации и подтверждённого release verdict.
