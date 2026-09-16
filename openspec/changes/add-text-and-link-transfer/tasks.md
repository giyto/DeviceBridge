## 1. Domain-модель и правила содержимого

- [x] 1.1 Добавить domain-модели text item, направления, `TEXT`/`LINK`, статусов и выбранной `BrowserSessionId`; проверить unit-тестами создание допустимых состояний и запрет противоречивых переходов.
- [x] 1.2 Реализовать UTF-8 validator с лимитом 102400 bytes и проверкой непустого содержимого; проверить unit-тестами ASCII, кириллицу, emoji, ровно 100 КБ, превышение и строку из пробелов.
- [x] 1.3 Реализовать безопасный link classifier для абсолютных `http`/`https` URL с host; проверить таблицей unit-тестов `https`, `http`, относительные значения, обычный текст, `javascript`, `data` и `file`.
- [x] 1.4 Определить `TextTransferRepository` и use cases наблюдения, отправки, повтора и приёма без Android/Ktor зависимостей; проверить architecture-тестом направление зависимостей и fake-repository unit-тестами use cases.

## 2. Text protocol и generation-scoped coordinator

- [x] 2.1 Добавить сериализуемые DTO и строгие validators для `text.send`, `text.accepted`, `text.received`, `text.ack`, `text.snapshot` и `text.error`; проверить unit-тестами round-trip JSON, обязательные поля, неизвестные поля, версию, timestamp и `messageId`.
- [x] 2.2 Реализовать in-memory text coordinator с ключом `(generationId, sessionId, messageId)` и fingerprint payload; проверить unit-тестами новое принятие, идемпотентный повтор и conflict при другом payload.
- [x] 2.3 Ограничить current-generation feed до 100 элементов с безопасным вытеснением завершённых операций; проверить unit-тестами memory bound, сохранение pending item и полную очистку при закрытии generation.
- [x] 2.4 Реализовать адресную отправку Android → выбранная session, acknowledgement и timeout/error без рассылки другим sessions; проверить coroutine-тестами две одновременные browser sessions, потерю connection и ручный retry без дубля.
- [x] 2.5 Реализовать session-scoped snapshot и дедупликацию на reconnect; проверить unit-тестом, что session получает только собственные элементы и один `messageId` не появляется дважды.

## 3. Защищённый Ktor transport и lifecycle

- [x] 3.1 Добавить `POST /api/v1/text` после существующих Host/Origin и bearer checks и ограничить body до decode; проверить server integration-тестами `200`, `400`, `401`, `409`, `413` и отсутствие входящего элемента при ошибке.
- [x] 3.2 Расширить авторизованный `/api/v1/events` адресными text events, snapshot и обработкой `text.ack`; проверить WebSocket integration-тестами обязательный первый `session.auth`, доставку выбранной session и protocol error.
- [x] 3.3 Связать text coordinator с запуском/остановкой server generation и отзывом browser session; проверить lifecycle-тестами завершение pending operations, очистку текста и невозможность доставки после revoke/stop.
- [x] 3.4 Обновить production route policy: открыть только защищённый `/api/v1/text`, сохранить file routes и `/diagnostics/*` как 404; проверить route-policy и release-isolation тестами.
- [x] 3.5 Подключить coordinator/repository/event gateway через существующий Hilt graph без циклических зависимостей; проверить `HiltGraphContractTest` и сборкой `:app:assembleDebug`.

## 4. Android text flow

- [x] 4.1 Добавить `TextViewModel`, однонаправленные actions и `TextUiState` для черновика, выбора session, предпросмотра, текущей ленты и статусов; проверить unit-тестами одну/несколько sessions, отключение получателя, success, error и retry.
- [x] 4.2 Реализовать Compose text screen с полем, явной вставкой, типом, preview, выбором одного получателя, подтверждением и карточками входящих элементов; проверить Compose-тестами loading/empty/disabled/error/success, клавиатурный фокус и крупный font scale.
- [x] 4.3 Разблокировать quick action «Текст» только при активной session и оставить «Файлы» недоступным; проверить `HomeScreenTest` для stopped, running-without-session, one-session и multi-session состояний.
- [x] 4.4 Добавить навигацию в text flow и безопасный возврат на главный экран без дублирования back stack; проверить navigation instrumented-тестом и пересозданием Activity.
- [x] 4.5 Обработать `ACTION_SEND` с `text/plain` как неподтверждённый draft и отклонить неподдерживаемые MIME/пустой payload; проверить intent-тестами отсутствие автоматической отправки и обязательный выбор session.
- [x] 4.6 Добавить platform adapters явной вставки и открытия только канонических `http`/`https` ссылок; проверить тестами, что clipboard не читается без action, а опасные схемы не создают `ACTION_VIEW`.
- [x] 4.7 Подключить current text state к главному экрану и foreground notification без содержимого сообщения; проверить unit/instrumented-тестами статусы active/completed/failed и очистку при stop.

