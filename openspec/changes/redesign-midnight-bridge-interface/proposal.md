## Why

Текущий интерфейс DeviceBridge функционален и доступен, но визуально остаётся слишком утилитарным: Android и web не формируют достаточно цельный узнаваемый продукт, постоянная web-шапка расходует полезную высоту, а предупреждение о доверенной сети доминирует даже после того, как пользователь его понял. Нужен отдельный визуальный этап, который повысит качество ежедневного использования и презентационную ценность проекта без изменения локальной архитектуры и модели безопасности.

## What Changes

- Android и web получают единую визуальную систему `Midnight Bridge / Porcelain Bridge`: спокойную premium dark/light палитру, строгую сетку Graphite Professional, ограниченные мотивы Signal Flow, согласованную типографику, поверхности, границы, статусы, иконки и motion.
- Android Home, Text, Files, History и Settings перерабатываются в рамках существующих сценариев: улучшается композиция, плотность, навигация, пустые и рабочие состояния, но команды, данные и protocol behavior не меняются.
- Web workspace перерабатывается для desktop, tablet и narrow viewport; постоянная верхняя шапка с логотипом и меткой «Только локально» удаляется полностью и не заменяется другой постоянной полосой.
- Web получает явный выбор темы `Системная / Светлая / Тёмная`. Выбор сохраняется локально в браузере, применяется до первой отрисовки без вспышки неправильной темы, синхронизируется между вкладками и в системном режиме реагирует на изменение темы ОС.
- Предупреждение «Используйте только в доверенной сети» остаётся видимым при первом использовании, но его можно скрыть в текущем браузере. Скрытие сохраняется локально, не отключает сетевые ограничения или защиту и может быть отменено через доступное действие в справке или connection area.
- Для обеих платформ уточняются состояния default, hover/pressed, focused, disabled, loading, empty, offline, error, success и cancelled, а также поведение при reduced motion, масштабе 200 процентов и длинном содержимом.
- Добавляются visual regression и accessibility проверки светлой/тёмной темы, theme persistence, отсутствия неправильной theme flash, скрытия/возврата предупреждения и отсутствия лишней web-шапки.
- Изменение не добавляет аккаунт, облако, внешний backend, web CDN, внешние шрифты или новую desktop-программу и не меняет существующие API, pairing, session, text или file protocol.

## Capabilities

### New Capabilities

Новые самостоятельные capabilities не добавляются.

### Modified Capabilities

- `android-app-shell`: уточняется визуальный контракт Android-экранов, светлой/тёмной темы, адаптивной композиции и ограниченного Signal Flow feedback.
- `browser-web-interface`: меняются web-композиция без постоянной верхней шапки, ручной и системный выбор темы, а также управляемая видимость предупреждения о доверенной сети.
- `error-recovery-and-accessibility`: уточняются контраст, focus, reduced motion, сохранение смысла статусов и доступность скрытого предупреждения во всех темах и размерах.

## Impact

- Android: Compose theme/tokens, reusable UI components, Home, Text, Files, History, Settings, adaptive navigation и UI tests.
- Web: HTML shell, CSS tokens/layout, TypeScript presentation state, локальное хранение theme/warning preferences, responsive и accessibility behavior.
- Проверки: Compose tests, Vitest DOM/contracts, Playwright visual/keyboard/theme suites и ручная матрица Android phone/large-screen и Chrome/Edge.
- Встроенный server, protocol DTO, Room/DataStore/Keystore, file pipeline и network authorization остаются совместимыми; новые runtime-зависимости не планируются.
