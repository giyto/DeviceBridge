## Context

См. `proposal.md` для мотивации. Текущие Android и web интерфейсы уже используют semantic states, light/dark palettes, adaptive layouts и accessibility contracts, поэтому change развивает существующую систему, а не заменяет UI-архитектуру. Android построен на Compose Material 3 и следует системной теме. Web использует статические HTML/CSS/TypeScript assets внутри APK, системный `prefers-color-scheme` и не имеет внешних runtime-зависимостей.

Постоянная web-шапка сейчас находится непосредственно в `index.html`, а security warning является статическим блоком. Новые preferences должны оставаться browser-local и не смешиваться с session token или trusted credential. Redesign не может менять protocol DTO, server routes или source-of-truth operation state.

## Goals / Non-Goals

**Goals:**

- создать узнаваемую, спокойную и практичную визуальную систему для ежедневного использования;
- обеспечить равное качество Midnight dark и Porcelain light themes;
- уменьшить визуальный шум и освободить полезную высоту web viewport удалением постоянной шапки;
- добавить предсказуемый трёхрежимный web theme preference без flash неправильной темы;
- позволить скрыть повторяющуюся security guidance, сохранив возможность вернуть её и не ослабляя безопасность;
- сохранить accessibility, adaptive layouts и существующую функциональную семантику.

**Non-Goals:**

- ручной выбор темы внутри Android-приложения;
- изменение server, pairing, session, text, file, history или settings business logic;
- тяжёлый glassmorphism, постоянное glow, 3D, иллюстративный marketing UI или внешние fonts/assets;
- синхронизация визуальных preferences между разными browsers или через Android host;
- изменение launcher icon в рамках этого change.

## Decisions

### 1. Единый визуальный язык, но платформенные компоненты

Android и web разделяют semantic tokens и визуальный характер, но не общий сгенерированный theme-файл. Compose продолжает использовать Material 3 color scheme, typography и shapes; web использует CSS custom properties и semantic HTML. Это сохраняет platform conventions и не связывает два build pipeline.

Альтернатива — генерировать обе темы из общего JSON. Она отклонена: для текущего размера проекта добавит tooling и риск рассинхронизации build без достаточной пользы.

### 2. Палитры Midnight и Porcelain

Базовые semantic anchors:

| Role | Midnight | Porcelain |
| --- | --- | --- |
| Page background | `#080B12` | `#F4F6FA` |
| Primary surface | `#0F1520` | `#FFFFFF` |
| Secondary surface | `#171F2E` | `#EEF2F7` |
| Border | `#263044` | `#D8DFEA` |
| Primary text | `#F4F7FB` | `#121826` |
| Secondary text | `#9AA8BC` | `#5D687A` |
| Primary accent | `#7180FF` | `#586BEB` |
| Secondary accent | `#49CFF5` | `#168EBA` |
| Success | `#45D39D` | `#168560` |
| Warning | `#E8B45B` | `#A86B16` |
| Error | `#F07178` | `#CF4A5A` |

Итоговые container/content пары могут корректироваться ради контраста, но semantic meaning должен оставаться неизменным. Чистый чёрный и полностью белый page background не используются как основные фоны. Тени сдержанные, границы низкоконтрастные, radii согласованы и не превращают каждый control в pill.

### 3. Graphite-структура и ограниченный Signal Flow

Graphite Professional определяет сетку, выравнивание, плотность и action hierarchy. Signal Flow применяется только к connection indicator и transfer transition: короткая линия/импульс между условными endpoints, без непрерывной декоративной анимации. При reduced motion остаётся статическое состояние.

Альтернатива Aurora/Frosted с blur и glow отклонена из-за контраста, производительности Android и быстрого визуального утомления.

### 4. Android следует системной теме

`DeviceBridgeTheme` продолжает получать эффективную тему от системы. Redesign меняет color schemes, typography, shapes и reusable primitives, но не вводит DataStore preference и новый settings flow. Это соответствует platform expectation и ограничивает scope.

Каждый top-level экран мигрирует на общие layout primitives: screen header, section header, status surface, operational item, compact metadata row и empty/error surface. Состояние остаётся во ViewModel; composables не инициируют operation из-за recomposition.

### 5. Web theme preference имеет три режима

Web вводит тип `system | light | dark` и отдельное browser-local хранилище с versioned key `devicebridge.theme.v1`. Эффективная тема отражается атрибутом `data-theme` на `document.documentElement` и соответствующим `color-scheme`.