## 5. Browser identity и web transport

- [ ] 5.1 Вынести browser identity classifier в чистый TypeScript-модуль с приоритетом `YaBrowser` → Edge → Opera → Firefox → Chrome/Chromium → Safari → neutral; проверить Vitest fixtures для Яндекс Браузера с одновременным `Chrome` token и для остальных fallback.
- [ ] 5.2 Нормализовать platform и итоговую label до 64 символов без отправки сырого User-Agent; проверить web unit-тестами empty/long/control-like hints и ожидаемую метку «Яндекс Браузер».
- [ ] 5.3 Уточнить server metadata tests для trim, лимита, control characters и plain-text отображения; проверить `ClientMetadataNormalizerTest` и pairing route tests.
- [ ] 5.4 Добавить защищённый web API client для `POST /api/v1/text` с типизированными результатами и ошибками; проверить Vitest-тестами bearer header, JSON schema, `401`, `409`, `413`, abort и отсутствие token в URL.
- [ ] 5.5 Расширить WebSocket client разбором text events, отправкой `text.ack` и snapshot/reconnect callbacks; проверить Vitest-тестами auth-before-events, dedupe, bounded reconnect и session loss.

## 6. Browser text UI

- [ ] 6.1 Реализовать отдельный `TextTransferController`, активируемый только connected session и очищаемый при revoke/`401`; проверить state-machine тестами отправку, error, retry, snapshot и потерю session во время ввода.
- [ ] 6.2 Добавить доступную text-форму и current-session feed, сохранив file controls недоступными; проверить DOM-тестами keyboard flow, accessible names, sender/time/direction/status и responsive structure.
- [ ] 6.3 Выводить пользовательское содержимое только через `textContent`/form value; проверить XSS regression-тестами HTML/script-like payload без появления исполняемых DOM nodes.
- [ ] 6.4 Реализовать явное копирование с Clipboard API только в разрешённом secure context и selectable fallback; проверить Vitest-тестами success, insecure context и rejected permission без ложного success.
- [ ] 6.5 Добавить явное открытие только server-classified `http`/`https` ссылок без auto-open; проверить DOM-тестами отсутствие navigation при получении и отсутствие open action для обычного/опасного текста.
- [ ] 6.6 Обновить стили светлой/тёмной темы и viewport 360–1920 px для формы и карточек; проверить contract-тестами отсутствие горизонтального overflow и видимый `:focus-visible`.

## 7. Сквозная автоматическая проверка

- [ ] 7.1 Запустить `npm.cmd --prefix web test` и `npm.cmd --prefix web run build`, исправить все ошибки и проверить, что собранные assets не обращаются к внешним origin и не регистрируют service worker.
- [ ] 7.2 Запустить `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, исправить все ошибки и проверить успешную упаковку актуальных web assets.
- [ ] 7.3 Запустить `.\gradlew.bat :app:connectedDebugAndroidTest` на API 29 и API 37.1; проверить успешные Compose, lifecycle, Ktor и `ACTION_SEND` сценарии на обоих образах.
- [ ] 7.4 Провести server integration-сценарий с двумя browser sessions: адресная отправка в обе стороны, idempotent retry, revoke, stop/restart, неправильный token, 100 КБ и 413; сохранить результаты в manual verification checklist.

## 8. Ручная приёмка и завершение change

- [ ] 8.1 Проверить pairing и точную label в актуальных Chrome, Edge и Яндекс Браузере на Windows: Яндекс Браузер не должен отображаться как Chrome; зафиксировать результат каждого browser в checklist.
- [ ] 8.2 Проверить Android → Browser и Browser → Android для текста, кириллицы, emoji, многострочного содержимого и HTTP(S)-ссылки; подтвердить sender/time/direction/status, отсутствие auto-open и адресность выбранному браузеру.
- [ ] 8.3 Обновить страницу в той же вкладке и проверить восстановление session и bounded text snapshot без повторного pairing и дублей; затем отозвать session и проверить возврат к pairing.
- [ ] 8.4 Проверить явную вставку, Android Share Target и browser manual-copy fallback на LAN HTTP; подтвердить отсутствие скрытого clipboard read и ложного сообщения об успешном копировании.
- [ ] 8.5 Проверить, что «Файлы» остаются недоступными, `/api/v1/files`, `/api/v1/transfers/*` и `/diagnostics/*` возвращают 404, а приложение не выполняет внешние запросы.
- [ ] 8.6 После подтверждения ручной приёмки отметить выполненные tasks, запустить `openspec validate add-text-and-link-transfer --strict`, синхронизировать main specs и архивировать change через OpenSpec archive workflow.
