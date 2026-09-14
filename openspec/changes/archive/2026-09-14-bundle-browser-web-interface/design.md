## Context

См. `proposal.md` — Ktor 3.5.2 / CIO уже принят по итогам spike, но сейчас сервер и маршруты существуют только в debug source set, а web-проекта и встроенных ресурсов нет. Согласно `docs/technical-specification.md`, браузерный клиент должен поставляться в единственном Android APK, работать без интернета, использовать TypeScript/Vite без обязательного runtime-фреймворка и предшествовать production lifecycle и pairing.

Этот change пересекает web toolchain, Gradle packaging, Android AssetManager и debug Ktor adapter. Поэтому статическая раздача проверяется на принятом debug-сервере, но Ktor не переносится в release classpath: release пока содержит только web assets, а production server lifecycle появится в change `add-server-lifecycle`.

## Goals / Non-Goals

**Goals:**

- получить воспроизводимый web build из зафиксированных исходников и lock-файла;
- включать актуальные web assets в debug и release APK через обычную Gradle-сборку;
- раздавать web shell и публичный version manifest через принятый debug server adapter;
- сохранить Clean Architecture: браузерный UI не знает о диагностических маршрутах, а Ktor не определяет web state machine;
- доказать offline-only работу, responsive layout, темы, базовую доступность, загрузку не более пяти секунд и совместимость Chrome/Edge;
- ограничить web routes допустимыми Host/Origin и показать пользователю ограничение локального HTTP;
- зафиксировать размер APK до и после добавления assets.

**Non-Goals:**

- production foreground service и восстановление сервера после смены сети;
- pairing, session token, защищённый `/api/v1/status`, trusted browsers и WebSocket `/api/v1/events`, включая reconnect;
- отправка текста, ссылок и файлов;
- история, настройки, PWA, service worker и установка страницы как приложения;
- публикация web bundle на CDN или любом внешнем сервере;
- перенос Ktor dependencies из debug в release до следующего change.

## Decisions

### 1. Vanilla TypeScript и Vite без runtime-фреймворка

Исходники располагаются в `web/` и состоят из семантического HTML, CSS и небольших TypeScript-модулей. Vite отвечает только за dev/build pipeline, а Vitest — за unit-тесты. Поддерживаемая LTS-версия Node.js фиксируется в `web/.nvmrc` и `package.json#engines`; точные зависимости фиксируются `package-lock.json`, а воспроизводимая установка выполняется через `npm ci`.

React/Vue отклонены: текущий shell содержит одну страницу, конечный автомат состояния и одно действие retry, поэтому runtime-фреймворк увеличил бы bundle и поверхность обновлений без измеримой пользы. Пересмотр допустим отдельным design-решением, если будущие transfer flows сделают ручной DOM-rendering сложнее.

### 2. Web build является входом Android packaging

Vite создаёт очищаемый каталог `app/src/main/assets/web`: `index.html`, build manifest и content-hashed файлы под `assets/`. Содержимое каталога является generated output и не редактируется вручную. Gradle получает инкрементальные задачи `npmCi` и `buildWebAssets` с явными inputs/outputs; задачи упаковки Android assets зависят от `buildWebAssets`, поэтому обычные `assembleDebug` и `assembleRelease` не могут упаковать устаревшую страницу.

`node_modules` и созданные web assets исключаются из Git; в репозитории хранятся исходники, конфигурация, lock-файл и пустой маркер каталога назначения. На чистом checkout Gradle сообщает понятную ошибку при отсутствии Node/npm и не подменяет web bundle старой копией.

Альтернатива — коммитить `dist` и обновлять его вручную. Она отклонена из-за риска рассинхронизации исходников и APK. Выделять web в отдельный Gradle-модуль также преждевременно: это build-time input одного `app`.

### 3. Android asset provider отделён от Ktor routes

