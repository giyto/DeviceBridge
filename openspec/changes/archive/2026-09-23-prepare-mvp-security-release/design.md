## Context

Существующие capabilities уже реализуют server lifecycle, browser authorization, text/file transfer, history/settings и recovery. Этот change не меняет продуктовую архитектуру: он переводит собранный функциональный MVP в проверяемый release candidate и закрывает обязательный security spike из ТЗ. Основное ограничение — HTTP/WebSocket внутри LAN не обеспечивает конфиденциальность от другого участника той же сети.

## Goals / Non-Goals

**Goals:**

- зафиксировать вариант №4 транспортного решения: LAN-only utility с явным residual risk;
- получить release APK, поведение которого не зависит от debug-only маршрутов и инструментов;
- превратить обязательные меры безопасности ТЗ в повторяемые gates с blocking semantics;
- связать APK, commit, version metadata, web asset version, checksum и acceptance evidence;
- подтвердить установку, обновление и ключевые flows в полной целевой матрице.

**Non-Goals:**

- внедрение TLS с пользовательским сертификатом, WebRTC/DTLS, desktop companion, cloud relay или аккаунтов;
- изменение protocol version и существующих пользовательских flows без найденного blocker;
- публикация в конкретном магазине приложений, маркетинговые материалы и автоматическая доставка обновлений;
- хранение signing key, password или иных release-секретов в repository.

## Decisions

### 1. MVP сохраняет LAN-only transport

Выбирается вариант №4 из ТЗ. Он сохраняет одно-приложную архитектуру, offline-first работу и уже проверенные browser flows. Ограничение компенсируется честным предупреждением, строгой authorization boundary и запретом трактовать session token как шифрование транспорта.

Альтернативы с локальным TLS, WebRTC/DTLS и desktop companion откладываются после MVP: каждая меняет onboarding, distribution или архитектуру и требует отдельного change.

### 2. Security spike является evidence-driven audit

Для каждой обязательной меры из раздела 12.2 ТЗ создаётся строка threat/control/test/evidence/result. Существующий успешный тест переиспользуется только если он запускается на release-relevant path и явно доказывает control. Непокрытый control получает сначала failing test либо воспроизводимый inspection gate, затем минимальное исправление.

Критическими blockers считаются обход authorization, раскрытие credentials или transfer payload, выполнение debug endpoint в release, внешний runtime-трафик, небезопасное принятие файла и ложный terminal success.

### 3. Debug и release имеют разные разрешённые поверхности

Diagnostics и developer-only инструменты остаются доступны только debug variant. Release route graph проверяется отрицательными contract tests и inspection собранного APK. Пользовательские web assets собираются production-командой и включаются в APK до Android packaging; asset manifest связывает bundle с содержимым artifact.

Различия build variants не должны затрагивать protocol contract, pairing, transfer state machine или persistence schema.

### 4. Подпись отделена от исходного кода

Release signing material передаётся локальной/CI-средой и не хранится в Git. В repository допускаются только имена ожидаемых параметров и безопасные инструкции. Verification подтверждает, что APK подписан не debug certificate, имеет монотонный versionCode и устанавливается как обновление поверх предыдущего artifact той же release-линии.

При отсутствии production key допускается создать локальный release candidate для проверки, но он получает статус `BLOCKED FOR DISTRIBUTION` и не может считаться публикуемым MVP.

### 5. Release evidence является версионированным артефактом

Отчёт фиксирует commit SHA, versionName/versionCode, SHA-256 APK, webAssetVersion, версии инструментов и фактическую матрицу. Большие логи и APK не коммитятся; в repository хранится компактный отчёт без секретов и пользовательского содержимого.

Автоматические gates включают существующие Gradle/Vitest/Playwright suites, release assemble/inspection, route-negative tests, secret/external-URL scan и dependency review. Ручная матрица применяется только к сценариям, которые требуют реального Android/network/browser окружения.

### 6. Release readiness блокируется неизвестным обязательным результатом

Итоговый verdict вычисляется из evidence: `READY` возможен только при отсутствии critical/high unresolved findings и при `PASS` во всех обязательных ячейках ТЗ. `NOT RUN`, `BLOCKED` и неподтверждённое предположение не преобразуются в `PASS`.

## Risks / Trade-offs

- [HTTP остаётся читаемым внутри LAN] → явное предупреждение, trusted-network scope, короткоживущие sessions и документированный residual risk.
- [Release-only конфигурация может отличаться от debug] → отдельные assemble/install/smoke gates именно для release candidate и отрицательная проверка diagnostics.
- [Secret scan может давать false positive] → ручная классификация с зафиксированным обоснованием; секреты никогда не добавляются в allowlist целиком.
- [Signing key недоступен в рабочей среде] → разделить технически проверенный candidate и distribution-ready artifact; отсутствие ключа остаётся blocker.
- [Dependency audit требует сети и меняется со временем] → фиксировать дату, источники и lock state; повторять непосредственно перед публикацией.
- [Полная матрица занимает время] → переиспользовать автоматические fixtures, но не подменять ими обязательные проверки реального устройства и ОС.

## Migration Plan

1. Зафиксировать baseline release build и таблицу обязательных controls.
2. Добавить failing release/security gates для найденных пробелов.
3. Исправить только подтверждённые blockers, не меняя protocol/persistence без отдельного решения.
4. Собрать подписанный release candidate, вычислить checksum и выполнить APK/network inspection.
5. Установить candidate как чистую установку и обновление; пройти автоматическую и ручную матрицу.
6. Обновить документацию, зафиксировать residual risks и verdict `READY` либо `BLOCKED`.

Rollback выполняется возвратом к предыдущему подписанному artifact и Git revert change. Поскольку новая migration данных не планируется, rollback не должен требовать удаления пользовательских данных; если реализация обнаружит необходимость schema/protocol migration, change должен быть пересмотрен до кода.
