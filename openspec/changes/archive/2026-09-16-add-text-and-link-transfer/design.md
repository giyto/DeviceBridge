## Context

См. мотивацию в `proposal.md`. Сейчас один Android-модуль уже разделён на domain, data/server, feature и protocol пакеты; production Ktor CIO server управляется foreground service, а `BrowserSessionCoordinator` владеет generation-scoped sessions и авторизованными WebSocket connections. Web shell написан на TypeScript без UI framework, хранит bearer token в `sessionStorage` и восстанавливает ту же session после обновления вкладки.

Новый flow пересекает Android UI, domain state, Ktor HTTP/WebSocket, session lifecycle и web UI. При этом он должен остаться полностью локальным, не переносить ответственность transfer в `BrowserSessionCoordinator` и не создавать постоянную историю раньше отдельного этапа ТЗ.

## Goals / Non-Goals

**Goals:**

- сохранить направление зависимостей Clean Architecture: UI и transport adapters зависят от use cases/domain contracts, а domain не знает о Compose, Ktor, Android clipboard или browser API;
- получить один версионированный text protocol для обоих направлений с единым `messageId`, acknowledgement и идемпотентностью;
- ограничить память и время ожидания, не записывая полный текст на диск;
- расширить существующую session/WebSocket инфраструктуру без второго server instance и без нового backend;
- вынести распознавание browser family в чистую тестируемую функцию и исправить приоритет Яндекс Браузера.

**Non-Goals:**

- Room history, короткие постоянные preview и настройки хранения;
- передача файлов, общая transfer queue и checksum;
- trusted-browser token, фоновая синхронизация и доставка после завершения server generation;
- шифрование LAN transport или изменение принятого HTTP/WebSocket ограничения MVP;
- подтверждение подлинности browser family по User-Agent.

## Decisions

### 1. Отдельная domain-граница text transfer

В `domain` появятся неизменяемые модели text item, направления, типа содержимого и статуса, небольшой `TextTransferRepository` и use cases наблюдения, отправки, повтора и приёма. Generation-scoped реализация в `data` будет называться text coordinator и владеть текущей лентой, idempotency registry и ожидающими acknowledgement.

Связь с browser session выполняется по `BrowserSessionId` и через узкий gateway отправки session events. Авторизация, выдача token и закрытие WebSocket остаются ответственностью существующего session coordinator; text coordinator получает только уже авторизованный session context. Закрытие server generation сначала прекращает text operations, затем закрывает sessions и listener в существующем lifecycle порядке.

Альтернатива — добавить text state прямо в `BrowserSessionCoordinator`. Она отвергнута, потому что смешивает аутентификацию, пользовательские данные и последующие file transfers в один крупный компонент.

### 2. Один канонический протокол поверх защищённого HTTP и WebSocket

Browser → Android использует `POST /api/v1/text` с bearer token. Android → Browser и server snapshot идут через уже авторизованный `/api/v1/events`; browser возвращает `text.ack` по тому же socket. Все envelopes используют `protocolVersion = 1`, opaque `messageId`, строковый `type` и `timestamp` в epoch milliseconds, как существующий session protocol.

Типы сообщений:

- `text.send` — HTTP command browser → Android с `content`;
- `text.accepted` — HTTP response с server timestamp, вычисленным `contentKind` и текущим status;
- `text.received` — адресное событие Android → конкретная browser session;
- `text.ack` — подтверждение помещения входящего элемента в browser feed;
- `text.snapshot` — ограниченный набор элементов этой session после WebSocket re-authentication;
- `text.error` — протокольная ошибка, которую можно соотнести с `messageId`.

Server сам вычисляет `contentKind` и server timestamp; присланное клиентом время не используется как доказательство порядка или безопасности. HTTP contract использует `200` для нового или идемпотентно повторённого принятия, `400` для некорректной схемы/версии, `401` для session authorization, `409` для повторного `messageId` с другим payload и `413` для превышения 100 КБ. Request body ограничивается до десериализации с небольшим фиксированным запасом на JSON envelope.

Альтернатива — передавать команды обоих направлений только по WebSocket. Она отвергнута для Browser → Android: защищённый POST проще повторять, тестировать и связывать с определённым HTTP-результатом, тогда как WebSocket уже нужен для server-push.

### 3. Идемпотентность и текущая лента живут только в server generation

Ключ операции — `(generationId, sessionId, messageId)`. Registry хранит fingerprint канонического payload и итог первого принятия. Повтор с тем же fingerprint возвращает тот же результат, а другое содержимое с тем же ID даёт conflict. Android → Browser остаётся `sending` до `text.ack`; при закрытии соединения или bounded timeout операция становится `failed`, и только пользователь запускает повтор.

Coordinator хранит не более 100 text items суммарно на generation и вытесняет самые старые завершённые элементы; pending operations не вытесняются до результата. При WebSocket re-authentication он отдаёт snapshot только элементов, доступных этой session. Web client также дедуплицирует snapshot/events по `messageId` и ограничивает DOM feed. Никакой full-text state не записывается в Room, DataStore, файл, backup или Android saved-state bundle.

Альтернатива — сразу внедрить Room history. Она отложена до отдельного change: ТЗ требует иной retention contract и безопасный короткий preview, а текущему этапу достаточно generation-scoped state.

### 4. Классификация ссылок одинакова на Android и server

