## Context

См. `proposal.md` для мотивации. Текущие Android и web интерфейсы уже используют semantic states, light/dark palettes, adaptive layouts и accessibility contracts, поэтому change развивает существующую систему, а не заменяет UI-архитектуру. Android построен на Compose Material 3 и следует системной теме. Web использует статические HTML/CSS/TypeScript assets внутри APK, системный `prefers-color-scheme` и не имеет внешних runtime-зависимостей.

Постоянная web-шапка сейчас находится непосредственно в `index.html`, а security warning является статическим блоком. Новые preferences должны оставаться browser-local и не смешиваться с session token или trusted credential. Redesign сохраняет server routes и source-of-truth operation state; защищённый status DTO может обратно совместимо получить только display metadata устройства.

## Goals / Non-Goals

**Goals:**

- создать узнаваемую, спокойную и практичную визуальную систему для ежедневного использования;
- обеспечить равное качество Midnight dark и Porcelain light themes;
- уменьшить визуальный шум и освободить полезную высоту web viewport удалением постоянной шапки;
- добавить предсказуемый двухрежимный light/dark web theme toggle без flash неправильной темы;
- позволить скрыть повторяющуюся security guidance, сохранив возможность вернуть её и не ослабляя безопасность;
- сохранить accessibility, adaptive layouts и существующую функциональную семантику;
- показывать авторизованному браузеру effective device name, а на новой установке предзаполнять его реальными manufacturer/model телефона.

**Non-Goals:**

- ручной выбор темы внутри Android-приложения;
- изменение pairing, session lifecycle, text, file, history или settings business logic за пределами исходного device-name fallback и защищённой display metadata;
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

### 5. Web theme preference имеет два режима и один icon toggle

Web использует только тип `light | dark` и browser-local хранилище с прежним versioned key `devicebridge.theme.v1`. Эффективная тема отражается атрибутом `data-theme` на `document.documentElement` и соответствующим `color-scheme`. Значение `system` из предыдущей итерации считается legacy и мигрирует в `dark`.

Минимальный bootstrap в `<head>` читает только допустимое значение до загрузки stylesheet/module и выставляет initial theme, чтобы исключить flash. Недоступный storage, неизвестное значение или исключение дают безопасный fallback `dark`. `storage` event обновляет другие вкладки того же origin; `matchMedia` больше не участвует в выборе темы.

Theme control — одна нативная icon button справа в connection/device area. Солнце показывает активную светлую тему, луна — активную тёмную; accessible name и title описывают действие переключения на противоположную тему. Выбор не вызывает reload и не меняет session/drafts.

### 6. Web-шапка удаляется, branding остаётся в содержании

Элемент `header.site-header`, его отдельная высота, brand link и badge «Только локально» удаляются. Первый meaningful block страницы становится connection/device context. Доступный page title сохраняется в документе и connection area; favicon и browser title остаются. Дополнительная eyebrow-подпись «Телефон и компьютер — рядом» и цветная верхняя signal-line удаляются как декоративный шум. Метка локальности не переносится в новую постоянную полосу: локальная природа объясняется в connection/help content и security warning.

### 7. Device identity берётся из Android settings

Чистая функция нормализует `Build.MANUFACTURER` и `Build.MODEL`: убирает пустые/управляющие значения, не дублирует manufacturer, если model уже начинается с него, и ограничивает результат действующим лимитом device name. Этот результат используется DataStore repository как fallback при отсутствии сохранённого имени и как миграция точного legacy-default `DeviceBridge Android`; любое другое допустимое сохранённое значение считается пользовательским и всегда приоритетно. Начальное значение runtime settings state также строится с physical fallback, чтобы защищённый status не успевал вернуть generic name до первой эмиссии DataStore.

`EffectiveFileLimitProvider` расширяется чтением effective settings и передаёт `deviceName` в существующий защищённый `/api/v1/status`. Публичный web manifest не получает device identity. Web показывает имя только после успешной авторизации, заменяя техническую строку web asset hash.

### 8. Security warning имеет versioned collapse preference

Для предупреждения используется прежний отдельный key `devicebridge.security-warning.v1`, где legacy-состояние dismissed интерпретируется как collapsed. Он не связывается с trusted credential, session token или server generation. При первом открытии warning раскрыт. Вся поверхность предупреждения является одной нативной кнопкой с `aria-expanded`: мышь, Enter или Space переключают раскрытый и компактный варианты, а отдельные кнопки «Скрыть»/«Показать» отсутствуют.

В раскрытом варианте доступны заголовок и полный текст. В компактном остаются warning icon, заголовок и chevron, поэтому guidance не исчезает полностью и не требует отдельного connection help/details блока. Если storage недоступен, состояние меняется только до reload; очистка site data возвращает раскрытый вариант. В preference не хранится сеть, IP, browser identity или пользовательский контент.

Альтернатива полностью удалить warning отклонена, потому что локальный HTTP остаётся незашифрованным. Альтернатива связывать collapse с trusted browser отклонена, потому что визуальная preference не должна расширять или сужать доверие.

### 9. Accessibility и visual QA являются частью дизайна

Все controls имеют default, hover/pressed, focused, disabled и loading states. Theme и warning controls используют native semantics, работают Tab/Shift+Tab/Enter/Space и имеют видимый focus в обеих темах. Status не кодируется только цветом. Layout проверяется при 360/768/1920 px, zoom/font scale 200 процентов, long URL/filename и reduced motion.

Playwright visual baselines покрывают обе темы и отсутствие site header. Android Compose screenshots/layout assertions покрывают phone/large-screen и обе системные темы. OS-level screenshot skill используется только для ручной финальной проверки эмулятора, а browser screenshots — через Playwright.

