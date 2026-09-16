## Purpose

Определить безопасный и проверяемый lifecycle авторизации browser client в локальной сети: одноразовый код, решение пользователя на Android host, session token, защита API и немедленный отзыв доступа.

## Requirements

### Requirement: Сервер выпускает ограниченный одноразовый код

При каждом успешном явном запуске production server DeviceBridge MUST создавать криптографически случайный шестизначный числовой pairing code. Код MUST действовать не более пяти минут, MUST отображаться только на Android host во время Running и MUST становиться недействительным после успешного pairing, истечения срока или завершения server lifecycle.

#### Scenario: Сервер начал новую сессию

- **WHEN** production server переходит в Running
- **THEN** Android host показывает новый шестизначный pairing code и оставшееся время его действия
- **AND** код не присутствует в URL, web manifest, web assets, уведомлении или журналах

#### Scenario: Код истёк

- **WHEN** срок действия активного pairing code заканчивается при работающем сервере
- **THEN** старый код больше не принимается
- **AND** Android host показывает новый код с новым сроком действия

#### Scenario: Код успешно использован

- **WHEN** пользователь разрешает pairing request, созданный с действующим кодом
- **THEN** использованный код немедленно становится недействительным
- **AND** следующий browser client должен использовать новый код

### Requirement: Неверные попытки pairing ограничиваются

DeviceBridge MUST принимать pairing code только через same-origin `POST /api/v1/session/confirm`, MUST проверять его без утечки ожидаемого значения и MUST временно блокировать новые попытки от источника после пяти неверных вводов. Начало pairing через `POST /api/v1/session/challenge` MUST возвращать только непривилегированный opaque challenge identifier и публичные параметры срока ожидания.

#### Scenario: Введён неверный код

- **WHEN** browser client отправляет неверный действующий по формату pairing code
- **THEN** сервер отклоняет попытку без создания session или запроса подтверждения
- **AND** сообщает безопасную ошибку и число оставшихся попыток без раскрытия ожидаемого кода

#### Scenario: Выполнена пятая неверная попытка

- **WHEN** один source IPv4 выполняет пятую неверную попытку в текущем окне ограничения
- **THEN** сервер блокирует новые попытки этого источника не менее чем на 60 секунд
- **AND** browser UI показывает отдельное состояние блокировки и оставшееся время

#### Scenario: Код истёк до отправки

- **WHEN** browser client отправляет ранее выданный, но уже истёкший код
- **THEN** сервер возвращает отдельную ошибку истечения без создания pending request
- **AND** попытка не может авторизовать browser session

### Requirement: Правильный код требует подтверждения на телефоне

Знание действующего pairing code MUST создавать только ограниченный по времени pending request и MUST NOT само по себе выдавать session token. Android host MUST показать отдельный запрос с browser label, source IPv4 и действиями разрешения и отказа; эти сведения MUST считаться информационными, а не доказательством личности клиента.

#### Scenario: Browser отправил правильный код

- **WHEN** browser client отправляет действующий код и допустимый browser label
- **THEN** web UI переходит в состояние ожидания, а Android host показывает соответствующий pending request
- **AND** защищённые API остаются недоступными этому browser client

#### Scenario: Пользователь разрешает подключение

- **WHEN** пользователь на Android host разрешает конкретный актуальный pending request
- **THEN** сервер создаёт одну browser session и возвращает token только запросившему клиенту
- **AND** Android и web UI показывают состояние «Подключено»

#### Scenario: Пользователь отклоняет или игнорирует подключение

- **WHEN** пользователь отклоняет pending request либо не отвечает до завершения ограниченного времени ожидания
- **THEN** сервер не создаёт session и не выдаёт token
- **AND** browser UI показывает различимое состояние отказа или истечения ожидания

#### Scenario: Решение относится к конкретному запросу

- **WHEN** одновременно существуют несколько pending requests или приходит повторное решение
- **THEN** решение применяется только к request с соответствующим opaque identifier
- **AND** один request не может создать более одной session

### Requirement: Session token является случайным и ограниченным lifecycle

После разрешения DeviceBridge MUST выпускать opaque bearer token с криптографической энтропией не менее 128 бит. Token MUST принадлежать конкретным server generation и browser session, MUST передаваться только в теле успешного same-origin ответа или авторизационном сообщении/заголовке и MUST NOT помещаться в URL, persistent Android storage, backup или журналы.

#### Scenario: Session разрешена

- **WHEN** Android host подтверждает pending request
- **THEN** server возвращает новый непредсказуемый token ровно один раз
- **AND** последующие HTTP-запросы передают его как `Authorization: Bearer <token>`

#### Scenario: Browser обновляет текущую вкладку