Domain validator принимает непустой текст до 102400 UTF-8 bytes. Link classifier разбирает всё значение стандартным URI parser и возвращает `LINK` только для абсолютных `http`/`https` URL с host; `javascript`, `data`, `file`, относительные и смешанные значения остаются `TEXT`. Открытие выполняется отдельным пользовательским action, а transport никогда не запускает intent или navigation автоматически.

Чтобы Android preview и server response не расходились, правила оформляются как чистая domain-функция с общей таблицей тестовых примеров. Web может показывать предварительную подсказку, но считает server-derived `contentKind` каноническим.

### 5. Android text feature использует ViewModel и явные platform adapters

Пакет `feature/text` содержит `TextViewModel`, `TextUiState`, actions и Compose screen. При одной session она выбирается явно в UI как единственный получатель; при нескольких пользователь выбирает один client. Ввод и shared text живут в ViewModel текущего процесса, поэтому переживают configuration change, но не становятся постоянной историей.

`ACTION_SEND` с `text/plain` направляется в text feature как черновик и никогда не вызывает send use case автоматически. Clipboard читается отдельным Android adapter только по кнопке «Вставить». `ACTION_VIEW` создаётся только после повторной проверки канонического `http`/`https` URI и явного нажатия «Открыть».

Главный экран и foreground notification наблюдают тот же repository state: уведомление показывает только факт ожидающей text operation, не её содержимое, и возвращается к обычному server status после acknowledgement или ошибки.

Альтернатива — читать clipboard при открытии экрана или отправлять share intent сразу. Оба варианта нарушают явное пользовательское действие из ТЗ.

### 6. Web text state отделяется от session state

Чистый `TextTransferController` получает авторизованный API client и event channel, а view отображает form/feed. Существующий `SessionController` остаётся владельцем pairing и token; при переходе в connected он активирует text controller, а при `401`, revoke или исчерпании reconnect — деактивирует его. DOM получает пользовательское содержимое только через `textContent`/value, никогда через `innerHTML`.

Copy helper сначала проверяет secure context и `navigator.clipboard`, перехватывает отказ permission и переключает карточку на выделяемое поле с инструкцией. Это важно для LAN HTTP, где Clipboard API обычно недоступен. Новые runtime dependencies и service worker не добавляются.

### 7. Browser identity определяется product-specific-first

Чистая функция получает ограниченный набор runtime hints (`userAgentData.brands`, `userAgent`, platform) и проверяет семейства в порядке: `YaBrowser`, Edge (`Edg`), Opera (`OPR`), Firefox, Chrome/Chromium, Safari, затем нейтральный «Браузер». Поэтому Chromium-compatible token больше не перехватывает Яндекс Браузер. Platform добавляется только после trim/ограничения; полный сырой User-Agent не отправляется как label.

Server применяет существующую нормализацию: trim, непустое значение, максимум 64 символа, отсутствие ISO control characters. Compose и web выводят label как plain text. Метка остаётся client-provided подсказкой и не влияет на pairing, token или права.

Альтернатива — определять browser только на Android по HTTP User-Agent. Она не даёт свойства подлинности, дублирует parser на двух языках и хуже работает с сокращёнными UA hints; для информационной подписи достаточно tested web classifier плюс server bounds.

### 8. Production route policy расширяется минимально

Allowlist открывает только защищённый `POST /api/v1/text` и новые text event types. File routes продолжают отвечать 404. Маршрут устанавливается в том же Ktor application и использует существующие Host/Origin и bearer authorization policies до чтения пользовательского payload.

Новых разрешений Android и внешних зависимостей не требуется. Manifest меняется только для declarative `ACTION_SEND text/plain` intent filter, если его ещё нет.

## Risks / Trade-offs

- [Локальный HTTP раскрывает текст участнику той же недоверенной сети] → сохранить видимое предупреждение, session auth и рекомендацию использовать только доверенную сеть; TLS остаётся отдельным security spike.
- [User-Agent можно подделать или браузер может сократить hints] → использовать метку только как информационную и показывать нейтральный fallback без утверждения личности.
- [Сообщение доставлено в browser feed, но acknowledgement потерян] → повтор с тем же `messageId` дедуплицируется server и web client; UI не обещает, что пользователь прочитал или скопировал текст.
- [До 100 КБ текста и snapshot увеличивают память] → общий bounded generation feed, лимит body до decode и отсутствие disk persistence.
- [Session закрывается между выбором адресата и send] → use case повторно проверяет active session непосредственно перед регистрацией операции и возвращает recoverable error.
- [Clipboard API заблокирован на HTTP origin] → всегда сохранять selectable fallback и не показывать ложное состояние success.
- [Snapshot и live event могут прийти одновременно] → reducer дедуплицирует по `messageId`, а WebSocket sender сериализует events для одной session.

## Migration Plan

1. Добавить domain models/validators и in-memory coordinator без публикации нового route.
2. Расширить protocol DTO и WebSocket dispatcher, затем включить защищённый text route в production policy.
3. Добавить Android text feature и `ACTION_SEND` preview.
4. Добавить web controller/view, browser classifier и собрать assets в APK атомарно с Android-кодом.
5. Проверить unit, server integration, Vitest, Compose и ручную матрицу API 29/37.1 плюс Chrome/Edge/Яндекс Браузер.

Миграции пользовательских данных нет. Rollback возвращает прежние web assets и закрывает `/api/v1/text`; generation-scoped text state исчезает при остановке server и не требует очистки persistent storage.