### 10. Android top-level headers остаются краткими

Home, History и Settings показывают только основной заголовок экрана. Декоративные eyebrow-подписи и повторяющие уже известную локальную модель пояснения под заголовком не отображаются. В History отдельный заголовок «Фильтры» сохраняется, но пояснение под ним удаляется. Это уменьшает визуальный шум, не меняя semantics основных заголовков, состояния, фильтры или действия.

### 11. Верхняя web-композиция использует общую линию

На wide viewport product/theme area и connection/workspace area начинаются на одной верхней линии. Название DeviceBridge и icon theme toggle образуют одну устойчивую строку, а правая область не получает искусственный верхний отступ. На narrow viewport порядок остаётся одноколоночным: branding, connection status, предупреждение и workspace без overlap или horizontal overflow.

### 12. History использует последовательные карточки и modal filter list

History остаётся `LazyColumn` и показывает ровно одну запись на строку. Для history применяется специализированная скруглённая card surface вместо плоского `OperationalItem`: верхняя строка содержит тип и semantic status, основной уровень — имя файла либо text/link preview, нижняя metadata — размер файла при наличии, время, browser и направление. Карточка занимает всю доступную ширину, допускает перенос длинного filename, сохраняет общий radius/tokens Midnight/Porcelain и не вводит квадратную либо двухколоночную grid-композицию. Открытие деталей остаётся действием всей карточки, удаление — отдельным различимым destructive action.

Три горизонтальных ряда `FilterChip` удаляются с основного экрана. Вместо них используется одна компактная outlined surface «Фильтры» с badge количества активных значений и кратким summary. Она открывает Material 3 `ModalBottomSheet`. Внутри располагаются вертикальные группы «Направление», «Тип» и «Результат»; каждая строка имеет label, checkbox/selected semantics и touch target не менее 48 dp. Выбор сразу отправляет существующие `ToggleDirection`, `ToggleKind` и `ToggleStatus`, поэтому `SavedStateHandle` и repository query продолжают быть единственным source of truth. Новое действие `ResetFilters` атомарно очищает три набора; «Готово» только закрывает sheet и не создаёт отдельный draft фильтра.

Для экранов с текстовыми полями вводится reusable modifier `dismissKeyboardOnUnconsumedTap`. Он очищает focus только после обычного tap, который не был потреблён дочерним interactive control, и применяется к корневым scroll containers Text и Settings. Поэтому тап по свободной области закрывает IME, тогда как TextField, кнопки, карточки, navigation и drag/scroll сохраняют своё действие. Очистка focus не отправляет draft и не меняет ViewModel state; системный Back остаётся стандартным способом закрытия IME.
## Risks / Trade-offs

- [Initial theme bootstrap расходится с runtime store] → одинаковый набор `light/dark`, fallback `dark` и migration legacy `system` закрепляются contract-тестом.
- [Светлая тема выглядит плоско] → разделять уровни холодным page background, borders и очень сдержанной elevation, а не большим количеством теней.
- [Новый стиль ухудшает контраст status colors] → проверять semantic container/content пары и визуальные состояния в обеих темах до обновления baseline.
- [Удаление верхних декоративных элементов скрывает branding или theme control] → сохранить доступный product heading и разместить icon toggle справа в connection area, проверив первый viewport на 360 и 1920 px.
- [Компактное предупреждение перестанут замечать] → всегда сохранять warning icon, полный заголовок, chevron и доступное `aria-expanded`; очистка preference возвращает раскрытый вариант.
- [Device metadata раскрывается до авторизации] → передавать имя только в защищённом status response и не добавлять его в публичный manifest.
- [Build manufacturer/model повреждены или слишком длинные] → чистая нормализация с generic fallback и лимитом 40 code points.
- [Legacy default ошибочно принят за пользовательское имя или generic name кратко попадает в status] → мигрировать только точное `DeviceBridge Android`, сохранить все остальные значения и инициализировать runtime settings physical fallback до первой эмиссии.
- [History card становится слишком высокой для длинных данных] → ограничить hierarchy тремя компактными уровнями, разрешить перенос filename и держать одну card на строку без фиксированного aspect ratio.
- [Modal filters скрывают активное состояние] → показывать badge количества и summary на trigger, а reset и selected semantics держать внутри sheet.
- [Общий tap-to-dismiss перехватывает field/button/scroll] → реагировать только на непотреблённый tap, закрепить Compose tests для draft preservation и single-action behavior.
- [Visual redesign случайно меняет operation behavior] → контроллеры и ViewModels не переписывать без необходимости, закрепить command-count и idempotency regression tests.
- [Visual snapshots становятся хрупкими] → фиксировать deterministic fonts/system states, использовать semantic DOM/Compose assertions отдельно от screenshots и обновлять baseline только после ручного просмотра.

## Migration Plan

1. Зафиксировать текущие semantic/command regression tests и добавить новые failing contracts для theme preference, header removal и warning dismissal.
2. Ввести новые Android и CSS tokens без перестройки feature state.
3. Добавить web theme/warning preference modules и pre-render bootstrap, затем свести theme preference к light/dark icon toggle.
4. Удалить site header, eyebrow и decorative signal-line, перестроить connection/workspace layout и показать авторизованное device name.
5. Последовательно мигрировать Android Home, Text, Files, History и Settings на новые primitives.
6. Обновить visual baselines только после проверки light/dark, responsive, keyboard и reduced-motion states.
7. Собрать web assets в APK и пройти полные Android/web regression gates.

Rollback выполняется одним возвратом change: preferences являются browser-local, legacy `system` безопасно мигрирует в dark, схема базы данных не меняется, а дополнительное поле status игнорируется старым web bundle.
