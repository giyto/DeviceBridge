## 1. Зафиксировать baseline и web toolchain

- [x] 1.1 Собрать текущие debug/release APK до добавления web assets, записать их размер, SHA-256, commit, JDK и Gradle в `docs/spikes/bundled-browser-web-interface.md` и проверить воспроизводимость указанными в отчёте командами
- [x] 1.2 Зафиксировать поддерживаемую LTS-версию Node.js в `web/.nvmrc` и `package.json#engines`, добавить TypeScript/Vite/Vitest без runtime-фреймворка, создать `package-lock.json` и проверить чистую установку командой `npm ci`
- [x] 1.3 Добавить `tsconfig`, Vite/Vitest config и минимальный падающий smoke-тест ожидаемого web shell, затем проверить, что test/build scripts запускаются из `web/` и первоначальный RED вызван отсутствующим поведением, а не настройкой окружения

## 2. Реализовать web state и интерфейс через TDD

- [x] 2.1 Сначала написать Vitest-тесты успешного, несовместимого и некорректного `/web-manifest.json`, затем реализовать типы ответа и `WebManifestClient` с same-origin URL, JSON-валидацией и timeout через AbortController до прохождения тестов
- [x] 2.2 Сначала написать тесты переходов `checking/available/unavailable`, трёх retry через 1/2/4 секунды, остановки retry и отмены предыдущего запроса при ручной проверке, затем реализовать чистый connection controller с инъекцией fetch/clock до прохождения тестов
- [x] 2.3 Сначала написать DOM-тесты текстовых состояний, live region, видимого retry, предупреждения о незашифрованном HTTP/доверенной сети и отсутствия работающих pairing/text/file форм, затем реализовать семантический HTML и renderer до прохождения тестов
- [x] 2.4 Добавить CSS для 360/768/1920 px, `prefers-color-scheme`, `prefers-reduced-motion`, видимого `:focus-visible` и touch targets не менее 44 px и проверить автоматическими style-contract тестами отсутствие горизонтального overflow и наличие обеих тем
- [x] 2.5 Добавить статический privacy-тест собранных HTML/CSS/JS/manifest, который запрещает внешние runtime URL, аналитику, service worker и незаявленные assets, и добиться его прохождения на production web build

## 3. Встроить воспроизводимый web build в Android APK

- [x] 3.1 Настроить Vite на очищаемый output `app/src/main/assets/web` с `index.html`, build manifest и content-hashed CSS/JS, затем дважды собрать неизменённые исходники и проверить совпадение относительных путей и SHA-256 всех outputs
- [x] 3.2 Добавить инкрементальные Gradle-задачи `npmCi` и `buildWebAssets` с явными inputs/outputs, связать их с packaging debug/release assets и проверить, что `assembleDebug` и `assembleRelease` из чистого состояния автоматически создают актуальный bundle
- [x] 3.3 Обновить `.gitignore` для `web/node_modules` и generated web assets, сохранить только маркер каталога назначения и проверить `git status` после сборки на отсутствие созданных файлов
- [x] 3.4 Проверить fail-fast поведение Gradle при недоступном Node/npm и при неполном Vite output, затем восстановить окружение и убедиться, что сборка не использует старый bundle

## 4. Добавить безопасную раздачу Android assets

- [x] 4.1 Сначала написать unit-тесты allowlist, MIME-типа, длины, отсутствующего пути и вариантов path traversal, затем реализовать `WebAssetProvider` и fake-реализацию без зависимости domain/ViewModel от Android или Ktor
- [x] 4.2 Сначала добавить instrumented-тест чтения `index.html`, build manifest и каждого связанного asset через Android AssetManager на API 29/37.1, затем реализовать Android provider до прохождения теста
- [x] 4.3 Сначала написать Ktor-тесты `GET /`, hashed `/assets/*`, 404, traversal, Content-Type, Cache-Control, CSP, `nosniff`, Referrer-Policy и `Cross-Origin-Resource-Policy`, затем реализовать статические routes поверх `WebAssetProvider` до прохождения тестов
- [x] 4.4 Сначала написать Ktor-тесты точного DTO `GET /web-manifest.json`, `Cache-Control: no-store`, protocol/web version, отсутствия секретов и непубличности `/api/v1/status`, затем реализовать manifest route до прохождения тестов
- [x] 4.5 Сначала написать Ktor-тесты допустимых/чужих `Host` и `Origin` и отсутствия permissive CORS, затем реализовать web origin policy и проверить ответы 200/403
- [x] 4.6 Подключить web routes к debug CIO composition root, сохранить bearer-защиту всех `/diagnostics/*` и проверить regression-тестами, что web manifest не открывает диагностические данные, а release classpath по-прежнему не содержит Ktor

## 5. Проверить интеграцию браузера и Android

- [x] 5.1 Выполнить `npm ci`, полный Vitest suite и production Vite build и проверить отсутствие skipped/focused тестов, TypeScript-ошибок и незаявленных runtime-зависимостей
- [x] 5.2 Собрать web bundle два раза из очищенного output и сформировать machine-readable перечень path/size/SHA-256, затем проверить полное совпадение обоих прогонов
- [x] 5.3 Собрать debug/release APK, проверить через `apkanalyzer` наличие полного `assets/web` и ссылочную целостность `index.html`, записать размер/delta/SHA-256 и подтвердить отсутствие Ktor/diagnostics в release
- [x] 5.4 На Pixel AVD API 29 выполнить полный `:app:connectedDebugAndroidTest`, открыть `/` и `/web-manifest.json` с компьютера через `adb forward`, проверить 200/403/404/security/cache headers и внести результат в отчёт
- [x] 5.5 На Pixel AVD API 37.1 выполнить ту же instrumented/host матрицу с выданным `ACCESS_LOCAL_NETWORK`, затем проверить контролируемую ошибку при отказе в разрешении и внести результат в отчёт
- [x] 5.6 В актуальных Chrome и Edge проверить один адрес DeviceBridge при viewport 360/768/1920, светлой/тёмной теме и клавиатурной навигации, убедиться в предупреждении о локальном HTTP, загрузке не более пяти секунд, отсутствии внешних запросов/service worker и приложить результаты или снимки к отчёту

## 6. Завершить документацию и проверки change

- [x] 6.1 Заполнить `docs/spikes/bundled-browser-web-interface.md` версиями Node/npm/Vite, командами, двумя reproducibility manifests, browser/AVD matrix, временем загрузки, APK delta, ограничениями и явно указать, что pairing/transfer/lifecycle/WebSocket reconnect остаются следующими changes
- [x] 6.2 Выполнить общий quality gate `npm ci`, web test/build, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` и проверить успешное завершение без пропущенных обязательных наборов
- [x] 6.3 Провести review границ Clean Architecture, security и scope: Ktor/Android не импортируются в web state/domain/ViewModel, нет второго desktop-приложения, внешнего backend, публичного `/api/v1/status`, permissive CORS, pairing или передачи данных, и зафиксировать найденные ограничения в отчёте
- [x] 6.4 Выполнить `openspec validate bundle-browser-web-interface --strict` и проверить успешную валидацию change перед ручным review, синхронизацией main specs, архивированием, commit и push
