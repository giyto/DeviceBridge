## Why

Функциональные этапы MVP 1.0 завершены, но ТЗ требует перед публичным релизом отдельный security spike и явный выбор транспортной модели. Текущий HTTP/WebSocket transport не шифрует трафик внутри локальной сети, поэтому релиз должен быть проверен как ограниченная LAN-only утилита, не создавать ложного ощущения защищённого канала и поставляться с воспроизводимыми security/release evidence.

## What Changes

- Зафиксировать для MVP 1.0 вариант №4 из ТЗ: одно Android-приложение, локальный HTTP/WebSocket только в доверенной сети, без облака, desktop companion и заявления о шифровании транспорта.
- Провести security spike по обязательным мерам MVP: поверхность публичных маршрутов, token/pairing/trusted-browser lifecycle, Origin/Host/CORS, upload validation, encrypted storage, журналы, остановка сервера и отсутствие внешнего runtime-трафика.
- Устранить найденные security/release blockers без расширения продуктового scope и повторно подтвердить негативные сценарии автоматическими тестами.
- Подготовить release-вариант APK: production web assets, воспроизводимая версия bundle, отключённые debug-only diagnostics, корректные version metadata, подпись и проверяемая установка/обновление.
- Добавить release checklist и evidence: dependency/security audit, отсутствие секретов и внешних endpoints, smoke-матрица API 29/API 37.1 и Chrome/Edge на Windows 10/11, checksum итогового APK и описание остаточных рисков LAN-only модели.
- Обновить пользовательскую и техническую документацию так, чтобы ограничения HTTP, доверенной сети и локального хранения были заметны до передачи данных.

## Capabilities

### New Capabilities

- `mvp-release-readiness`: определяет security decision, требования к release APK, обязательные security gates, release evidence и допустимые остаточные риски MVP 1.0.

### Modified Capabilities

- Нет. Существующие пользовательские возможности и protocol contracts сохраняются; найденное расхождение с действующим контрактом должно исправляться в реализации и тестах без добавления новой функции.

## Impact

- Android release build, manifest/build configuration, signing/version metadata и включение production web assets.
- Embedded Ktor routes, session/trusted-token storage, request validation, logging и debug-only diagnostics.
- Web bundle и network policy: отсутствие внешних ресурсов, аналитики, service worker и фонового transport.
- Автоматические security/release gates, dependency audit, APK inspection и целевая acceptance-матрица.
- `docs/technical-specification.md`, пользовательское руководство, security/release checklist и verification report.
