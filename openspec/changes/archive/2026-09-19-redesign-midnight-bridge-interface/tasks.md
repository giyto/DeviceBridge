> Пункты 1–6 фиксируют завершённую первую итерацию редизайна. Решения о трёх режимах темы и segmented control уточнены пользователем и заменяются задачами 7.x.

## 1. Baseline и проверяемые visual contracts

- [x] 1.1 Зафиксировать текущие Android и web layouts, semantic structure, command counts и visual baselines до редизайна; проверить, что inventory покрывает Home, Text, Files, History, Settings, pairing, warning и все terminal states.
- [x] 1.2 Добавить failing Vitest tests для `system/light/dark` preference, invalid/storage-failure fallback, `matchMedia`, `storage` event и сохранения session/drafts; проверить, что тесты падают до реализации theme controller.
- [x] 1.3 Добавить failing DOM tests для первого показа, скрытия, reload, возврата и storage-failure поведения security warning; проверить keyboard focus и отсутствие влияния на session/trusted state.
- [x] 1.4 Добавить failing structure tests, требующие отсутствия `site-header`, сохранения доступного page/product title и нахождения theme control внутри connection area; проверить narrow/wide DOM order.
- [x] 1.5 Добавить failing Android theme/component tests для Midnight/Porcelain semantic roles, contrast-safe status containers, system theme и reduced-animation fallback; проверить light/dark и phone/large-screen fixtures.

## 2. Web theme и security preferences

- [x] 2.1 Реализовать versioned browser-local theme store со значениями `system/light/dark`, безопасным parser и fallback; проверить unit tests для read/write/clear, invalid value и недоступного storage.
- [x] 2.2 Реализовать pre-render theme bootstrap, effective theme controller, `matchMedia` и cross-tab `storage` synchronization; проверить отсутствие theme flash, reload и изменение системной темы без повторной network-команды.
- [x] 2.3 Реализовать доступный трёхрежимный theme control внутри connection area с desktop и narrow presentation; проверить Tab/Shift+Tab/Enter/Space, visible focus и сохранение выбора.
- [x] 2.4 Реализовать отдельный versioned store и presentation state для dismiss/restore security warning; проверить первый показ, постоянное скрытие в profile, возврат через connection help и graceful fallback без storage.
- [x] 2.5 Подключить theme и warning effects к web entrypoint без смешивания с session controller и без повторного pairing/text/file command при render, theme change или warning dismissal; проверить controller/DOM regression tests.

## 3. Web Midnight/Porcelain redesign

- [x] 3.1 Заменить CSS tokens на semantic Midnight/Porcelain palette, typography, spacing, radii, borders, elevation и action/status states; проверить contract tests для обеих тем и отсутствие внешних fonts/CDN/runtime styling.
- [x] 3.2 Удалить `site-header`, brand badge и отдельную locality badge из HTML/CSS; перенести доступный product heading, theme control и connection context в содержательную область и проверить, что отдельная верхняя полоса отсутствует на 360/768/1920 px.
- [x] 3.3 Перестроить wide connection/workspace grid и narrow single-column layout в Graphite-структуре без декоративной вложенности cards; проверить zoom 200 процентов, long address и отсутствие horizontal page overflow.
- [x] 3.4 Обновить pairing, connected browser, reconnect и security/help surfaces; проверить loading/offline/error/success, dismiss/restore warning и отсутствие layout shift при смене состояния.
- [x] 3.5 Перестроить text/link feed и composer в Midnight/Porcelain themes; проверить long URL, copy/open/retry, сохранение draft, focus restoration и отсутствие повторной delivery.
- [x] 3.6 Перестроить file draft, drop zone, queue и operational cards; проверить multiple files, long filename, cancel/retry/download, verifying без ложных 100 процентов и controls внутри card.
- [x] 3.7 Добавить ограниченный Signal Flow feedback для connection/transfer и reduced-motion fallback; проверить, что motion короткая, не блокирует действие и не является единственным status feedback.

## 4. Android theme и reusable primitives

- [x] 4.1 Обновить Compose color schemes, typography, shapes и semantic status tokens для Midnight/Porcelain, сохранив системный выбор темы; проверить unit/Compose tests и contrast для primary/secondary/status content.
- [x] 4.2 Создать или переработать reusable screen header, section header, connection/status surface, operational item, metadata row, empty/error surface и action hierarchy; проверить default/pressed/focused/disabled/loading/error/success/cancelled semantics.
- [x] 4.3 Реализовать ограниченный Android Signal Flow indicator и reduced-animation/static fallback; проверить, что recomposition и theme change не запускают server, pairing или transfer command.
- [x] 4.4 Привести bottom navigation и navigation rail к новой визуальной системе без изменения destinations/selection state; проверить phone/large-screen resize и Activity recreation.

## 5. Android screen redesign

- [x] 5.1 Перестроить Home как premium connection dashboard с первым приоритетом status, address/pairing, sessions, quick actions и active transfers; проверить stopped/running/no-session/connected/error и font scale 200 процентов.
- [x] 5.2 Перестроить Text как спокойную session feed с устойчивым composer и применимыми item actions; проверить оба направления, long URL, retry, TalkBack order и сохранение draft.
- [x] 5.3 Перестроить Files как draft + operational queue с ясной metadata/stage/action hierarchy; проверить multiple selection, remove-before-send, cancel picker, cancel/retry, long filename и terminal states.
- [x] 5.4 Перестроить History и Settings без лишней card nesting, сохранив loading/empty/content/error и draft/saving/saved/failed states; проверить filters, retry, validation и отсутствие duplicate writes.
- [x] 5.5 Провести Compose semantics/layout audit всех top-level screens в light/dark, phone/large-screen и font scale 200 процентов; исправить clipping, контраст, touch targets, content descriptions и focus behavior.

