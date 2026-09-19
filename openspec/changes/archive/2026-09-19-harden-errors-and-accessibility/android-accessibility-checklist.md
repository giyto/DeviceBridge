# Android accessibility checklist

Дата проверки: 2026-09-19

Область проверки: Home, Text, Files, History и Settings; phone/large-screen navigation; Android API 29 и API 37.1.

## Semantics и взаимодействие

- [x] Каждый видимый кликабельный элемент имеет доступное имя; editable control определяется через нативный `SetText` semantics.
- [x] Кнопки, вкладки, карточки действий и выбор получателя имеют корректную роль.
- [x] Фактическая Compose touch-область видимых действий не меньше 48 dp.
- [x] Получатель на Text представлен одним `RadioButton`-действием без дублирующего фокуса.
- [x] Получатель на Files представлен ролью `RadioButton` и выбранным состоянием.
- [x] Карточки истории представлены ролью `Button` и понятным действием открытия деталей.
- [x] Feedback-карточки Text и Files имеют явное действие «Закрыть сообщение».
- [x] Декоративный символ quick action скрыт от accessibility services; текстовая подпись остаётся доступной.
- [x] Connection, pairing и transfer-stage transitions используют polite live region; частый progress не получает focus и live-region.
- [x] Loading/empty/error/success/cancelled состояния имеют текстовое объяснение и не полагаются только на цвет или анимацию.
- [x] Reduced-motion test подтверждает, что feedback остаётся понятным при scale factor 0.

## Layout и визуальная доступность

- [x] Home, Text, Files, History и Settings проверены при 400 dp, dark theme и font scale 200%.
- [x] Те же экраны проверены при 700 dp, light theme и navigation rail.
- [x] Длинные URL и имена файлов не скрывают применимые действия.
- [x] API 29 assertions прокручивают элемент в видимую область перед проверкой и не зависят от высоты конкретного AVD.

## Исправленные blocker findings

- [x] Удалено дублированное доступное действие выбора Text recipient: кликабельной является вся строка, визуальный `RadioButton` не создаёт вторую команду.
- [x] Добавлены отсутствовавшие роли выбора Files recipient и открытия History record.
- [x] Добавлены доступные имя и роль для dismiss feedback на Text и Files.
- [x] Декоративный quick-action glyph исключён из озвучивания.

## Автоматические подтверждения

- [x] `DeviceBridgeNavigationTest#visibleClickTargetsAcrossAllScreensHaveNamesRolesAndMinimumTouchSize` — успешно.
- [x] Новые semantics regression tests для QuickAction, Text, Files и History — 6/6 успешно.
- [x] `:app:connectedDebugAndroidTest` на Pixel 4 / Android 10 / API 29 — 106/106, 0 skipped, 0 failed.
- [x] `:app:connectedDebugAndroidTest` на Pixel 8 / Android 17 / API 37.1 — 106/106, 0 skipped, 0 failed.