Минимальный bootstrap в `<head>` читает только допустимое значение до загрузки stylesheet/module и выставляет initial theme, чтобы исключить flash. Недоступный storage, неизвестное значение или исключение дают безопасный fallback `system`. В режиме `system` слушается `matchMedia`; для explicit light/dark системные события игнорируются. `storage` event обновляет другие вкладки того же origin.

Theme control находится внутри верхней области connection/device panel, а не в отдельной глобальной шапке. На wide viewport допустим segmented control; на narrow — компактная нативная кнопка/меню с полным accessible name. Выбор темы не вызывает reload и не меняет session/drafts.

### 6. Web-шапка удаляется, branding остаётся в содержании

Элемент `header.site-header`, его отдельная высота, brand link и badge «Только локально» удаляются. Первый meaningful block страницы становится connection/device context. Доступный page title сохраняется в документе и connection area; favicon и browser title остаются. Метка локальности не переносится в новую постоянную полосу: локальная природа объясняется в connection/help content и security warning.

### 7. Security warning имеет versioned dismiss preference

Для предупреждения используется отдельный key `devicebridge.security-warning.v1` со значением dismissed. Он не связывается с trusted credential, session token или server generation. При первом открытии warning видим. Кнопка «Скрыть» сохраняет preference и возвращает focus в connection area. Доступное действие «Показать предупреждение о сети» остаётся в connection help/details и очищает dismissal.

Если storage недоступен, warning остаётся видимым и скрывается только до reload. Очистка site data закономерно возвращает предупреждение. В preference не хранится сеть, IP, browser identity или пользовательский контент.

Альтернатива полностью удалить warning отклонена, потому что локальный HTTP остаётся незашифрованным. Альтернатива связывать dismissal с trusted browser отклонена, потому что визуальная preference не должна расширять или сужать доверие.

### 8. Accessibility и visual QA являются частью дизайна

Все controls имеют default, hover/pressed, focused, disabled и loading states. Theme и warning controls используют native semantics, работают Tab/Shift+Tab/Enter/Space и имеют видимый focus в обеих темах. Status не кодируется только цветом. Layout проверяется при 360/768/1920 px, zoom/font scale 200 процентов, long URL/filename и reduced motion.

Playwright visual baselines покрывают обе темы и отсутствие site header. Android Compose screenshots/layout assertions покрывают phone/large-screen и обе системные темы. OS-level screenshot skill используется только для ручной финальной проверки эмулятора, а browser screenshots — через Playwright.

## Risks / Trade-offs

- [Initial theme bootstrap расходится с runtime store] → один parser допустимых значений дублируется минимально и закрепляется contract-тестом на одинаковый key/value set.
- [Светлая тема выглядит плоско] → разделять уровни холодным page background, borders и очень сдержанной elevation, а не большим количеством теней.
- [Новый стиль ухудшает контраст status colors] → проверять semantic container/content пары и визуальные состояния в обеих темах до обновления baseline.
- [Удаление шапки скрывает branding или theme control] → сохранить доступный product heading и разместить theme control в connection panel, проверив первый viewport на 360 и 1920 px.
- [Пользователь забудет ограничение HTTP после dismissal] → оставить действие повторного показа в connection help; очистка preference возвращает warning.
- [Visual redesign случайно меняет operation behavior] → контроллеры и ViewModels не переписывать без необходимости, закрепить command-count и idempotency regression tests.
- [Visual snapshots становятся хрупкими] → фиксировать deterministic fonts/system states, использовать semantic DOM/Compose assertions отдельно от screenshots и обновлять baseline только после ручного просмотра.

## Migration Plan

1. Зафиксировать текущие semantic/command regression tests и добавить новые failing contracts для theme preference, header removal и warning dismissal.
2. Ввести новые Android и CSS tokens без перестройки feature state.
3. Добавить web theme/warning preference modules и pre-render bootstrap.
4. Удалить site header и перестроить connection/workspace layout.
5. Последовательно мигрировать Android Home, Text, Files, History и Settings на новые primitives.
6. Обновить visual baselines только после проверки light/dark, responsive, keyboard и reduced-motion states.
7. Собрать web assets в APK и пройти полные Android/web regression gates.

Rollback выполняется одним возвратом change: preferences являются browser-local и безопасно игнорируются старым web bundle; server/database migration отсутствует.