Небольшой `WebAssetProvider` предоставляет только разрешённые относительные пути, content type, длину и поток чтения. Android-реализация читает `assets/web` через AssetManager. Ktor route adapter получает provider извне и не строит файловый путь из непроверенной строки; допустимые файлы определяются build manifest. Это закрывает path traversal и оставляет возможность заменить server engine без изменения web bundle.

В текущем change provider подключается к debug composition root и принятому CIO adapter. Release APK содержит ресурсы, но не точку запуска сервера. На следующем этапе тот же provider будет передан production server adapter через Hilt.

Прямая раздача произвольного пути через файловую систему отклонена: Android assets не являются обычным каталогом, а строковая конкатенация создаёт риск traversal и неверных MIME-типов.

### 4. HTML обновляется сразу, hashed assets кэшируются надолго

`GET /` возвращает `index.html` с `Cache-Control: no-store`. Ссылки ведут на content-hashed `/assets/*`, которые возвращаются с `Cache-Control: public, max-age=31536000, immutable`. Отсутствующий или неразрешённый путь получает 404. Content type определяется по build manifest, а не по пользовательскому заголовку.

Так браузер не смешивает HTML новой версии со старыми файлами и одновременно не скачивает неизменившиеся assets повторно. Query-параметр версии отклонён: content hash надёжнее связывает URL с содержимым.

### 5. Публичный version manifest является web asset, а не API

Debug server предоставляет same-origin `GET /web-manifest.json` и возвращает только:

```json
{
  "protocolVersion": 1,
  "webAssetVersion": "<deterministic-version>"
}
```

Файл создаётся вместе с bundle, получает `Cache-Control: no-store` и не содержит имени устройства, Android API, IP, uptime, диагностического токена или пользовательских данных. Успешная загрузка подтверждает только достижимость статической страницы и совместимость shell. `/api/v1/status` не регистрируется публично; защищённый endpoint и остальные `/api/v1` маршруты остаются change `add-secure-browser-session`.

Использовать `/diagnostics/health` отклонено: браузеру пришлось бы получать spike-токен, а временный контракт проник бы в production web code. Создавать WebSocket `/api/v1/events` сейчас также рано — его lifecycle и авторизация ещё не определены.

### 6. Соединение моделируется конечным автоматом в web-слое

Чистый TypeScript controller хранит одно из состояний `checking`, `available` или `unavailable`. `WebManifestClient` выполняет same-origin fetch с timeout через AbortController, валидирует JSON и major protocol version. После потери связи допускаются три автоматические попытки с задержками 1, 2 и 4 секунды; затем остаётся явная кнопка повторной проверки. Новая ручная попытка отменяет предыдущий запрос.

DOM renderer получает готовое состояние и не выполняет сеть самостоятельно. Fetch и таймеры инъецируются в controller, поэтому переходы, timeout, retry и cancellation проверяются Vitest без реального браузера и сервера.

Постоянный polling отклонён: до production lifecycle он создавал бы лишние запросы и ложное ощущение активной сессии. Этот HTTP retry проверяет только доступность manifest и не называется WebSocket-сессией; полный WebSocket reconnect добавится вместе с защищённой браузерной сессией.

### 7. Offline и security ограничения обеспечиваются сборкой, origin policy и HTTP-заголовками

HTML не содержит inline script/style и внешних URL. Сервер возвращает как минимум `Content-Security-Policy` с `default-src 'self'`, `connect-src 'self'`, `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`, а также `X-Content-Type-Options: nosniff` и `Referrer-Policy: no-referrer`. Build-тест анализирует HTML, CSS, JS и manifest и падает при внешней runtime-ссылке, service worker или незаявленном asset.

Web routes получают allowlist допустимых host-значений от composition root. Запрос с другим `Host` отклоняется; присутствующий `Origin` обязан совпадать со scheme/host запроса. Сервер не добавляет `Access-Control-Allow-Origin` и возвращает `Cross-Origin-Resource-Policy: same-origin`. CSP не объявляет transport encryption: MVP работает по локальному HTTP, а ограничения этой модели отдельно зафиксированы в ТЗ. Разрешать CDN даже как fallback отклонено, потому что нарушает offline и privacy требования.

