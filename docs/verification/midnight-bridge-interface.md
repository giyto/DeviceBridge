# Midnight Bridge interface: отчёт проверки

Дата проверки: 19 сентября 2026 года.

## Объём изменения

Проверяется только визуальная и presentation-часть существующего DeviceBridge:

- Midnight Bridge и Porcelain Bridge с Graphite Professional и ограниченным Signal Flow;
- Android, следующий системной теме без собственного переключателя;
- web-режимы `Светлая` и `Тёмная` с одной кнопкой-иконкой и локальным сохранением;
- удаление отдельной верхней шапки web UI;
- удаление декоративного eyebrow и цветной линии в рабочей области;
- показ имени реального Android-устройства только после защищённого подключения;
- исходное имя Android из производителя и модели с приоритетом пользовательского значения;
- сворачивание и раскрытие предупреждения доверенной сети одной кликабельной карточкой;
- сохранение прежних pairing, session, trusted browser, text и file contracts.

Новых backend routes, protocol commands, cloud/remote функций и отдельного desktop companion изменение не добавляет. Сворачивание warning не меняет локальную security model: HTTP/WebSocket остаётся незашифрованным и предназначен только для доверенной сети.

## Автоматические результаты

| Gate | Фактический результат |
| --- | --- |
| `npm test` | PASS: 39 файлов, 210 тестов |
| `npm run typecheck` | PASS |
| `npm run build` | PASS |
| `npm run test:visual` | PASS: 51 сценарий, 9 ожидаемых пропусков |
| `npm run test:compatibility` | PASS: 6 сценариев Chrome/Edge |
| `npm run test:file-transfer-gate` | PASS: 14 сценариев |
| `:app:testDebugUnitTest` | PASS |
| `:app:assembleDebug` и `:app:assembleDebugAndroidTest` | PASS |
| API 29 instrumentation | PASS: 109/109 |
| API 37.1 instrumentation | PASS: 109/109 |

Playwright-проверки запускались последовательно: visual, compatibility и file-transfer используют общий каталог результатов. Один параллельный служебный запуск file-transfer встретил удаление trace общим runner; отдельный полный повтор прошёл 14/14 и подтвердил отсутствие продуктового дефекта.

## Web visual matrix

| Поверхность | Midnight | Porcelain | Проверено |
| --- | --- | --- | --- |
| Chrome 360 px | PASS | PASS | single-column, pairing, controls, отсутствие page overflow |
| Chrome 768 px | PASS | PASS | adaptive layout, warning, operational surfaces |
| Chrome 1920 px | PASS | PASS | wide connection/workspace grid, общая верхняя линия, отсутствие отдельной шапки |
| Chrome 1920 px, zoom 200% | PASS | PASS | доступность основных действий и отсутствие horizontal page overflow |
| Edge 1920 px | PASS | PASS | branded-browser visual/compatibility contract |
| Warning expanded/collapsed | PASS | PASS | вся карточка переключает состояние, preference сохраняется, отдельного connection help нет |
| Dense text/file states | PASS | PASS | длинные URL/имена, retry/cancel/download, terminal states |
| Reduced motion | PASS | PASS | status остаётся видимым без необязательной анимации |

Theme regression отдельно проверяет две явные темы, миграцию прежнего значения `Системная` в `Тёмная` и то, что metadata `data-theme-preference` на корневом элементе не принимается за кнопку.

## Android matrix

| Поверхность | Светлая системная тема | Тёмная системная тема | Дополнительный contract |
| --- | --- | --- | --- |
| Phone navigation | PASS | PASS | Home, Text, Files, History, Settings |
| Large-screen navigation rail | PASS | PASS | те же destinations и selection state |
| Font scale 200% | PASS | PASS | основные действия доступны, semantics не теряются |
| API 29 | PASS: 109/109 | PASS: suite contracts | lifecycle и functional regressions |
| API 37.1 | PASS: 109/109 | PASS: suite contracts | lifecycle и functional regressions |

Compose tests покрывают phone/large-screen, light/dark, масштаб шрифта, loading/empty/error/success/cancelled, TalkBack descriptions и отсутствие команд при recomposition/theme change.

## Delta specs acceptance evidence