## 6. Visual regression, интеграция и документация

- [x] 6.1 Обновить Playwright visual fixtures и baselines для Midnight/Porcelain на 360/768/1920 px и zoom 200 процентов; проверить отсутствие шапки, обе темы, theme control, warning shown/hidden, dense text/file states и reduced motion в Chrome/Edge.
- [x] 6.2 Выполнить web gates `npm run typecheck`, `npm test`, `npm run build`, visual, compatibility и file-transfer suites; исправить failures и проверить, что production bundle не содержит внешних ресурсов.
- [x] 6.3 Выполнить Android JVM, Compose, assemble и instrumentation gates на API 29 и API 37.1; проверить light/dark, phone/large-screen, lifecycle и отсутствие functional regressions.
- [x] 6.4 Проверить, что собранный APK содержит свежие web assets и theme bootstrap, а manifest asset version изменён воспроизводимо; сравнить встроенный bundle с production build.
- [x] 6.5 Обновить `docs/technical-specification.md`, user guide и visual verification report: описать Midnight/Porcelain, системную Android-тему, три web-режима, скрытие/возврат warning и неизменность локальной security model; проверить отсутствие обещаний новых backend-функций.
- [x] 6.6 Провести ручной visual QA через screenshot/Playwright: Android phone и large-screen, Chrome и Edge, обе темы, первый viewport, pairing, text, files, warning dismissal/restore и restart persistence; сохранить фактическую матрицу результатов.
- [x] 6.7 Выполнить `openspec validate redesign-midnight-bridge-interface --strict`, сверить acceptance evidence со всеми delta specs и отметить change готовым только после успешных автоматических и ручных проверок.
## 7. Уточнение темы и identity устройства

- [x] 7.1 Добавить failing web tests: отсутствуют eyebrow и цветная верхняя линия, theme control состоит из одной icon button, поддерживает только `light/dark`, мигрирует legacy `system` в `dark`, сохраняется между reload/tabs и имеет корректные accessible name/icon.
- [x] 7.2 Реализовать двухрежимные theme store/bootstrap/controller и icon toggle справа; удалить декоративную подпись и линию, не меняя session/drafts/operations.
- [x] 7.3 Добавить failing Android/JVM tests для нормализации manufacturer/model, fallback при некорректных данных, исходного имени при пустом DataStore и приоритета сохранённого пользовательского имени.
- [x] 7.4 Реализовать Android device-name provider/fallback и wiring в DataStore repository без перезаписи пользовательского значения.
- [x] 7.5 Добавить `deviceName` в защищённый status contract, показать его в web connection card вместо web asset hash и проверить, что публичный manifest не раскрывает identity.
- [x] 7.6 Обновить web visual fixtures, документацию и verification report; выполнить OpenSpec strict validation, web gates, Android JVM/assemble и релевантные route/Compose tests.
- [x] 7.7 Добавить failing JVM tests для миграции точного legacy-имени `DeviceBridge Android`, сохранения произвольного пользовательского имени и physical initial state до первой эмиссии DataStore.
- [x] 7.8 Реализовать точечную миграцию legacy-default и physical initial settings state, обновить ТЗ/verification evidence и выполнить Android JVM/assemble, OpenSpec strict validation и diff checks.

## 8. Упрощение Android-заголовков

- [x] 8.1 Добавить failing Compose tests, подтверждающие отсутствие показанных декоративных eyebrow/supporting строк на Home, History и Settings при сохранении основных заголовков и фильтров.
- [x] 8.2 Сделать ScreenHeader пригодным для краткого варианта, убрать указанные строки и пояснение фильтров History; выполнить релевантные Compose tests, Android JVM/assemble, OpenSpec strict validation и diff checks.

## 9. Выравнивание web-header и сворачиваемое предупреждение

- [x] 9.1 Добавить failing DOM/CSS tests для общей верхней линии wide layout, отсутствия отдельного security-help блока и единой warning surface с aria-expanded, mouse/Enter/Space toggle и persistence.
- [x] 9.2 Реализовать выровненную product/theme и connection/workspace композицию, удалить отдельные hide/restore controls и превратить warning в раскрываемую/компактную поверхность без изменения session или security state.
- [x] 9.3 Обновить visual fixtures и документацию, выполнить web typecheck/unit/build, релевантные Playwright suites, Android asset assembly, OpenSpec strict validation и diff checks.
## 10. History cards, list filters и закрытие клавиатуры

- [x] 10.1 Добавить failing Compose/ViewModel tests: History показывает одну скруглённую запись на строку, файловая карточка содержит имя/размер/время/направление/status, filter trigger открывает вертикальный modal list с selected semantics и reset, а тап по свободной области снимает focus без потери введённого значения.
- [x] 10.2 Реализовать специализированные History cards и компактный filter trigger с `ModalBottomSheet`, вертикальными группами, active-count summary и `ResetFilters`, сохранив существующие details/delete/filter persistence и одну запись на строку.
- [x] 10.3 Реализовать reusable keyboard-dismiss modifier для непотреблённого тапа и применить его к Text/Settings без перехвата TextField, buttons, navigation или scroll и без отправки/изменения draft.
- [x] 10.4 Обновить Android UI/документацию и verification evidence; выполнить релевантные Compose/ViewModel tests, Android JVM/assemble, OpenSpec strict validation и diff checks.