### 8. Один responsive shell с CSS-темами и семантической разметкой

Страница использует один столбец на узких экранах и ограниченный по ширине контейнер на широких. CSS учитывает 360, 768 и 1920 px, `prefers-color-scheme`, `prefers-reduced-motion`, видимый `:focus-visible` и touch target не менее 44 px. Статус передаётся текстом и через live region, а retry остаётся нативной кнопкой.

На странице отображаются название DeviceBridge, объяснение локального соединения, статус и сведения о том, что pairing/передача появятся позже. Отдельный заметный блок предупреждает, что HTTP не шифрует локальный трафик и приложение следует использовать только в доверенной сети. Формулировка пользовательская; технические подробности не подменяют основное предупреждение. Disabled-псевдоформы не создаются: они выглядели бы как сломанная функция и противоречили честному scope этапа.

### 9. Лимит загрузки измеряется отдельно от установления TCP

Интеграционный замер начинается после успешного TCP connect и завершается после получения HTML и всех обязательных assets из его ссылок. Для API 29 и API 37.1 через `adb forward`, а также для ручной проверки Chrome/Edge, результат MUST укладываться в пять секунд. В отчёте фиксируются устройство, браузер, количество и общий размер запросов и длительность.

Этот критерий не превращается в хрупкий unit-test с wall clock: автоматический host integration test использует локальный endpoint и щедрый верхний лимит, а фактические браузерные значения документируются отдельно.

## Risks / Trade-offs

- **Риск:** Node/npm увеличат время обычной Android-сборки. → Gradle tasks используют inputs/outputs, `npm ci` повторяется только при изменении lock-файла, а Vite build — при изменении web inputs.
- **Риск:** generated assets могут загрязнять worktree или остаться устаревшими. → Каталог очищается перед сборкой, исключается из Git и всегда создаётся как зависимость Android packaging.
- **Риск:** browser cache смешает разные версии. → HTML и web manifest не кэшируются, а остальные URL содержат content hash и считаются immutable.
- **Риск:** динамический asset path откроет traversal. → Provider принимает только allowlist из build manifest и никогда не обращается к произвольному пути устройства.
- **Риск:** публичный manifest примут за готовую авторизацию. → Он находится вне `/api/v1`, содержит только версии, а UI явно сообщает, что защищённая сессия ещё не создана.
- **Риск:** Host/Origin validation отклонит допустимый адрес эмулятора или LAN IP. → Composition root формирует явный allowlist из адресов текущего запуска; тесты покрывают loopback, локальный адрес, порт и заведомо чужие значения.
- **Риск:** `adb forward` не проверяет реальный Wi-Fi LAN. → На этом этапе он доказывает Android/browser integration; реальный LAN, смена сети и permission lifecycle обязательны в `add-server-lifecycle`.
- **Компромисс:** release APK станет больше до появления production server. → Delta измеряется и фиксируется; это сознательная подготовка единственного APK, а не мёртвый внешний клиент.

## Migration Plan

1. Зафиксировать baseline размеров debug/release APK и версии Node/npm.
2. Добавить `web/`, lock-файл, Vitest и тесты web state machine до реализации UI.
3. Настроить Vite output и Gradle tasks, затем доказать воспроизводимость двух чистых сборок.
4. Добавить Android asset provider и тесты безопасного разрешения путей.
5. Подключить статические routes и web manifest к debug CIO adapter, затем проверить Host/Origin policy и HTTP-контракт.
6. Проверить страницу через `adb forward` на API 29/API 37.1 и вручную в Chrome/Edge при целевых viewport и темах, включая лимит пяти секунд и предупреждение о доверенной сети.
7. Собрать debug/release APK, проверить assets и delta размера, затем выполнить unit/lint/instrumented/OpenSpec validation.

Откат удаляет web build pipeline, generated assets integration и новые routes одним change-коммитом. Диагностический Ktor spike и Android app shell остаются работоспособными независимо.