- **WHEN** авторизованный пользователь обновляет web page в той же browser tab
- **THEN** browser client может восстановить token только из tab-scoped session storage
- **AND** token отсутствует в local storage, cookie и URL

#### Scenario: Token относится к прошлому server generation

- **WHEN** клиент предъявляет token после остановки и нового явного запуска сервера
- **THEN** server отклоняет token как недействительный
- **AND** browser client должен пройти новый pairing

### Requirement: Production API закрыт session authorization

Production server MUST оставлять без token только `GET /`, разрешённые `/assets/*`, `GET /web-manifest.json`, `POST /api/v1/session/challenge` и `POST /api/v1/session/confirm`. Реализованные status и session routes MUST требовать действующий bearer token; WebSocket `/api/v1/events` MUST требовать token первым протокольным сообщением до отправки каких-либо приватных событий.

#### Scenario: Защищённый HTTP-запрос без token

- **WHEN** клиент запрашивает реализованный защищённый endpoint без token либо с неизвестным, отозванным или устаревшим token
- **THEN** сервер возвращает `401 Unauthorized`
- **AND** ответ не раскрывает состояние устройства, список sessions или другие приватные данные

#### Scenario: Авторизованный запрос статуса

- **WHEN** browser session запрашивает `GET /api/v1/status` с действующим bearer token и допустимыми Host/Origin
- **THEN** сервер возвращает совместимую версию протокола и фактическое состояние соединения
- **AND** не возвращает pairing codes или tokens других sessions

#### Scenario: WebSocket не авторизован

- **WHEN** клиент открывает `/api/v1/events`, но не отправляет допустимое auth message первым сообщением за ограниченное время
- **THEN** сервер закрывает соединение с определённой протокольной причиной
- **AND** не отправляет клиенту session events до закрытия

#### Scenario: WebSocket авторизован

- **WHEN** первым сообщением WebSocket переданы поддерживаемая `protocolVersion`, уникальный `messageId`, `type`, `timestamp` и действующий token
- **THEN** сервер связывает соединение с соответствующей browser session
- **AND** последующие события не требуют передачи token в URL

### Requirement: Пользователь управляет активными browser sessions

Android host MUST показывать фактическое число и список активных browser sessions и MUST позволять немедленно отозвать каждую session отдельно. Авторизованный browser client MUST иметь возможность завершить собственную session через защищённый `DELETE /api/v1/session`.

#### Scenario: Browser session подключена

- **WHEN** pairing успешно завершён и session остаётся действующей
- **THEN** Android UI и foreground notification показывают актуальное число подключённых браузеров
- **AND** Android UI показывает информационный label и время подключения session без раскрытия token

#### Scenario: Пользователь отзывает browser session

- **WHEN** пользователь выбирает отключение конкретного browser client на Android host
- **THEN** token этой session немедленно становится недействительным и её WebSocket закрывается
- **AND** browser UI показывает потерю авторизации и предлагает новый pairing

#### Scenario: Browser завершает собственную session

- **WHEN** авторизованный browser client выполняет `DELETE /api/v1/session`
- **THEN** сервер аннулирует только предъявившую token session
- **AND** другие browser sessions продолжают работать

#### Scenario: Server lifecycle завершается

- **WHEN** сервер остановлен пользователем, сеть потеряна, IPv4 изменён или локальное разрешение отозвано
- **THEN** все pending requests, active sessions и tokens текущего generation аннулируются
- **AND** ни одна session не восстанавливается автоматически после нового запуска

### Requirement: Session protocol не раскрывает секреты и не расширяет доверие

Session DTO и ошибки MUST иметь явную поддерживаемую protocol version, ограниченные размеры и безопасную сериализацию. DeviceBridge MUST NOT сохранять текущий session token как trusted-browser credential и MUST NOT считать browser label, User-Agent, IP или Host самостоятельным доказательством авторизации.

#### Scenario: Получена неподдерживаемая версия протокола

- **WHEN** pairing, HTTP или WebSocket message содержит неподдерживаемую major protocol version
- **THEN** server отклоняет сообщение с понятным version error
- **AND** не создаёт и не изменяет session

#### Scenario: Получены лишние или слишком большие данные клиента

- **WHEN** challenge или confirm payload превышает лимит либо содержит недопустимые поля/значения
- **THEN** server отклоняет запрос до создания pending request
- **AND** не отражает непроверенные данные как HTML в Android или web UI

#### Scenario: Browser просит постоянное доверие

- **WHEN** текущий web client пытается сохранить или обменять session token на постоянный credential
- **THEN** production API этого change не предоставляет такую операцию
- **AND** повторный server lifecycle требует новый pairing