| Delta requirement | Проверяемое evidence |
| --- | --- |
| Android unified visual system | `DesignTokensTest`, `MidnightPorcelainThemeContractTest`, Compose semantics/layout tests, API 29/37.1 instrumentation, phone/large light/dark screenshots |
| Android premium screen composition | Home/Text/Files/History/Settings tests, navigation phone/rail matrix и screenshot audit |
| Local HTTP warning | warning store/controller/DOM tests, mouse/Enter/Space toggle, keyboard focus и persistent-profile restart |
| Web light/dark icon theme | theme store/controller/bootstrap/control tests, legacy migration, cross-tab/storage contracts, Chrome screenshots и persistent-profile restart |
| Android device identity | AndroidDeviceNameTest, DataStore legacy migration/priority tests, physical initial-state test, protected status route tests, public manifest exact-key contract и web status rendering tests |
| Shared Android/web visual language | semantic token/style contracts, Compose contracts, Midnight/Porcelain visual baselines |
| Adaptive workspace without header | DOM structure tests и Playwright 360/768/1920/zoom-200 Chrome/Edge matrix |
| Stable hierarchy and accessibility | keyboard pairing/retry/cancel scenarios, live-region contracts, reduced-motion tests и dense text/file screenshots |

Каждый scenario трёх delta specs имеет как минимум одно автоматическое доказательство; визуальные scenarios дополнительно сопоставлены с фактической screenshot/Playwright-матрицей выше.
## Production bundle

Две последовательные production-сборки создали одинаковый набор и одинаковые hashes. Из APK извлечены и byte-for-byte сравнены пять упакованных web-файлов (служебный `.gitkeep` не входит в APK).

- `webAssetVersion`: `sha256-89fa96ac7a7d7066`;
- CSS: `assets/index-xyQ1nVSS.css`;
- JavaScript: `assets/index-kT2FSaHl.js`;
- результат сравнения: PASS, `apkBundleMatches=true`, `reproducibleBuild=true`.

## Фактическая screenshot/Playwright-приёмка

| Окружение | Состояния | Результат |
| --- | --- | --- |
| Chrome, 1920 × 1080 | Midnight, Porcelain, первый viewport | PASS: заголовок, theme control, connection и warning читаемы |
| Chrome, 360 × 900 | Midnight, Porcelain, первый viewport | PASS: одна колонка, нет переноса слов и horizontal overflow |
| Edge, 1920 × 1080 | Midnight, Porcelain, pairing | PASS: структура и палитры совпадают с visual contract |
| Playwright fixtures | pairing, connected, long link, dense file queue, warning collapsed | PASS: элементы и действия остаются в своих surfaces |
| Chrome persistent profile | theme + warning, полное закрытие и новый процесс | PASS: `dark` и компактное состояние warning восстановлены из localStorage |
| Android API 37.1 phone, 1080 × 2400 / 420 dpi | системные light/dark | PASS: bottom navigation, Home и primary action не обрезаны |
| Android API 37.1 large-screen, 1600 × 2560 / 240 dpi | системные light/dark | PASS: navigation rail, Home и quick actions не обрезаны |

После Android-проверки эмулятору возвращены исходные 1080 × 2400, 420 dpi и светлая системная тема. Временные screenshots и отдельный Playwright profile удалены.

Первый ручной wide screenshot выявил дефект: `DeviceBridge` переносился внутри слова, а подписи theme control ломались посередине. Заголовок получил ограниченный responsive размер и `nowrap`, переключатель — устойчивую вертикальную композицию и непереносимые labels. Сначала добавлен падающий CSS contract, затем исправление; после обновления baselines полный visual gate повторно прошёл 51/51 выполняемых сценариев при 9 намеренных project skips.

Результат подтверждает визуальную часть change. Проверка реальной LAN-связи, pairing, текста и файлов не заменена скриншотами: её функциональные contracts закрыты отдельными compatibility/file-transfer/instrumentation gates, а пользовательскую end-to-end проверку следует выполнить перед архивированием.
## History cards, list filters и клавиатура

Дополнительная Android-приёмка после правок History выполнена на API 37.1:

- HistoryScreenTest, TextScreenTest и SettingsScreenTest: PASS, 29/29;
- проверены одна карточка истории на строку, file metadata, вертикальный modal filter list,
  selected semantics, общий reset и сохранение существующих details/delete actions;
- проверены снятие focus по непотреблённому тапу на свободной области, сохранение
  введённого текста/настройки, работоспособность кнопок и прокрутки;
- :app:testDebugUnitTest: PASS, 478/478;
- :app:assembleDebug и :app:assembleDebugAndroidTest: PASS;
- openspec validate redesign-midnight-bridge-interface --strict: PASS;
- git diff --check: PASS, ошибок whitespace нет.

Панель фильтров прокручивается, поэтому группы и действия остаются доступны на
маленьком экране и при увеличенном шрифте. Изменения не добавляют endpoint и не
меняют передачу текста, файлов, pairing или доверие браузера.
